// +--------------------------------------------------------------+
// | RoleSyncExtensions.swift - 角色同步器扩展                      |
// +--------------------------------------------------------------+

import Foundation

extension ChatStoreSyncHydrators {
    
    // MARK: - Role 同步器
    
    /// 构建角色记录
    private static func buildRole(id: String) async -> PortableRecord? {
        guard let role = await MainActor.run { RoleStore.load(roleId: id) } else {
            return nil
        }
        
        let bodyJson = try? JSONSerialization.data(withJSONObject: ["body": role.body], options: [])
            .flatMap { String(data: $0, encoding: .utf8) }
            ?? "{}"
        
        let avatarBase64 = role.avatarData.flatMap {
            String(data: $0, encoding: .utf8)
        }
        
        let synced = SyncedRole(
            id: id,
            name: role.metadata.name,
            style: role.metadata.style,
            lang: role.metadata.lang,
            bodyJson: bodyJson ?? "{}",
            avatarBase64: avatarBase64,
            updatedAt: Date()
        )
        
        return SyncableTypeRegistry.shared.metadata(for: "RoleV2")?.buildPortable(synced)
    }
    
    /// 合并角色记录
    private static func mergeRole(record: PortableRecord) async {
        guard let id = stringField(record, "roleId") else {
            logger.warning("[SyncCore] mergeRole: missing roleId field")
            return
        }
        
        let role = RoleFile(
            metadata: RoleMetadata(
                name: stringField(record, "name") ?? "Jarvis",
                style: stringField(record, "style") ?? "",
                lang: stringField(record, "lang") ?? "auto"
            ),
            body: {
                guard let bodyJson = stringField(record, "bodyJson"),
                      let data = bodyJson.data(using: .utf8),
                      let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let body = json["body"] as? String else {
                    return ""
                }
                return body
            }(),
            avatarData: {
                guard let b64 = stringField(record, "avatarBase64"),
                      let data = Data(base64Encoded: b64) else {
                    return nil
                }
                return data
            }(),
            roleId: id,
            isDefault: id == "default"
        )
        
        try? RoleStore.save(role)
        logger.info("[SyncCore] applied RoleV2: \(id)")
    }
    
    /// 构建角色集合记录
    private static func buildRolesCollection() async -> PortableRecord? {
        let rolesDir = RoleStore.rolesDir
        let fm = FileManager.default
        
        var roleIds: [String] = []
        var currentRoleId = RoleStore.currentRoleId
        
        // 收集所有角色ID
        let allRoles = await MainActor.run { RoleStore.allRoles() }
        for role in allRoles {
            roleIds.append(role.roleId)
        }
        
        // 确保 default 角色在列表中
        if !roleIds.contains("default") {
            roleIds.insert("default", at: 0)
        }
        
        let synced = SyncedRolesCollection(
            roleIds: try? JSONSerialization.data(withJSONObject: roleIds, options: [])
                .flatMap { String(data: $0, encoding: .utf8) }
                ?? "[\"default\"]",
            currentRoleId: currentRoleId,
            updatedAt: Date()
        )
        
        return SyncableTypeRegistry.shared.metadata(for: "RolesCollectionV2")?.buildPortable(synced)
    }
    
    /// 合并角色集合记录
    private static func mergeRolesCollection(record: PortableRecord) async {
        guard let roleIdsJson = stringField(record, "roleIds") else {
            logger.warning("[SyncCore] mergeRolesCollection: missing roleIds field")
            return
        }
        
        guard let data = roleIdsJson.data(using: .utf8),
              let roleIds = try? JSONSerialization.jsonObject(with: data) as? [String] else {
            logger.warning("[SyncCore] mergeRolesCollection: invalid roleIds JSON")
            return
        }
        
        let currentRoleId = stringField(record, "currentRoleId") ?? "default"
        
        // 应用角色集合
        await MainActor.run {
            // 记录当前角色
            RoleStore.currentRoleId = currentRoleId
            
            // 确保所有角色都被拉取
            // 这需要额外的 fetch 操作，此处标记为需处理
            logger.info("[SyncCore] applied RolesCollectionV2: \(roleIds.count) roles, current=\(currentRoleId)")
        }
    }
}