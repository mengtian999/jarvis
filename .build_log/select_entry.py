import io
U=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\java\com\jarvis\app\ui\chat\ChatUserMessageUI.kt"
C=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\java\com\jarvis\app\ui\chat\ChatScreen.kt"
V=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values\strings.xml"
Z=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\res\values-zh\strings.xml"
def rpl(path,old,new,n):
    s=io.open(path,encoding="utf-8").read()
    c=s.count(old); assert c==1, f"{n}:{c}"
    s=s.replace(old,new)
    io.open(path,"w",encoding="utf-8",newline="\n").write(s)
    print("OK",n)
rpl(U,
"    onWithdraw: (() -> Unit)? = null,\n    onPreviewFile: (Uri, String) -> Unit = { _, _ -> },\n) {",
"    onWithdraw: (() -> Unit)? = null,\n    onPreviewFile: (Uri, String) -> Unit = { _, _ -> },\n    onSelect: (() -> Unit)? = null,\n) {","param")
rpl(U,
"""            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_longpress_copy)) },""",
"""            ) {
                if (onSelect != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_longpress_select)) },
                        onClick = { showMenu = false; onSelect() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_longpress_copy)) },""","menu-item")
rpl(C,
"""                                },
                            )
                            } // close UserBubble SideEffect + UserMessageBubble block""",
"""                                },
                                onSelect = if (isStreaming) null else {
                                    { forwardSelecting = true; forwardSelectionIds = emptySet() }
                                },
                            )
                            } // close UserBubble SideEffect + UserMessageBubble block""","callsite")
rpl(V,
"    <string name=\"forward_select_at_least_one\">Select at least one message to forward</string>\n",
"    <string name=\"forward_select_at_least_one\">Select at least one message to forward</string>\n    <string name=\"chat_longpress_select\">Select</string>\n","str-en")
rpl(Z,
"    <string name=\"forward_select_at_least_one\">请至少选择一条消息进行转发</string>\n",
"    <string name=\"forward_select_at_least_one\">请至少选择一条消息进行转发</string>\n    <string name=\"chat_longpress_select\">选择</string>\n","str-zh")
print("SELECT ENTRY DONE")
