import SwiftUI

/// [T-role-forward] Two-step forward picker: choose the target role, then the
/// destination (new chat / one of that role's sessions) plus the auto-respond
/// toggle. On confirm, the source message is flattened into a user row in the
/// target session (copy semantics — the source is untouched) and, when
/// enabled, a PendingForwardRespond marker makes the target chat auto-start
/// its agent loop when opened.
///
/// MVP is single-message (the long-press "Forward to Role" entry). Android
/// carries the full multi-select implementation.
struct ForwardToRoleSheet: View {
    @ObservedObject var vm: AIChatViewModel
    let sourceMessageId: UUID?
    @Binding var pickRoleId: String?
    @Binding var targetSessionId: String?
    @Binding var autoRespond: Bool
    @Environment(\.dismiss) private var dismiss

    /// Role list snapshot — @State so edits elsewhere re-render on next open.
    @State private var roles: [RoleFile] = []
    /// Sessions bound to the chosen role (loaded when the role is picked).
    @State private var roleSessions: [ChatSession] = []
    @State private var forwarding = false

    private var chosenRole: RoleFile? {
        pickRoleId.flatMap { rid in roles.first { $0.roleId == rid } }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let chosenRole {
                    destinationStep(chosenRole)
                } else {
                    roleStep
                }
            }
            .navigationTitle(chosenRole == nil
                ? String(localized: "Choose a role")
                : String(localized: "Choose destination"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Cancel")) { dismiss() }
                }
                if chosenRole != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(String(localized: "Forward")) { await forward() }
                            .disabled(forwarding)
                    }
                }
            }
        }
        .onAppear {
            roles = RoleStore.rolesSnapshot
        }
    }

    // MARK: Step 1 — role list

    private var roleStep: some View {
        List {
            ForEach(roles, id: \.roleId) { role in
                Button {
                    pickRoleId = role.roleId
                    targetSessionId = nil
                    loadRoleSessions(role.roleId)
                } label: {
                    HStack(spacing: 12) {
                        RoleAvatarImage(roleId: role.roleId, size: 32)
                        Text(role.metadata.name)
                            .foregroundStyle(.primary)
                        if role.roleId == vm.sessionRole.roleId {
                            Text(String(localized: "This chat"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                }
            }
        }
    }

    // MARK: Step 2 — destination + auto-respond

    private func destinationStep(_ role: RoleFile) -> some View {
        List {
            Section {
                Button {
                    targetSessionId = nil
                } label: {
                    HStack {
                        Image(systemName: "square.and.pencil")
                        Text(String(localized: "New chat"))
                        Spacer()
                        if targetSessionId == nil {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                        }
                    }
                }
                ForEach(roleSessions, id: \.id) { session in
                    Button {
                        targetSessionId = session.id
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(session.title ?? String(localized: "New Chat"))
                                    .lineLimit(1)
                                    .foregroundStyle(.primary)
                                if let preview = session.lastMessage {
                                    Text(preview)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }
                            Spacer()
                            if targetSessionId == session.id {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                        }
                    }
                }
            }
            Section {
                Toggle(isOn: $autoRespond) {
                    Text(String(localized: "Let \(role.metadata.name) reply immediately"))
                }
            } footer: {
                Text(String(localized: "The forwarded message is copied into the target chat as material from you; this chat is not modified."))
            }
        }
    }

    private func loadRoleSessions(_ roleId: String) {
        Task { @MainActor in
            let all = await ChatStore.shared.listSessions()
            roleSessions = all
                .filter { $0.roleId == roleId }
                .sorted { $0.updatedAt > $1.updatedAt }
                .prefix(20)
                .map { $0 }
        }
    }

    private func forward() async {
        guard let chosenRole,
              let msg = vm.messages.first(where: { $0.id == sourceMessageId }) else {
            dismiss()
            return
        }
        forwarding = true
        let targetId = await vm.forwardChatMessageToRole(
            msg,
            targetRoleId: chosenRole.roleId,
            targetSessionId: targetSessionId,
            autoRespond: autoRespond
        )
        forwarding = false
        dismiss()
        guard let targetId else { return }
        // Navigate to the target chat; its AIChatView drains the
        // auto-respond marker (when stashed) on appear.
        Task { @MainActor in
            NotificationCenter.default.post(
                name: .forwardDidComplete, object: nil,
                userInfo: ["targetSessionId": targetId]
            )
        }
    }
}

/// [T-role-forward] AIChatView listens for this and navigates to the forward
/// target using the same atomic path-replacement as move-to (it owns the
/// NavigationStack; the sheet cannot reach it).
extension Notification.Name {
    static let forwardDidComplete = Notification.Name("MinisForwardDidComplete")
}
