import io
p=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\java\com\jarvis\app\ui\chat\ChatScreen.kt"
s=io.open(p,encoding="utf-8").read()
def rpl(old,new,n):
    global s
    c=s.count(old); assert c==1, f"{n}:{c}"
    s=s.replace(old,new)
rpl("    var showRoleEntrySheet by remember { mutableStateOf(false) }",
"""    var showRoleEntrySheet by remember { mutableStateOf(false) }
    var showRolePickerSheet by remember { mutableStateOf(false) }""","state")
rpl(".clickable { showRoleEntrySheet = true },",
".clickable { showRolePickerSheet = true },","avatar-click")
rpl("""                                    } else {
                                        onNewChat()
                                    }""",
"""                                    } else {
                                        showRolePickerSheet = true
                                    }""","newchat-menu")
rpl("""                        showNewChatStopDialog = false
                        viewModel.cancelStream()
                        onNewChat()""",
"""                        showNewChatStopDialog = false
                        viewModel.cancelStream()
                        showRolePickerSheet = true""","stop-confirm")
old5 = (
"            if (showRoleEntrySheet) {\n"
"                MinisAlertDialog(\n"
"                    onDismissRequest = { showRoleEntrySheet = false },\n"
"                    title = stringResource(R.string.role_entry_title),\n"
"                    text = sessionRole.metadata.name,\n"
"                    confirmText = stringResource(R.string.role_entry_new_session),\n"
"                    onConfirm = {\n"
"                        showRoleEntrySheet = false\n"
"                        onNewChatWithRole(sessionRole.roleId)\n"
"                    },\n"
"                    neutralText = stringResource(R.string.role_entry_view_history),\n"
"                    onNeutral = {\n"
"                        showRoleEntrySheet = false\n"
"                        // Park the filter request on the list VM" + chr(39) + "s bus and\n"
"                        // pop back " + chr(8212) + " the list screen consumes it on compose.\n"
"                        com.jarvis.app.ui.sessions.SessionListViewModel\n"
"                            .pendingRoleFilter.value = sessionRole.roleId\n"
"                        onBack()\n"
"                    },\n"
"                    dismissText = stringResource(R.string.cancel),\n"
"                    onDismiss = { showRoleEntrySheet = false },\n"
"                )\n"
"            }\n"
)
new5 = (
"            if (showRolePickerSheet) {\n"
"                RolePickerSheet(\n"
"                    currentRoleId = sessionRole.roleId,\n"
"                    onNewChatWithRole = { roleId ->\n"
"                        showRolePickerSheet = false\n"
"                        onNewChatWithRole(roleId)\n"
"                    },\n"
"                    onViewRoleHistory = { roleId ->\n"
"                        showRolePickerSheet = false\n"
"                        com.jarvis.app.ui.sessions.SessionListViewModel\n"
"                            .pendingRoleFilter.value = roleId\n"
"                        onBack()\n"
"                    },\n"
"                    onDismiss = { showRolePickerSheet = false },\n"
"                )\n"
"            }\n"
)
rpl(old5,new5,"entry-sheet")
io.open(p,"w",encoding="utf-8",newline="\n").write(s)
print("WIRE DONE")
