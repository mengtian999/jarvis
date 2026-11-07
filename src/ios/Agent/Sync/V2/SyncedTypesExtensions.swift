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

// MARK: - SyncedAgentGroup (Agent 群)
//
// One record per agent group. members_json travels verbatim (raw DB row
// shape — the parser lives in GroupStore, and peers that don't know a field
// just ignore it). LWW by updatedAt per group. Mirrors Android's backup
// `agent_groups.json` row shape.
struct SyncedAgentGroup: Syncable {
    var id: String
    var name: String
    var instruction: String?
    var membersJson: String
    var hostRoleId: String?
    var modelOverrideJson: String?
    var maxRounds: Int
    var createdAt: Date
    var updatedAt: Date

    static let syncMetadata: SyncTypeMetadata<SyncedAgentGroup> = {
        typealias F = FieldDescriptor<SyncedAgentGroup>
        return SyncTypeMetadata<SyncedAgentGroup>(
            recordType: "AgentGroupV2",
            idKeyPath: \SyncedAgentGroup.id,
            scope: .global,
            fields: [
                F.string("groupId",           \SyncedAgentGroup.id),
                F.string("name",              \SyncedAgentGroup.name),
                F.optionalString("instruction",   \SyncedAgentGroup.instruction),
                F.string("membersJson",       \SyncedAgentGroup.membersJson),
                F.optionalString("hostRoleId",    \SyncedAgentGroup.hostRoleId),
                F.optionalString("modelOverrideJson", \SyncedAgentGroup.modelOverrideJson),
                F.int("maxRounds",            \SyncedAgentGroup.maxRounds),
                F.date("createdAt",           \SyncedAgentGroup.createdAt),
                F.date("updatedAt",           \SyncedAgentGroup.updatedAt),
            ],
            conflictPolicy: .lastWriteWinsByField(\SyncedAgentGroup.updatedAt),
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

    /// [T-group-chat] P4: agent-group records. Registered together with the
    /// roles family — groups reference roleIds, so peers should be able to
    /// resolve rosters as soon as both families land.
    static func registerAgentGroups() {
        let r = SyncableTypeRegistry.shared
        r.register(SyncedAgentGroup.self)
    }
}