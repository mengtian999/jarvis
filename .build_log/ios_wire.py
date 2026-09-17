import io
P=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\ios\Views\Chat\AIChatView.swift"
s=io.open(P,encoding="utf-8").read()
def rpl(old,new,n):
    global s
    c=s.count(old); assert c==1, f"{n}:{c}"
    s=s.replace(old,new)
# 1) confirmationDialog -> RolePickerSheet sheet
rpl("""        .confirmationDialog(
            String(localized: "Session role: \\(vm.sessionRole.metadata.name)"),
            isPresented: $showRoleEntrySheet,
            titleVisibility: .visible
        ) {
            Button(String(localized: "New chat with this role")) {
                RoleNavigationBus.shared.newChatWithRole = vm.sessionRole.roleId
            }
            Button(String(localized: "View this role's chats")) {
                RoleNavigationBus.shared.showRoleHistory = vm.sessionRole.roleId
            }
            Button(String(localized: "Cancel"), role: .cancel) {}
        }""",
"""        .sheet(isPresented: $showRoleEntrySheet) {
            RolePickerSheet(vm: vm)
        }""","confdialog")
# 2) requestNewChatFromMenu else -> role picker
rpl("""        } else {
            NotificationCenter.default.post(name: .newChatRequested, object: nil)
        }""",
"""        } else {
            showRoleEntrySheet = true
        }""","newchat-menu")
# 3) stop-confirm confirm -> role picker
rpl("""                vm.cancel()
                NotificationCenter.default.post(name: .newChatRequested, object: nil)""",
"""                vm.cancel()
                showRoleEntrySheet = true""","stop-confirm")
io.open(P,"w",encoding="utf-8",newline="\n").write(s)
print("IOS WIRE DONE")
