import Foundation

// MARK: - [T-group-chat] §4 Group-chat round scheduler (iOS)
//
// Design doc: docs/agent-group-chat-design.md. Mirrors the Android block in
// ChatViewModel.kt one-for-one (selectGroupSpeakers / runGroupRound /
// rebuildAgentHistoryForSpeaker / model chain / queued-drain). One group round
// = sequential per-member agent loops (invariant: ONE loop per session — D5).
// Later speakers rebuild history from the DB and therefore see earlier
// members' replies from the same round (A drafts, B reviews — for free).
//
// Injection mechanism: iOS runAgentLoop() resolves its provider and system
// prompt internally (Android's takes parameters). Rather than re-plumb the
// 12k-line loop, the round scheduler sets `groupEntryOverride` /
// `baseSystemPromptOverride` (both declared in AIChatViewModel.swift) before
// calling runAgentLoop() and clears them after — the loop's own memory/skill
// appends then stack on the speaker's identity+group base, exactly like an
// ordinary chat stacks on the session persona.
extension AIChatViewModel {

    /// @全体 trigger markers (D2). Case-insensitive for the ASCII one.
    private static let groupAllMarkers = ["@全体", "@所有人", "@all"]

    /// §4.2 Select the speakers for one round.
    ///
    ///  - any @全体 marker → every member with participatesInRound, roster
    ///    order, host last (host is repositioned even when @-named directly).
    ///  - else @-mentions → named members only (roster order; host last; a
    ///    direct @mention bypasses the participation toggle). Longest name
    ///    wins at a shared @-position ("@小艾二号" does not also summon 小艾).
    ///  - no marker, no mention → speak to all (D2: an unaddressed message is
    ///    addressed to the room).
    func selectGroupSpeakers(
        _ userText: String,
        group: AgentGroupRecord,
        resolved: [ResolvedGroupMember],
    ) -> [ResolvedGroupMember] {
        let roundEligible = resolved.filter { $0.participatesInRound }
        // Longest-name-first scan: at each '@', the longest member name
        // that prefix-matches wins, so "@小艾二号" never also triggers 小艾.
        let byName = resolved
            .map { ($0.role.metadata.name.isEmpty ? "Agent" : $0.role.metadata.name, $0) }
            .sorted { $0.0.count > $1.0.count }
        var mentionedIds = Set<String>()
        var searchStart = userText.startIndex
        while let at = userText[searchStart...].firstIndex(of: "@") {
            let rest = String(userText[userText.index(after: at)...])
            if let (name, member) = byName.first(where: { !$0.0.isEmpty && rest.hasPrefix($0.0) }) {
                mentionedIds.insert(member.role.roleId)
                _ = name // matched longest name at this position
            }
            searchStart = userText.index(after: at)
        }
        // [T-group-chat][P2-fix] D2, implemented correctly this time: @全体
        // marker OR a PLAIN unaddressed message (no @mention at all) → the
        // whole round-eligible roster. The first cut only honored the
        // markers, so a plain "hi" selected NOBODY and the round silently
        // produced zero replies. Only an explicit @mention narrows it.
        let mentioned: [ResolvedGroupMember]
        if Self.groupAllMarkers.contains({ userText.range(of: $0, options: .caseInsensitive) != nil }) ||
            mentionedIds.isEmpty {
            mentioned = roundEligible
        } else {
            // Preserve roster order.
            mentioned = resolved.filter { mentionedIds.contains($0.role.roleId) }
        }
        // Host speaks last (D3) — regardless of roster position.
        guard let hostId = group.hostRoleId else { return mentioned }
        return mentioned.sorted { a, b in
            let aHost = a.role.roleId == hostId
            let bHost = b.role.roleId == hostId
            if aHost != bHost { return !aHost } // non-host first
            return false // stable order preserved otherwise
        }
    }

    /// §5 Model resolution chain: group unified-model override > role.model >
    /// session default (nil → caller falls back to the session's current
    /// entry). A dead entry (instance deleted / model gone) degrades to the
    /// NEXT level, not an error — one member's stale config must never fail
    /// the whole round; the caller surfaces a notice.
    private func resolveSpeakerModelEntry(
        group: AgentGroupRecord,
        role: RoleFile,
    ) -> ModelEntry? {
        func find(_ ref: AgentGroupModelRef?) -> ModelEntry? {
            guard let ref else { return nil }
            // Match BOTH the instance id and the model id — a bare model id is
            // ambiguous across provider instances.
            return ProviderConfigStore.shared.visibleEntries(for: ref.providerInstanceId)
                .first { $0.baseModel.id == ref.modelId }
        }
        return find(group.modelOverride) ?? find(role.metadata.model)
    }

    /// §4.4 Rebuild agentHistory from the DB **from this speaker's point of
    /// view**: their own assistant turns keep their full parts (tools intact);
    /// other members' turns are flattened to author-labeled user turns
    /// (`[作者名]: …` — text only, tool internals stay private to their own
    /// loop). Consecutive flattened turns merge into one user message.
    /// Loop-internal user rows (tool results) are skipped.
    func rebuildAgentHistoryForSpeaker(
        selfRoleId: String?,
        members: [ResolvedGroupMember],
    ) async {
        agentHistory.removeAll()
        guard let sessionId else { return }
        let rows = await ChatStore.shared.loadMessages(sessionId: sessionId)
        let nameByRoleId = Dictionary(uniqueKeysWithValues: members.map {
            ($0.role.roleId, $0.role.metadata.name.isEmpty ? "Agent" : $0.role.metadata.name)
        })
        var pending = ""
        func flushPending() {
            let text = pending.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty {
                agentHistory.append(AgentMessage(role: .user, parts: [.text(text)]))
            }
            pending = ""
        }
        for row in rows {
            switch row.role {
            case .user:
                // Skip loop-internal user rows (all-toolResult parts): they
                // belong to the loop that produced them, not the transcript.
                let isInternal = !row.parts.isEmpty && row.parts.allSatisfy {
                    if case .toolResult = $0 { return true } else { return false }
                }
                if isInternal { continue }
                flushPending()
                agentHistory.append(AgentMessage(role: .user, parts: row.parts))
            case .assistant:
                if row.roleId == selfRoleId {
                    flushPending()
                    agentHistory.append(AgentMessage(role: .assistant, parts: row.parts))
                } else {
                    let author = nameByRoleId[row.roleId] ?? "Assistant"
                    let text = Self.forwardExtractText(row.parts)
                        .replacingOccurrences(of: "[图片未转发]", with: "[图片]")
                    if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        pending += "[\(author)]: \(text)\n\n"
                    }
                }
            }
        }
        flushPending()
        // [T-group-chat][P2-fix] Merge consecutive USER entries (raw user
        // rows and/or flattened foreign turns) into ONE user message:
        // strict OpenAI-compatible upstreams reject non-alternating roles
        // with a 400 (observed on the gateway). Mirrors the Android merge
        // pass in rebuildAgentHistoryForSpeaker.
        if agentHistory.count > 1 {
            var merged: [AgentMessage] = []
            merged.reserveCapacity(agentHistory.count)
            for m in agentHistory {
                if m.role == .user, let prev = merged.last, prev.role == .user {
                    var combined = prev
                    if case .text(let prevText)? = combined.parts.last,
                       case .text(let newText) = m.parts.first {
                        combined.parts[combined.parts.count - 1] = .text(
                            [prevText, newText].filter { !$0.isEmpty }.joined(separator: "\n\n"))
                        combined.parts.append(contentsOf: m.parts.dropFirst())
                    } else {
                        combined.parts.append(contentsOf: m.parts)
                    }
                    merged[merged.count - 1] = combined
                } else {
                    merged.append(m)
                }
            }
            if merged.count != agentHistory.count {
                agentHistory = merged
            }
        }
    }

    /// §4.5 Group context appended to the speaker's identity section.
    func buildGroupSection(
        group: AgentGroupRecord,
        selfRole: RoleFile,
        resolved: [ResolvedGroupMember],
    ) -> String {
        var lines = "\n\n## Group chat context\n"
        lines += "You are speaking in a group chat \"\(group.name)\". Members (round-robin order):\n"
        for m in resolved {
            let name = m.role.metadata.name.isEmpty ? "Agent" : m.role.metadata.name
            let blurb = m.role.metadata.style
                .split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: true)
                .first.map(String.init)?
                .prefix(60)
            let tag: String
            if m.role.roleId == selfRole.roleId { tag = " (you)" }
            else if m.role.roleId == group.hostRoleId { tag = " (moderator)" }
            else { tag = "" }
            lines += "- \(name)\(tag)"
            if let blurb, !blurb.isEmpty { lines += " — \(blurb)" }
            lines += "\n"
        }
        lines += "The human user runs this room; their messages are plain user turns. "
        lines += "Other members' replies appear as user turns prefixed \"[name]:\". "
        lines += "Your own previous replies appear as your assistant turns.\n"
        if let instruction = group.instruction, !instruction.isEmpty {
            lines += "Group instruction from the user (always applies):\n\(instruction)\n"
        }
        if selfRole.roleId == group.hostRoleId {
            lines += "You are the moderator of this group. After others have spoken, summarize " +
                "the discussion so far, surface disagreements, and propose concrete next steps. Keep it concise.\n"
        }
        return lines
    }

    /// SystemInfo row helper for group-round notices.
    private func groupNotice(_ text: String) {
        appendSystemInfo(text, icon: "💡")
    }

    /// §4.3 One group round: select speakers, run each member's loop
    /// sequentially. A member failure appends a systemInfo row and continues;
    /// cancellation aborts the whole round.
    func runGroupRound(userText: String) async throws {
        guard let gid = agentGroupId,
              let group = GroupStore.groupById(gid) else {
            groupNotice("Group unavailable — falling back to a plain chat.")
            return
        }
        let resolved = GroupStore.resolveMembers(group)
        if resolved.isEmpty {
            groupNotice("No available members in this group.")
            return
        }
        let staleCount = group.members.count - resolved.count
        if staleCount > 0 { groupNotice("\(staleCount) member(s) skipped (role deleted).") }

        let targets = selectGroupSpeakers(userText, group: group, resolved: resolved)
        if targets.isEmpty {
            groupNotice("No member addressed.")
            return
        }

        for member in targets {
            if !isProcessing { break } // cancelled — abort the whole round (D5)
            speakerRole = member.role
            let name = member.role.metadata.name.isEmpty ? "Agent" : member.role.metadata.name
            do {
                // §5 chain: group unified-model override > role.model > session
                // default. A dead override degrades to the session default with
                // a notice — one member's stale config never kills the round.
                var entry = resolveSpeakerModelEntry(group: group, role: member.role)
                if entry == nil, group.modelOverride != nil {
                    groupNotice("“\(name)” unified model unavailable — using the default model.")
                }
                if entry == nil { entry = resolveCurrentEntry() }
                guard let speakerEntry = entry else {
                    groupNotice("“\(name)” skipped: no model configured.")
                    continue
                }
                // Inject the speaker's identity + group context as the loop's
                // base prompt (the loop's memory/skill appends stack on top).
                baseSystemPromptOverride =
                    RoleSystemPromptBuilder.identitySection(for: member.role)
                    + buildGroupSection(group: group, selfRole: member.role, resolved: resolved)
                groupEntryOverride = speakerEntry
                defer {
                    baseSystemPromptOverride = nil
                    groupEntryOverride = nil
                }
                await rebuildAgentHistoryForSpeaker(selfRoleId: member.role.roleId, members: resolved)
                try await runAgentLoop()
            } catch is CancellationError {
                baseSystemPromptOverride = nil
                groupEntryOverride = nil
                throw CancellationError() // cancel aborts the whole round
            } catch {
                baseSystemPromptOverride = nil
                groupEntryOverride = nil
                logger.error("group round: member \(name) failed: \(error)")
                groupNotice("“\(name)” failed to reply: \(error.localizedDescription)")
            }
        }
        speakerRole = sessionRole
    }

    /// §4.6 Group variant of the queue drain: each queued text = its own round.
    func drainQueuedGroupPrompts() async {
        while !promptQueue.isEmpty {
            let queued = promptQueue
            promptQueue.removeAll()
            for queuedPrompt in queued {
                if !isProcessing { return }
                let text = queuedPrompt.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { continue }
                // Persist + append the queued turn (plain text — queued prompts
                // carry no fresh attachments in the group path).
                let userMessage = AgentMessage(role: .user, parts: [.text(text)])
                agentHistory.append(userMessage)
                if let persistedId = await persistAgentMessage(userMessage) {
                    agentHistory[agentHistory.count - 1].dbMessageId = persistedId
                }
                let userMsg = ChatMessage(role: .user, content: text)
                userMsg.queuedPromptId = queuedPrompt.id
                userMsg.isQueued = false
                messages.append(userMsg)
                do {
                    try await runGroupRound(userText: text)
                } catch is CancellationError {
                    return
                } catch {
                    groupNotice("Round for “\(text.prefix(20))…” failed: \(error.localizedDescription)")
                }
            }
        }
    }
}
