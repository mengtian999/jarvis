import Foundation

// MARK: - Domain models [T-group-chat]

/// One roster entry inside an [AgentGroupRecord.members]. Array position in
/// the persisted members_json IS the round-robin speaking order.
///
/// `participatesInRound == false` = excluded from @全体 rounds (a direct
/// @mention still reaches the member — the toggle is a round-membership
/// switch, not a mute button).
struct AgentGroupMember: Equatable {
    var roleId: String
    var participatesInRound: Bool = true
}

/// [T-group-chat-model-override] A complete model reference — the same shape
/// as the per-message attribution snapshot columns (`model_id` +
/// `provider_instance_id`), because users have several provider instances that
/// can carry the same model id. A bare modelId is ambiguous across instances.
struct AgentGroupModelRef: Equatable {
    var modelId: String
    var providerInstanceId: String
}

/// [T-group-chat] An agent group: a named roster of roles reusable across
/// group sessions. Raw JSON strings are the persisted form (membersJson /
/// modelOverrideJson) exactly as stored in `agent_groups`; the parsed views
/// are computed properties so the facade never re-serializes on read.
///
/// Mirrors Android `com.jarvis.app.agent.AgentGroup` (GroupStore.kt).
struct AgentGroupRecord: Identifiable, Equatable {
    var id: String
    var name: String
    /// 群公告 / group instruction — injected into every member's system prompt. Nil = none.
    var instruction: String?
    /// Roster JSON, round-robin speaking order. See AgentGroupStore.members(from:).
    var membersJson: String
    /// Moderator role. Nil = none (the default). Speaks last; extra prompt duties.
    var hostRoleId: String?
    /// [T-group-chat-model-override] Unified-model override JSON. Nil = OFF (default).
    var modelOverrideJson: String?
    /// Reserved for agent-to-agent @mention rounds. Always 1; no reader yet.
    var maxRounds: Int = 1
    var createdAt: Date
    var updatedAt: Date

    var members: [AgentGroupMember] { AgentGroupStore.members(from: membersJson) }
    var modelOverride: AgentGroupModelRef? { AgentGroupStore.modelOverride(from: modelOverrideJson) }
}

/// [T-group-chat] A roster entry resolved against the live RoleStore.
/// Unresolvable members (role deleted) are dropped by
/// [AgentGroupStore.resolveMembers], NOT replaced with the default role — a
/// deleted "架构师" must not come back speaking as Jarvis.
struct ResolvedGroupMember: Equatable {
    var role: RoleFile
    var participatesInRound: Bool
}

// MARK: - GroupStore

/// [T-group-chat] 门面：agent_groups 的内存快照 + CRUD。风格仿
/// [RoleStore]（enum + 快照缓存 + 通知），存储走 ChatStore 的 SQL 区块。
/// 镜像 Android `com.jarvis.app.agent.GroupStore`。
enum GroupStore {

    /// Fired after any group mutation (create/update/delete) so the session
    /// list and pickers re-read the snapshot. Mirrors `.rolesChanged`.
    static let groupChanged = Notification.Name("MinisGroupChanged")

    private static let queue = DispatchQueue(label: "MinisGroupStore")

    /// Render-path snapshot — session list rows / pickers never hit SQLite.
    /// Mutated only inside [queue]; reads are safe (array swaps are atomic
    /// enough for our read use, same discipline as RoleStore.rolesSnapshotCache).
    private static var snapshotCache: [AgentGroupRecord] = []

    static var snapshot: [AgentGroupRecord] {
        queue.sync { snapshotCache }
    }

    static func groupById(_ groupId: String?) -> AgentGroupRecord? {
        guard let groupId else { return nil }
        return snapshot.first { $0.id == groupId }
    }

    /// Re-read all groups from ChatStore into the snapshot. Called after every
    /// mutation and whenever a UI surface needs fresh data (groups are few;
    /// full reload is cheap). ChatStore is an actor, so this is async — UI
    /// call sites wrap it in a Task.
    @discardableResult
    static func refreshSnapshot() async -> [AgentGroupRecord] {
        let groups = await ChatStore.shared.listAgentGroups()
        queue.sync { snapshotCache = groups }
        return groups
    }

    /// Fire-and-forget refresh for sync merge paths (RoleSyncExtensions) that
    /// are already async but shouldn't block on the snapshot swap.
    static func refreshSnapshotAsync() {
        Task { await refreshSnapshot() }
    }

    // MARK: CRUD
    // All async (ChatStore is an actor) — mirror Android's suspend functions.

    /// Create a group. `members` order = round-robin speaking order (the UI
    /// passes the checkbox order). Host is stored as given — speaking-last is
    /// applied at schedule time, never by reordering.
    @discardableResult
    static func createGroup(
        name: String,
        members: [AgentGroupMember],
        hostRoleId: String? = nil,
        instruction: String? = nil,
        modelOverride: AgentGroupModelRef? = nil,
    ) async -> AgentGroupRecord {
        let now = Date()
        let group = AgentGroupRecord(
            id: UUID().uuidString,
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            instruction: instruction.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .flatMap { $0.isEmpty ? nil : $0 },
            membersJson: membersJson(from: members),
            hostRoleId: hostRoleId,
            modelOverrideJson: modelOverride.map { modelOverrideJson(from: $0) },
            createdAt: now,
            updatedAt: now,
        )
        await ChatStore.shared.upsertAgentGroup(group)
        await ChatStore.shared.markDirty(recordType: "AgentGroupV2", recordId: group.id)
        await refreshSnapshot()
        NotificationCenter.default.post(name: groupChanged, object: group.id)
        return group
    }

    /// Full-row update (rename / instruction / roster order / participation
    /// toggles / host / unified-model override). Bumps updatedAt (LWW stamp).
    static func updateGroup(_ group: AgentGroupRecord) async {
        var updated = group
        updated.updatedAt = Date()
        await ChatStore.shared.upsertAgentGroup(updated)
        await ChatStore.shared.markDirty(recordType: "AgentGroupV2", recordId: updated.id)
        await refreshSnapshot()
        NotificationCenter.default.post(name: groupChanged, object: group.id)
    }

    /// Delete a group AND all its sessions (design doc D6 — UI must confirm).
    ///
    /// - Returns: the deleted session ids, so the UI can navigate away when
    ///   the currently-open chat was among them.
    @discardableResult
    static func deleteGroup(_ groupId: String) async -> [String] {
        let deleted = await ChatStore.shared.deleteAgentGroup(groupId)
        await ChatStore.shared.markDirty(recordType: "AgentGroupV2", recordId: groupId, operation: "delete")
        await refreshSnapshot()
        NotificationCenter.default.post(name: groupChanged, object: groupId)
        return deleted
    }

    // MARK: resolution

    /// Resolve the roster against the live RoleStore, keeping array order.
    /// Unresolvable members are dropped silently here — the UI surfaces them
    /// as grey "已删除" rows via [unresolvedMemberIds] and the chat scheduler
    /// inserts a systemInfo line per dropped member (§3.3). NOT written back:
    /// the stored roster keeps the stale id until the user cleans it up, so a
    /// temporary snapshot race can never permanently shrink a group.
    static func resolveMembers(_ group: AgentGroupRecord) -> [ResolvedGroupMember] {
        var out: [ResolvedGroupMember] = []
        for member in group.members {
            guard let role = RoleStore.roleById(member.roleId) ?? RoleStore.load(roleId: member.roleId) else {
                continue
            }
            out.append(ResolvedGroupMember(role: role, participatesInRound: member.participatesInRound))
        }
        return out
    }

    /// Roster ids that no longer resolve — the grey rows in the group settings screen.
    static func unresolvedMemberIds(_ group: AgentGroupRecord) -> [String] {
        let known = Set(RoleStore.rolesSnapshot.map(\.roleId))
        return group.members.map(\.roleId).filter { !known.contains($0) }
    }

    // MARK: JSON (de)serialization
    // Defensively tolerant: a malformed members_json degrades to an empty
    // roster (rendered as an empty group the user can edit), never a crash.

    static func membersJson(from members: [AgentGroupMember]) -> String {
        let items = members.map { m -> String in
            let p = m.participatesInRound ? "true" : "false"
            return "{\"roleId\":\(jsonEscape(m.roleId)),\"participatesInRound\":\(p)}"
        }
        return "[" + items.joined(separator: ",") + "]"
    }

    static func members(from json: String) -> [AgentGroupMember] {
        guard let data = json.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return arr.compactMap { dict in
            guard let roleId = dict["roleId"] as? String, !roleId.isEmpty else { return nil }
            let participates = dict["participatesInRound"] as? Bool ?? true
            return AgentGroupMember(roleId: roleId, participatesInRound: participates)
        }
    }

    static func modelOverrideJson(from ref: AgentGroupModelRef) -> String {
        "{\"modelId\":\(jsonEscape(ref.modelId)),\"providerInstanceId\":\(jsonEscape(ref.providerInstanceId))}"
    }

    static func modelOverride(from json: String?) -> AgentGroupModelRef? {
        guard let json, let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let modelId = dict["modelId"] as? String, !modelId.isEmpty,
              let instanceId = dict["providerInstanceId"] as? String, !instanceId.isEmpty else {
            return nil
        }
        return AgentGroupModelRef(modelId: modelId, providerInstanceId: instanceId)
    }

    private static func jsonEscape(_ s: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [s], options: [.fragmentsAllowed]) else {
            return "\"\""
        }
        // JSONSerialization of a single-element array → "[\"…\"]"; strip the brackets.
        var out = String(data: data, encoding: .utf8) ?? "\"\""
        if out.hasPrefix("[") && out.hasSuffix("]") {
            out = String(out.dropFirst().dropLast())
        }
        return out
    }
}
