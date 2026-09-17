// +--------------------------------------------------------------+
// | SyncedTypesExtensions.swift - 角色同步类型扩展                |
// +--------------------------------------------------------------+

import Foundation

// MARK: - SyncedRole (角色配置)
//
// One record per role. Role IDs include the special "default" role.
// Avatar data is encoded as base64 PNG. LWW by updatedAt for each role.
struct SyncedRole: Syncable {
    var id: String            // roleId — e.g. "default", "persona-john"
    var name: String          // Display name
    var style: String         // Style description
    var lang: String          // "auto", "zh", "en"
    var bodyJson: String      // JSON: {"body": "..."}
    var avatarBase64: String?  // Base64 PNG (64x64), nil for default
    var updatedAt: Date
    
    static let syncMetadata: SyncTypeMetadata<SyncedRole> = {
        typealias F = FieldDescriptor<SyncedRole>
        return SyncTypeMetadata<SyncedRole>(
            recordType: "RoleV2",
            idKeyPath: \SyncedRole.id,
            scope: .global,
            fields: [
                F.string("roleId",        \SyncedRole.id),
                F.string("name",          \SyncedRole.name),
                F.string("style",         \SyncedRole.style),
                F.string("lang",          \SyncedRole.lang),
                F.string("bodyJson",      \SyncedRole.bodyJson),
                F.optionalString("avatarBase64", \SyncedRole.avatarBase64),
                F.date("updatedAt",       \SyncedRole.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedRole.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedRolesCollection (角色集合索引)
//
// Singleton record per account that tracks which roles exist and which is current.
struct SyncedRolesCollection: Syncable {
    var id: String = "roles-collection"  // constant — one record per account
    var roleIds: String                    // JSON: ["default", "persona-john", ...]
    var currentRoleId: String              // Which role is active
    var updatedAt: Date
    
    static let syncMetadata: SyncTypeMetadata<SyncedRolesCollection> = {
        typealias F = FieldDescriptor<SyncedRolesCollection>
        return SyncTypeMetadata<SyncedRolesCollection>(
            recordType: "RolesCollectionV2",
            idKeyPath: \SyncedRolesCollection.id,
            scope: .global,
            fields: [
                F.string("roleIds",         \SyncedRolesCollection.roleIds),
                F.string("currentRoleId",   \SyncedRolesCollection.currentRoleId),
                F.date("updatedAt",         \SyncedRolesCollection.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedRolesCollection.updatedAt),
            version: 1
        )
    }()
}

// MARK: - SyncedTypesBootstrap 注册

extension SyncedTypesBootstrap {
    static func registerRoles() {
        let r = SyncableTypeRegistry.shared
        r.register(SyncedRole.self)
        r.register(SyncedRolesCollection.self)
    }
}