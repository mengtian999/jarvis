import io
p=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\java\com\jarvis\app\ui\chat\ChatScreen.kt"
s=io.open(p,encoding="utf-8").read()
APOS=chr(39)
ARROW=chr(8594)
old=(
"                            // [T-role-forward] Enter message multi-select "+ARROW+"\n"
"                            // forward to another role"+APOS+"s session. Disabled\n"
"                            // while streaming (rows keep mutating).\n"
"                            DropdownMenuItem(\n"
"                                text = { Text(stringResource(R.string.forward_to_role)) },\n"
"                                enabled = !isStreaming,\n"
"                                onClick = {\n"
"                                    showChatMenu = false\n"
"                                    forwardSelecting = true\n"
"                                    forwardSelectionIds = emptySet()\n"
"                                },\n"
"                                leadingIcon = {\n"
"                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)\n"
"                                },\n"
"                            )\n"
)
new=(
"                            // [T-role-forward] Forward to another role"+APOS+"s session.\n"
"                            // No selection yet -> toast + enter select mode; selection\n"
"                            // present -> open the forward sheet (role/session/autoRespond).\n"
"                            val fwdCtx = androidx.compose.ui.platform.LocalContext.current\n"
"                            val fwdEmptyMsg = stringResource(R.string.forward_select_at_least_one)\n"
"                            DropdownMenuItem(\n"
"                                text = { Text(stringResource(R.string.forward_to_role)) },\n"
"                                enabled = !isStreaming,\n"
"                                onClick = {\n"
"                                    showChatMenu = false\n"
"                                    if (forwardSelectionIds.isEmpty()) {\n"
"                                        android.widget.Toast.makeText(\n"
"                                            fwdCtx,\n"
"                                            fwdEmptyMsg,\n"
"                                            android.widget.Toast.LENGTH_SHORT,\n"
"                                        ).show()\n"
"                                        forwardSelecting = true\n"
"                                        forwardSelectionIds = emptySet()\n"
"                                    } else {\n"
"                                        showForwardSheet = true\n"
"                                    }\n"
"                                },\n"
"                                leadingIcon = {\n"
"                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)\n"
"                                },\n"
"                            )\n"
)
c=s.count(old); assert c==1, f"forward-item:{c}"
s=s.replace(old,new)
io.open(p,"w",encoding="utf-8",newline="\n").write(s)
print("FORWARD GATE DONE")
