import io
P=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\ios\Views\Chat\SessionMemoryView.swift"
s=io.open(P,encoding="utf-8").read()
old = """        let soulURL = memDir.appendingPathComponent("SOUL.md")
        if fm.fileExists(atPath: soulURL.path),
           let content = try? String(contentsOf: soulURL, encoding: .utf8),
           !content.isEmpty {
            let lineCount = content.components(separatedBy: "\\n").count
            items.append(AutoItem(
                name: "SOUL.md",
                detail: "\\(lineCount) lines (full)",
                icon: "person.fill",
                content: content,
                fileURL: soulURL
            ))
        } else {
            items.append(AutoItem(name: "SOUL.md", detail: "Empty", icon: "person", content: "(empty)", fileURL: soulURL))
        }"""
new = """        // [T-role-soul-sheet] SOUL.md is the session role persona body, sourced
        // from RoleStore (roles/<roleId>/role.json body), NOT the legacy global
        // SOUL.md. identitySection() reads the same body, so this shows the
        // exact persona the model sees. Read-only here; edit the persona via
        // Role settings (RoleStore.saveRole).
        let soulContent = RoleStore.resolveRole(vm.sessionRole.roleId)?.body ?? ""
        if !soulContent.isEmpty {
            let lineCount = soulContent.components(separatedBy: "\\n").count
            items.append(AutoItem(
                name: "SOUL.md",
                detail: "\\(lineCount) lines (full)",
                icon: "person.fill",
                content: soulContent,
                fileURL: nil
            ))
        } else {
            items.append(AutoItem(name: "SOUL.md", detail: "Empty", icon: "person", content: "(empty)", fileURL: nil))
        }"""
c=s.count(old); assert c==1, f"{c}"
s=s.replace(old,new)
io.open(P,"w",encoding="utf-8",newline="\n").write(s)
print("IOS SOUL DONE")
