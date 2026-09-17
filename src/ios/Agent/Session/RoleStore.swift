// +--------------------------------------------------------------+
// | RoleStore.swift - 角色集合管理器 (Multi-Role System)       |
// +--------------------------------------------------------------+

import Foundation
import SwiftUI

// MARK: - Role Collection 管理

/// 角色集合管理器
/// 管理所有角色：默认角色(禁止删除) + 用户自定义角色(可编辑/可删除)
enum RoleStore {
    
    /// 角色ID常量
    static let defaultRoleId = "default"
    
    /// 获取角色目录路径
    static var rolesDir: URL {
        AIChatViewModel.minisMemoryPersistentDir.appendingPathComponent("roles")
    }
    
    /// 获取当前激活的角色ID
    static var currentRoleId: String {
        get {
            let url = rolesDir.appendingPathComponent("current.json")
            guard let data = try? Data(contentsOf: url),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                  let id = json["roleId"] else {
                return `defaultRoleId`
            }
            return id
        }
        set {
            let fm = FileManager.default
            try? fm.createDirectory(at: rolesDir, withIntermediateDirectories: true)
            let url = rolesDir.appendingPathComponent("current.json")
            let json = ["roleId": newValue]
            guard let data = try? JSONSerialization.data(withJSONObject: json) else { return }
            try? data.write(to: url, options: .atomic)
        }
    }
    
    /// 获取当前激活的角色名称
    static var currentRoleName: String {
        let role = currentRole()
        return role.metadata.name
    }
    
    /// 获取所有角色列表
    static func allRoles() -> [RoleFile] {
        var roles: [RoleFile] = []
        let fm = FileManager.default

        // 确保目录存在
        try? fm.createDirectory(at: rolesDir, withIntermediateDirectories: true)

        // 角色直接存放在 roles/<roleId>/ 下（default 角色也在这里），与
        // save/load/deleteRole 使用的路径一致。旧实现只遍历 roles/custom/，
        // 导致默认角色和所有早期创建的角色都遍历不到（角色切换弹窗最多只能
        // 看到 custom/ 下建过壳目录的那几个角色）。
        let subdirs = ((try? fm.contentsOfDirectory(at: rolesDir,
                                                     includingPropertiesForKeys: nil,
                                                     skipHiddenFiles: true))
            ?? []).filter { $0.hasDirectoryPath }
        for roleDir in subdirs {
            if let roleFile = load(roleId: roleDir.lastPathComponent) {
                roles.append(roleFile)
            }
        }

        // 按名称排序
        return roles.sorted { $0.metadata.name.localizedCaseInsensitiveCompare($1.metadata.name) == .orderedAscending }
    }

    // MARK: - [T-role-snapshot] In-memory collection snapshot
    //
    // List rendering (session rows, forward pickers, role filter chips) and
    // per-message header resolution must not do per-row file IO (allRoles()
    // walks the roles/ directory reading role.json + avatar.png per role).
    // The snapshot is warmed lazily on first access and refreshed on every
    // collection mutation (save/delete). .rolesChanged (already observed by
    // the settings UI) is co-posted so SwiftUI views re-render on edits.

    /// Cached full role collection. Access via [rolesSnapshot] so callers
    /// observe mutations; [roleById]/[resolveRole] read it directly.
    private(set) static var rolesSnapshotCache: [RoleFile] = []

    /// Snapshot accessor: warms on first use, then serves the cache.
    static var rolesSnapshot: [RoleFile] {
        if rolesSnapshotCache.isEmpty {
            refreshRolesSnapshot()
        }
        return rolesSnapshotCache
    }

    /// 按 roleId 查角色（基于内存快照，无文件 IO）。nil → 调用方回退
    /// [loadDefault]（孤儿引用 = 角色已删，渲染期回退默认，与 folderId 策略一致）。
    static func roleById(_ roleId: String?) -> RoleFile? {
        guard let roleId else { return nil }
        return rolesSnapshot.first { $0.roleId == roleId }
    }

    /// [T-role-resolve] 渲染期统一解析器：快照查找 → 回退默认角色。无磁盘
    /// IO，可在 SwiftUI body 里安全调用（配合 .rolesChanged 观察刷新）。
    static func resolveRole(_ roleId: String?) -> RoleFile {
        roleById(roleId) ?? loadDefault()
    }

    /// Reload the snapshot from disk. Called on every collection mutation.
    static func refreshRolesSnapshot() {
        rolesSnapshotCache = allRoles()
    }
    
    /// 获取当前激活的角色
    static func currentRole() -> RoleFile {
        let currentId = currentRoleId
        if let role = load(roleId: currentId) {
            return role
        }
        return loadDefault()
    }
    
    /// 加载指定角色的文件（包括头像）
    static func load(roleId: String) -> RoleFile? {
        let roleDir = rolesDir.appendingPathComponent(roleId)
        let fileURL = roleDir.appendingPathComponent("role.json")
        
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        
        // 读取 role.json
        guard let data = try? Data(contentsOf: fileURL),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        
        let metadata = RoleMetadata(
            name: json["name"] as? String ?? "Jarvis",
            style: json["style"] as? String ?? "",
            lang: json["lang"] as? String ?? "auto",
            isDefault: roleId == `defaultRoleId`
        )
        
        let body = json["body"] as? String ?? ""
        let avatarData = loadAvatar(roleId: roleId)
        
        return RoleFile(metadata: metadata, body: body, avatarData: avatarData, roleId: roleId)
    }
    
    /// 加载头像数据
    static func loadAvatar(roleId: String) -> Data? {
        let avatarURL = rolesDir.appendingPathComponent(roleId).appendingPathComponent("avatar.png")
        return try? Data(contentsOf: avatarURL)
    }
    
    /// 保存角色
    static func save(_ role: RoleFile) throws {
        let fm = FileManager.default
        let roleDir = rolesDir.appendingPathComponent(role.roleId)
        
        // 确保目录存在
        try fm.createDirectory(at: roleDir, withIntermediateDirectories: true)
        
        // 保存 role.json
        let fileURL = roleDir.appendingPathComponent("role.json")
        let json: [String: Any] = [
            "name": role.metadata.name,
            "style": role.metadata.style,
            "lang": role.metadata.lang,
            "body": role.body
        ]
        
        let data = try JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: fileURL, options: .atomic)
        
        // 保存头像(如果提供)
        if let avatarData = role.avatarData {
            let avatarURL = roleDir.appendingPathComponent("avatar.png")
            // 压缩并保存 (保持64x64)
            if let uiImage = UIImage(data: avatarData) {
                let resized = resizeImage(uiImage, to: CGSize(width: 64, height: 64))
                if let pngData = resized.pngData() {
                    try pngData.write(to: avatarURL, options: .atomic)
                }
            }
        }

        // [T-role-snapshot] save may add or rewrite a role file → refresh the
        // collection and tell observers (message headers / list avatars) so
        // edits reflect immediately.
        refreshRolesSnapshot()
        NotificationCenter.default.post(name: .rolesChanged, object: nil)
    }
    
    /// 创建新角色
    static func createRole(name: String, style: String = "", lang: String = "auto", 
                          body: String = "", avatarData: Data? = nil) -> RoleFile {
        var roleId = slugify(name) ?? UUID().uuidString
        var counter = 1
        
        // 确保ID唯一
        while load(roleId: roleId) != nil {
            roleId = "\(roleId)_\(counter)"
            counter += 1
        }
        
        // 角色目录由 save(_:) 按 roleId 建在 roles/<roleId>/ 下。旧实现这里
        // 预先建 roles/custom/<id> 壳目录，但从不往里写 role.json（死代码），
        // 且旧 allRoles() 只遍历 custom/，会让 default 角色和早期角色全部
        // 漏掉。两处已一起修正。
        
        let metadata = RoleMetadata(name: name, style: style, lang: lang, isDefault: false)
        let role = RoleFile(metadata: metadata, body: body, avatarData: avatarData, roleId: roleId)
        
        try? save(role)
        return role
    }
    
    /// 删除角色(不可删除默认角色)
    static func deleteRole(roleId: String) -> Bool {
        guard roleId != `defaultRoleId` else { return false }

        let roleDir = rolesDir.appendingPathComponent(roleId)
        guard FileManager.default.fileExists(atPath: roleDir.path) else { return false }

        try? FileManager.default.removeItem(at: roleDir)
        // [T-role-snapshot] delete shrinks the collection → refresh so rows
        // referencing the deleted role fall back to default via roleById.
        refreshRolesSnapshot()
        NotificationCenter.default.post(name: .rolesChanged, object: nil)
        return true
    }
    
    /// 设置当前激活的角色
    static func setCurrentRole(roleId: String) -> Bool {
        guard load(roleId: roleId) != nil || roleId == `defaultRoleId` else { return false }
        currentRoleId = roleId
        NotificationCenter.default.post(name: .roleSwitched, object: nil)
        return true
    }
    
    /// 切换到下一个角色(循环)
    static func switchToNextRole() {
        let allRoles = RoleStore.allRoles()
        guard !allRoles.isEmpty else { return }
        
        let currentId = currentRoleId
        if let currentIndex = allRoles.firstIndex(where: { $0.roleId == currentId }) {
            let nextIndex = (currentIndex + 1) % allRoles.count
            currentRoleId = allRoles[nextIndex].roleId
        } else {
            currentRoleId = allRoles.first!.roleId
        }
    }
    
    /// 重置为默认角色
    static func resetToDefault() {
        currentRoleId = `defaultRoleId`
    }
    
    // MARK: - Private Helpers
    
    /// slugify函数 - 将名称转换为ID
    private static func slugify(_ text: String) -> String? {
        let slug = text
            .lowercased()
            .replacingOccurrences(of: " ", with: "-")
            .replacingOccurrences(of: "[\\W\\s_]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet.alphanumerics.inverted.union(CharacterSet(charactersIn: "-")))
        return slug.isEmpty ? nil : slug
    }
    
    /// 调整图片尺寸
    private static func resizeImage(_ image: UIImage, to size: CGSize) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
    
    /// 加载默认角色(贾维斯/Jarvis)
    static func loadDefault() -> RoleFile {
        let fm = FileManager.default
        let defaultDir = rolesDir.appendingPathComponent(`defaultRoleId`)
        
        // 如果默认角色不存在，创建
        if !fm.fileExists(atPath: defaultDir.path) {
            let defaultRole = RoleFile(
                metadata: RoleMetadata(name: "Jarvis", style: "", lang: "auto", isDefault: true),
                body: defaultPersonalityBody,
                avatarData: generateDefaultAvatar(),
                roleId: `defaultRoleId`
            )
            try? save(defaultRole)
            NotificationCenter.default.post(name: .rolesChanged, object: nil)
            return defaultRole
        }
        
        // 返回默认角色
        return load(roleId: `defaultRoleId`) ?? RoleFile(
            metadata: RoleMetadata(name: "Jarvis", style: "", lang: "auto", isDefault: true),
            body: defaultPersonalityBody,
            avatarData: generateDefaultAvatar(),
            roleId: `defaultRoleId`
        )
    }
    
    /// 默认人格内核 - 贾维斯/Jarvis
    static let defaultPersonalityBody = """
    Don't perform — help. Skip the "Sure!" and "Happy to assist!" — just do the work.

    Have a stance. It's fine to disagree, prefer one thing over another, find some things interesting and others dull.

    Act first, ask second. If you can look it up, look it up. Come back with answers, not questions.
    """
    
    /// 生成默认头像(贾维斯主题)
    private static func generateDefaultAvatar() -> Data? {
        // 生成一个64x64的蓝色圆形头像带✨
        let size = CGSize(width: 64, height: 64)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { context in
            // 绘制圆形背景
            let rect = CGRect(origin: .zero, size: size)
            UIBezierPath(ovalIn: rect).fill()
            
            // 绘制渐变
            let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: [UIColor.systemBlue.cgColor, UIColor.darkBlue.cgColor] as CFArray,
                locations: [0, 1]
            )!
            context.cgContext.drawRadialGradient(
                gradient,
                startCenter: CGPoint(x: 32, y: 32),
                startRadius: 0,
                endCenter: CGPoint(x: 32, y: 32),
                endRadius: 32,
                options: []
            )
        }
        return image.pngData()
    }
}

// MARK: - 数据模型

/// 角色元数据
struct RoleMetadata: Equatable, Hashable {
    var name: String
    var style: String
    var lang: String
    var isDefault: Bool  // 是否为默认角色
    
    /// 显示头像
    var displayEmoji: String { "✨" }
}

/// 角色文件
struct RoleFile: Equatable, Hashable {
    var metadata: RoleMetadata
    var body: String
    var avatarData: Data?
    var roleId: String
    
    /// 哈希支持
    func hash(into hasher: inout Hasher) {
        hasher.combine(roleId)
    }
    
    static func == (lhs: RoleFile, rhs: RoleFile) -> Bool {
        lhs.roleId == rhs.roleId &&
        lhs.metadata.name == rhs.metadata.name &&
        lhs.metadata.style == rhs.metadata.style &&
        lhs.metadata.lang == rhs.metadata.lang
    }
}

// MARK: - Legacy SOUL.md 解析器

enum SoulMDParser {
    
    /// 解析 SOUL.md 文件内容（向后兼容）
    static func parse(_ source: String) -> (metadata: RoleMetadata, body: String) {
        let trimmedLeading = source.drop(while: { $0 == "\n" || $0 == "\r" })
        
        guard trimmedLeading.hasPrefix("---") else {
            return (RoleMetadata(name: "Jarvis", style: "", lang: "auto", isDefault: true), source)
        }
        
        let lines = String(trimmedLeading).components(separatedBy: "\n")
        guard lines.first?.trimmingCharacters(in: .whitespaces) == "---",
              let closeIdx = lines.dropFirst().firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "---" })
        else {
            return (RoleMetadata(name: "Jarvis", style: "", lang: "auto", isDefault: true), source)
        }
        
        let frontmatterLines = Array(lines[1..<closeIdx])
        let bodyLines = Array(lines[(closeIdx + 1)...])
        let body = bodyLines.joined(separator: "\n").drop(while: { $0 == "\n" || $0 == "\r" })
        
        var meta = RoleMetadata(name: "Jarvis", style: "", lang: "auto", isDefault: true)
        for raw in frontmatterLines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            var value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            if value.hasPrefix("\""), value.hasSuffix("\""), value.count >= 2 {
                value = String(value.dropFirst().dropLast())
            }
            switch key {
            case "name": if !value.isEmpty { meta.name = value }
            case "style": meta.style = value
            case "lang": if !value.isEmpty { meta.lang = value }
            default: break
            }
        }
        
        return (meta, String(body))
    }
}

// MARK: - 系统提示构建器

enum RoleSystemPromptBuilder {
    
    private static let identityTemplate =
        "You are {name}, a capable AI assistant. "
    
    /// 构建身份部分的系统提示
    static func identitySection(for role: RoleFile? = nil) -> String {
        let roleFile = role ?? RoleStore.currentRole()
        let name = roleFile.metadata.name.isEmpty ? "Jarvis" : roleFile.metadata.name
        let style = roleFile.metadata.style.trimmingCharacters(in: .whitespaces)
        let body = roleFile.body.trimmingCharacters(in: .whitespacesAndNewlines)
        
        let identity = identityTemplate.replacingOccurrences(of: "{name}", with: name)
        let identityTrimmed = identity.trimmingCharacters(in: .whitespaces)
        
        // 固定提示 - 告诉模型如何修改角色
        let roleEditHint = """
        ---
        角色配置 (role.json) 的字段可以通过两种方式修改:
        1. 工具: 调用 `minis-config` 提议更改(需用户批准).
        2. UI: 前往 设置 → 角色 直接编辑.
        选择您在上下文中更易用的方式. 请勿说"我无法更改人格".
        """
        
        // 风格块
        func styleBlock(_ s: String) -> String {
            guard !s.isEmpty else { return "" }
            return "\n\nResponse style (从角色配置 `style` —— 对每条回复应用，除非用户明确要求其他风格; 如果它指定回复语言，请覆盖默认的匹配用户语言规则):\n\(s)"
        }
        
        guard !body.isEmpty else {
            return identityTrimmed + styleBlock(style) + "\n\n" + roleEditHint + "\n\n"
        }
        
        // 过滤注入
        let cleanBody = scrubInjections(body)
        
        return identityTrimmed
            + "\n\nPersonality (from role.json — your character and voice; defer to the user's latest message when it conflicts with anything here):\n"
            + cleanBody
            + styleBlock(style)
            + "\n\n"
            + roleEditHint
            + "\n\n"
    }
    
    /// 过滤注入尝试
    private static func scrubInjections(_ s: String) -> String {
        let patterns = [
            #"(?i)ignore.{0,30}previous.{0,30}instructions?"#,
            #"(?i)disregard.{0,30}(previous|prior).{0,30}instructions?"#,
            #"(?i)forget.{0,30}(previous|prior).{0,30}instructions?"#
        ]
        
        return s.components(separatedBy: "\n")
            .filter { line in
                for p in patterns {
                    if line.range(of: p, options: .regularExpression) != nil {
                        return false
                    }
                }
                return true
            }
            .joined(separator: "\n")
    }
}

// MARK: - 通知

extension Notification.Name {
    static let roleSwitched = Notification.Name("MinisRoleSwitched")
    static let rolesChanged = Notification.Name("MinisRolesChanged")
}

// MARK: - [T-role-session-bound] 跨屏角色导航总线
//
// 聊天顶栏的角色引导（以此角色新建会话 / 查看该角色的历史会话）需要
// ContentView 执行导航（新建草稿 / 列表过滤），而 AIChatView 无法直接触达
// 父级导航。仿 Android SessionListViewModel.pendingRoleFilter 的做法，用
// 单例 ObservableObject 传递一次性请求；ContentView 消费后清空。

@MainActor
final class RoleNavigationBus: ObservableObject {
    static let shared = RoleNavigationBus()

    /// "以此角色新建会话" 请求的目标 roleId。消费后置 nil。
    @Published var newChatWithRole: String? = nil
    /// "查看该角色的历史会话" 请求的目标 roleId（列表 roleFilter）。消费后置 nil。
    @Published var showRoleHistory: String? = nil
}

// MARK: - 辅助视图

/// 渲染当前角色名称
@MainActor
struct AssistantRoleName: View {
    @State private var name: String = RoleStore.loadDefault().metadata.name

    var body: some View {
        Text(name)
            .onReceive(NotificationCenter.default.publisher(for: .roleSwitched)) { _ in
                let n = RoleStore.currentRole().metadata.name
                name = n.isEmpty ? "Jarvis" : n
            }
    }
}

/// [T-role-message-level] Assistant 消息头部：按消息的 roleId 解析生成角色
/// （快照查找 → 回退默认），头像+名。观察 .rolesChanged，角色编辑/删除后
/// 即时重渲染；roleId 固定不动，全局切换当前角色不影响历史消息。
@MainActor
struct AssistantMessageHeader: View {
    /// 生成该条消息的角色 id。nil（旧数据）→ 默认角色回退。
    let roleId: String?
    @State private var role: RoleFile = RoleStore.loadDefault()

    var body: some View {
        HStack(spacing: 6) {
            if let data = role.avatarData, let img = UIImage(data: data) {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 18, height: 18)
                    .clipShape(Circle())
            } else {
                Image(systemName: "sparkles")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [Color(red: 0.72, green: 0.69, blue: 0.59),
                                     Color(red: 0.6, green: 0.6, blue: 0.55)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
            }
            Text(role.metadata.name.isEmpty ? "Jarvis" : role.metadata.name)
                .font(.body.weight(.semibold))
                .foregroundColor(.primary)
        }
        .onAppear { role = RoleStore.resolveRole(roleId) }
        .onReceive(NotificationCenter.default.publisher(for: .rolesChanged)) { _ in
            role = RoleStore.resolveRole(roleId)
        }
        // roleId itself never changes per message, but roleSwitched doesn't
        // affect it either — no extra observers needed.
    }
}

/// [T-role-avatar] 圆形角色头像（列表行/顶栏 chip 复用）。按 roleId 从快照
/// 解析 → 回退默认头像；无图时由调用方回退 ✨。观察 .rolesChanged 刷新。
@MainActor
struct RoleAvatarImage: View {
    let roleId: String?
    var size: CGFloat = 24
    @State private var image: UIImage? = nil

    private func resolve() {
        let role = RoleStore.resolveRole(roleId)
        image = role.avatarData.flatMap { UIImage(data: $0) }
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "sparkles")
                    .font(.system(size: size * 0.55, weight: .semibold))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.indigo, .gray],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .onAppear { resolve() }
        .onReceive(NotificationCenter.default.publisher(for: .rolesChanged)) { _ in
            resolve()
        }
    }
}