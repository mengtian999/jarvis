import SwiftUI

/// [T-role-picker] Full role-list sheet for the in-chat "jump to another
/// role" and "new chat with a role" flows. Replaces the current-role-only
/// confirmationDialog. Picking a role opens a NEW session bound to it
/// (immutable in-session) via RoleNavigationBus, or filters the session list
/// to that role history. Mirrors Android RolePickerSheet.kt.
struct RolePickerSheet: View {
    @ObservedObject var vm: AIChatViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var roles: [RoleFile] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(roles, id: \.roleId) { role in
                    Section {
                        Button {
                            RoleNavigationBus.shared.newChatWithRole = role.roleId
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                RoleAvatarImage(roleId: role.roleId, size: 36)
                                HStack(spacing: 6) {
                                    Text(role.metadata.name)
                                        .foregroundStyle(.primary)
                                    if role.roleId == vm.sessionRole.roleId {
                                        Text(String(localized: "Current"))
                                            .font(.caption)
                                            .foregroundStyle(.tint)
                                    }
                                }
                                Spacer()
                                Image(systemName: "square.and.pencil")
                                    .foregroundStyle(.secondary)
                                Text(String(localized: "New chat"))
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Button {
                            RoleNavigationBus.shared.showRoleHistory = role.roleId
                            dismiss()
                        } label: {
                            HStack {
                                Image(systemName: "clock.arrow.circlepath")
                                    .foregroundStyle(.secondary)
                                Text(String(localized: "View history"))
                                Spacer()
                            }
                        }
                    }
                }
            }
            .navigationTitle(String(localized: "Session role"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "Cancel")) { dismiss() }
                }
            }
        }
        .onAppear { roles = RoleStore.rolesSnapshot }
    }
}
