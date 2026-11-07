import SwiftUI

/// [T-group-chat] 发起群聊 sheet — one surface for the whole group lifecycle.
/// SwiftUI mirror of Android `AgentGroupSheet.kt`:
///
///  - LIST mode: existing groups (tap → new chat with that group) + "新建群".
///  - FORM mode (create or edit): name / instruction / member roster (tap
///    order = round-robin speaking order) / moderator / unified-model override
///    (D4: default OFF) / delete (edit only, confirm dialog, D6).
///
/// Confirm in create mode navigates straight into the new group chat via
/// [onOpenGroupChat]. All writes go through GroupStore (async — ChatStore is
/// an actor); the sheet drives them from Task {}
struct AgentGroupSheet: View {
    /// Called with the group id to open a new group chat (create confirm, or
    /// tapping an existing group in list mode).
    let onOpenGroupChat: (String) -> Void
    let onDismiss: () -> Void

    @State private var editing: AgentGroupRecord?
    @State private var showDeleteConfirm = false

    var body: some View {
        NavigationStack {
            Group {
                if let group = editing {
                    AgentGroupEditForm(
                        group: group,
                        onCancel: { editing = nil },
                        onSave: { name, instruction, members, hostRoleId, modelOverride in
                            let target = group
                            Task {
                                if target.id.isEmpty {
                                    let created = await GroupStore.createGroup(
                                        name: name,
                                        members: members,
                                        hostRoleId: hostRoleId,
                                        instruction: instruction,
                                        modelOverride: modelOverride,
                                    )
                                    editing = nil
                                    onDismiss()
                                    onOpenGroupChat(created.id)
                                } else {
                                    var updated = target
                                    updated.name = name
                                    updated.instruction = instruction
                                    updated.membersJson = GroupStore.membersJson(from: members)
                                    updated.hostRoleId = hostRoleId
                                    updated.modelOverrideJson = modelOverride.map { GroupStore.modelOverrideJson(from: $0) }
                                    await GroupStore.updateGroup(updated)
                                    editing = nil
                                }
                            }
                        },
                        onDelete: group.id.isEmpty ? nil : { showDeleteConfirm = true },
                    )
                } else {
                    AgentGroupListPicker(
                        onOpenGroupChat: { gid in
                            onDismiss()
                            onOpenGroupChat(gid)
                        },
                        onEdit: { editing = $0 },
                        onCreate: {
                            editing = AgentGroupRecord(
                                id: "", name: "", instruction: nil,
                                membersJson: "[]", hostRoleId: nil,
                                modelOverrideJson: nil, createdAt: Date(), updatedAt: Date(),
                            )
                        },
                    )
                }
            }
            .navigationTitle(editing?.id.isEmpty == false
                ? AppLocalized("Group Settings")
                : AppLocalized("Group Chats"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Done")) { onDismiss() }
                }
            }
            .alert(AppLocalized("Delete Group"), isPresented: $showDeleteConfirm) {
                Button(AppLocalized("Delete"), role: .destructive) {
                    guard let id = editing?.id, !id.isEmpty else { return }
                    Task {
                        _ = await GroupStore.deleteGroup(id)
                        editing = nil
                    }
                }
                Button(AppLocalized("Cancel"), role: .cancel) {}
            } message: {
                Text(AppLocalized("Delete this group and all its chats? This cannot be undone."))
            }
        }
    }
}

// MARK: - List mode

private struct AgentGroupListPicker: View {
    let onOpenGroupChat: (String) -> Void
    let onEdit: (AgentGroupRecord) -> Void
    let onCreate: () -> Void

    var body: some View {
        List {
            Button {
                onCreate()
            } label: {
                Label(AppLocalized("New Group"), systemImage: "plus.circle.fill")
            }
            .tint(.accentColor)

            ForEach(GroupStore.snapshot) { group in
                Button {
                    onOpenGroupChat(group.id)
                } label: {
                    HStack(spacing: 10) {
                        AgentGroupAvatarStack(roleIds: group.members.map(\.roleId))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(group.name)
                                .font(.body)
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                            Text(String(format: AppLocalized("%d members"), group.members.count))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button {
                            onEdit(group)
                        } label: {
                            Image(systemName: "gearshape")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}

/// Overlapping avatar stack for a roster of roleIds (mirrors Android
/// GroupAvatarStack). Sized for list rows.
struct AgentGroupAvatarStack: View {
    let roleIds: [String]
    var avatarSize: CGFloat = 24

    var body: some View {
        let shown = Array(roleIds.prefix(3))
        HStack(spacing: -6) {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, roleId in
                RoleAvatar(roleId: roleId, size: avatarSize)
            }
            if roleIds.count > 3 {
                Text("+\(roleIds.count - 3)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// Single role avatar by id (snapshot lookup → default fallback), mirroring
/// Android RoleStore.cachedAvatarBitmap render paths.
struct RoleAvatar: View {
    let roleId: String
    var size: CGFloat = 24

    var body: some View {
        let role = RoleStore.roleById(roleId)
        let name = role?.metadata.name.isEmpty == false ? role!.metadata.name : "Agent"
        if let data = role?.avatarData, let ui = UIImage(data: data) {
            Image(uiImage: ui)
                .resizable()
                .scaledToFill()
                .frame(width: size, height: size)
                .clipShape(Circle())
        } else {
            ZStack {
                Circle().fill(Color.accentColor.opacity(0.18))
                Text(String(name.prefix(1)))
                    .font(.system(size: size * 0.5, weight: .medium))
                    .foregroundStyle(.secondary)
            }
            .frame(width: size, height: size)
        }
    }
}

// MARK: - Form mode

private struct AgentGroupEditForm: View {
    let group: AgentGroupRecord
    let onCancel: () -> Unit
    let onSave: (String, String?, [AgentGroupMember], String?, AgentGroupModelRef?) -> Void
    let onDelete: (() -> Unit)?

    @State private var name: String
    @State private var instruction: String
    @State private var members: [AgentGroupMember]
    @State private var hostRoleId: String?
    @State private var modelOverride: AgentGroupModelRef?
    @State private var showModelPicker = false

    private var isCreate: Bool { group.id.isEmpty }

    init(group: AgentGroupRecord, onCancel: @escaping () -> Unit, onSave: @escaping (String, String?, [AgentGroupMember], String?, AgentGroupModelRef?) -> Void, onDelete: (() -> Unit)?) {
        self.group = group
        self.onCancel = onCancel
        self.onSave = onSave
        self.onDelete = onDelete
        _name = State(initialValue: group.name)
        _instruction = State(initialValue: group.instruction ?? "")
        _members = State(initialValue: group.members)
        _hostRoleId = State(initialValue: group.hostRoleId)
        _modelOverride = State(initialValue: group.modelOverride)
    }

    var body: some View {
        Form {
            Section {
                TextField(AppLocalized("Group name"), text: $name, prompt: Text(AppLocalized("e.g. Review panel, Project team")))
                TextField(AppLocalized("Group instruction (optional)"), text: $instruction, prompt: Text(AppLocalized("Shared context every member sees")), axis: .vertical)
                    .lineLimit(2...4)
            }

            // Roster — array order IS the round-robin speaking order.
            Section {
                ForEach(Array(members.enumerated()), id: \.element.roleId) { idx, member in
                    MemberRow(
                        member: member,
                        isHost: hostRoleId == member.roleId,
                        onToggleHost: {
                            hostRoleId = (hostRoleId == member.roleId) ? nil : member.roleId
                        },
                        onMoveUp: idx > 0 ? {
                            members.swapAt(idx - 1, idx)
                        } : nil,
                        onMoveDown: idx < members.count - 1 ? {
                            members.swapAt(idx, idx + 1)
                        } : nil,
                        onRemove: {
                            members.removeAll { $0.roleId == member.roleId }
                            if hostRoleId == member.roleId { hostRoleId = nil }
                        },
                    )
                }
            } header: {
                Text(AppLocalized("Members (tap to add, in speaking order)"))
            } footer: {
                // [T-group-chat][P3-ui] 参与 @全体 开关从行内移除（用户反馈多余；
                // AgentGroupMember.participatesInRound 字段保留，默认 true）。
                // 说明行让人看懂 ★ 与 ↑↓ 各自干什么。
                if members.isEmpty {
                    Text(AppLocalized("Select at least 2 roles"))
                } else {
                    Text(AppLocalized("★ = moderator (speaks last). ↑↓ change speaking order. Tap a member's ★ to set or clear."))
                }
            }

            // Add-member pool: every role not yet in the roster.
            Section {
                ForEach(RoleStore.rolesSnapshot.filter { role in
                    !members.contains(where: { $0.roleId == role.roleId })
                }) { role in
                    Button {
                        members.append(AgentGroupMember(roleId: role.roleId))
                    } label: {
                        Label(
                            role.metadata.name.isEmpty ? "Jarvis" : role.metadata.name,
                            systemImage: "plus.circle",
                        )
                    }
                }
            }

            // [T-group-chat-model-override] Unified model — default OFF (D4).
            Section {
                Toggle(isOn: Binding(
                    get: { modelOverride != nil },
                    set: { on in
                        modelOverride = on
                            ? ProviderConfigStore.shared.resolvedAgentLoopEntries.first.map {
                                AgentGroupModelRef(modelId: $0.baseModel.id, providerInstanceId: $0.providerInstanceId)
                            }
                            : nil
                    },
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(AppLocalized("Unified Model"))
                        Text(modelOverride == nil
                            ? AppLocalized("Off — members use their own models")
                            : (modelOverride?.modelId ?? ""))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                if modelOverride != nil {
                    Button {
                        showModelPicker = true
                    } label: {
                        HStack {
                            Text(AppLocalized("Choose Model"))
                            Spacer()
                            Text(currentModelLabel)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
            }

            if !isCreate, let onDelete {
                Section {
                    Button(role: .destructive) { onDelete() } label: {
                        Label(AppLocalized("Delete Group"), systemImage: "trash")
                    }
                }
            }

            Section {
                Button {
                    onSave(
                        name.trimmingCharacters(in: .whitespacesAndNewlines),
                        instruction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : instruction,
                        members,
                        hostRoleId,
                        modelOverride,
                    )
                } label: {
                    Text(AppLocalized(isCreate ? "Create Group" : "Save"))
                        .frame(maxWidth: .infinity)
                }
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || members.isEmpty)
            }
        }
        .sheet(isPresented: $showModelPicker) {
            GroupModelPicker { ref in
                modelOverride = ref
                showModelPicker = false
            }
        }
    }

    private var currentModelLabel: String {
        guard let ref = modelOverride else { return "" }
        let store = ProviderConfigStore.shared
        return store.visibleEntries(for: ref.providerInstanceId)
            .first { $0.baseModel.id == ref.modelId }?.model.displayName ?? ref.modelId
    }
}

private struct MemberRow: View {
    let member: AgentGroupMember
    let isHost: Bool
    let onToggleHost: () -> Unit
    let onMoveUp: (() -> Unit)?
    let onMoveDown: (() -> Unit)?
    let onRemove: () -> Unit

    var body: some View {
        let role = RoleStore.roleById(member.roleId)
        HStack(spacing: 8) {
            RoleAvatar(roleId: member.roleId, size: 28)
            Text(role?.metadata.name.isEmpty == false
                ? role!.metadata.name
                : (role == nil ? AppLocalized("Deleted Role") : "Jarvis"))
                .font(.body)
                .lineLimit(1)
            Spacer()
            // Moderator toggle (D3) — host always speaks last. ★ lit/unlit.
            Button(action: onToggleHost) {
                Image(systemName: isHost ? "star.fill" : "star")
                    .foregroundStyle(isHost ? Color.yellow : Color.secondary)
            }
            .buttonStyle(.plain)
            if let onMoveUp {
                Button(action: onMoveUp) { Image(systemName: "arrow.up") }
                    .buttonStyle(.plain)
            }
            if let onMoveDown {
                Button(action: onMoveDown) { Image(systemName: "arrow.down") }
                    .buttonStyle(.plain)
            }
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
    }
}

/// Model picker for the unified-model override: all visible entries across
/// instances (a bare model id is ambiguous across instances — see
/// AgentGroupModelRef).
private struct GroupModelPicker: View {
    let onPick: (AgentGroupModelRef) -> Void

    var body: some View {
        NavigationStack {
            List(ProviderConfigStore.shared.resolvedAgentLoopEntries) { entry in
                Button {
                    onPick(AgentGroupModelRef(modelId: entry.baseModel.id, providerInstanceId: entry.providerInstanceId))
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.model.displayName)
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Text(entry.baseModel.id)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }
            .navigationTitle(AppLocalized("Choose Model"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
