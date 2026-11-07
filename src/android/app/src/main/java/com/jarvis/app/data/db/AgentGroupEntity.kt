package com.jarvis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [T-group-chat] An agent group — a reusable roster of roles the user can
 * pull into a group-chat session ("评审团", "项目组"). The group itself owns NO
 * messages; a session bound via `sessions.group_id` is where the conversation
 * lives. Design doc: docs/agent-group-chat-design.md (§2.1).
 *
 * Mirrors the iOS `agent_groups` table in ChatStore.swift column-for-column
 * so a future sync layer can map the two without a translation step (same
 * rule as FolderEntity vs the iOS `folders` table).
 *
 * ## Why `name` is NOT unique
 *
 * Same reasoning as FolderEntity: two devices can each create "项目组" offline;
 * keying on the name would turn a rename into an identity change. A locally
 * generated UUID needs no cross-device coordination.
 *
 * ## Why no @ForeignKey anywhere
 *
 * host_role_id and the roleIds inside members_json reference RoleStore files
 * (not DB rows), and sessions reference this table without an FK constraint —
 * an orphan ref is a legitimate transient state (role deleted, sync not yet
 * arrived), rendered via GroupStore.resolveMembers' fallback instead of
 * failing a constraint. Deleting a group deletes its sessions in an explicit
 * transaction, not via cascade.
 */
@Entity(tableName = "agent_groups")
data class AgentGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    /**
     * 群公告 / group-level instruction, injected into every member's system
     * prompt. Null = none. Kept short by the UI, not enforced here.
     */
    val instruction: String? = null,
    /**
     * Member roster as JSON: `[{"roleId":"…","participatesInRound":true}]`.
     * ARRAY ORDER IS LOAD-BEARING: it is the round-robin speaking order
     * (host always speaks last regardless of position). participatesInRound
     * false = excluded from @全体 rounds (direct @mentions still reach them).
     * Parsed/serialized by GroupStore — never hand-edit in SQL.
     */
    @ColumnInfo(name = "members_json") val membersJson: String,
    /** Moderator role. Null = none (the default). Speaks last; extra prompt duties. */
    @ColumnInfo(name = "host_role_id") val hostRoleId: String? = null,
    /**
     * [T-group-chat-model-override] Group-level unified model as JSON
     * `{"modelId":"…","providerInstanceId":"…"}`. Null = override OFF (the
     * default). Resolution chain at speak time: this > role.model > session
     * default (design doc §5). Resolution-time only — never written back into
     * role.json.
     */
    @ColumnInfo(name = "model_override_json") val modelOverrideJson: String? = null,
    /**
     * Reserved for v3 (agent-to-agent @mentions): max agent rounds per user
     * message. Always 1 today; no code path reads it yet. Kept as a column so
     * enabling v3 needs no migration.
     */
    @ColumnInfo(name = "max_rounds") val maxRounds: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * Tracks RECORD edits (rename / roster / settings) — NOT chat activity.
     * A session in the group getting new messages must not touch it.
     */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
