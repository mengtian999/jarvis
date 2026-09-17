import io
p=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\java\com\jarvis\app\ui\chat\SessionMemorySheet.kt"
s=io.open(p,encoding="utf-8").read()
def rpl(old,new,n):
    global s
    c=s.count(old); assert c==1, f"{n}:{c}"
    s=s.replace(old,new)
rpl("""    // SOUL.md — persona / identity. Lives in the same memory dir as
    // GLOBAL.md and is auto-injected into the system prompt by
    // SystemPromptBuilder.identitySection(). Surfaced here so the user
    // can see + edit the same file the model sees, mirroring GLOBAL.md.
    val soulContent = memoryRepository.readFile("SOUL.md")""",
"""    // [T-role-soul-sheet] SOUL.md — the session role's persona body, sourced
    // from RoleStore (roles/<roleId>/role.json body), NOT the legacy global
    // memoryDir/SOUL.md. identitySection() reads the same role.json body, so
    // this surfaces + edits the exact persona the model sees. Previously
    // reading the global SOUL.md always showed Jarvis for non-default roles.
    val soulRole = com.jarvis.app.agent.RoleStore.resolveRole(roleId)
    val soulContent = soulRole?.body ?: """"","soul-display")
rpl("""                            memoryRepository.saveFile(m.name, editedContent, roleId)
                            // SOUL.md drives [SoulStore.cachedMetadata] which
                            // backs the chat-bubble header name. The raw
                            // saveFile() path here bypasses SoulStore.save(),
                            // so refresh the cache manually to keep readers
                            // in sync after an in-sheet edit.
                            if (m.name == "SOUL.md") {
                                com.jarvis.app.agent.SoulStore.refreshCache(context)
                            }""",
"""                            if (m.name == "SOUL.md") {
                                // [T-role-soul-sheet] Persist persona body back
                                // to the role (roles/<roleId>/role.json), not
                                // the legacy global SOUL.md file. saveRole
                                // refreshes caches + posts .rolesChanged so the
                                // chat header / list re-render with the new body.
                                val cur = com.jarvis.app.agent.RoleStore.resolveRole(roleId)
                                if (cur != null) {
                                    com.jarvis.app.agent.RoleStore.saveRole(
                                        context, cur.copy(body = editedContent),
                                    )
                                }
                            } else {
                                memoryRepository.saveFile(m.name, editedContent, roleId)
                            }""","soul-save")
io.open(p,"w",encoding="utf-8",newline="\n").write(s)
print("SOUL FIX DONE")
