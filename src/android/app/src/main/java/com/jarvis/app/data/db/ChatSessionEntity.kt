package com.jarvis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [T-android-session-grouping] The `folder_id` index is declared here so the
 * entity and MIGRATION_10_11 agree — Room validates the live schema against
 * the entity on open, and an index present in one but not the other aborts
 * startup with an IllegalStateException.
 *
 * Non-unique on purpose: many sessions share one group.
 */
@Entity(
    tableName = "sessions",
    indices = [
        androidx.room.Index(value = ["folder_id"], name = "index_sessions_folder_id"),
        // [T-role-session-bound] Backs the "filter sessions by role" list
        // query and the per-role history view. Plain non-unique index — many
        // sessions share one role, same shape as folder_id.
        androidx.room.Index(value = ["role_id"], name = "index_sessions_role_id"),
    ],
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,        // milliseconds
    @ColumnInfo(name = "updated_at") val updatedAt: Long,        // milliseconds
    val category: String? = null,
    @ColumnInfo(name = "last_message") val lastMessage: String? = null,
    @ColumnInfo(name = "model_binding") val modelBinding: String? = null,
    // iOS parity fields:
    @ColumnInfo(name = "source") val source: String? = null,             // e.g. "shortcut", "share"
    @ColumnInfo(name = "memory_enabled") val memoryEnabled: Int = 1,     // 1=on, 0=off
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,          // milliseconds, null=not pinned
    @ColumnInfo(name = "edit_count") val editCount: Int = 0,             // message edit counter
    // T239: per-session thinking-mode override. null = unset (use the
    // current model/group default — i.e. existing pre-T239 behaviour, which
    // is OFF on Android today). Non-null is one of ThinkingLevel.name
    // ("OFF"/"LOW"/"MEDIUM"/"HIGH"/"XHIGH") and represents an explicit user
    // choice that survives cold-start.
    @ColumnInfo(name = "thinking_override") val thinkingOverride: String? = null,
    /**
     * [T-android-session-grouping] Group membership. NULL = ungrouped.
     *
     * Deliberately NOT a declared @ForeignKey. A folder_id pointing at a group
     * that does not exist locally is a legitimate transient state, not
     * corruption: a future sync could deliver the session before its group, and
     * a group dissolved on another device leaves references behind until that
     * change arrives. Such orphans render as ungrouped (see
     * SessionListViewModel's grouping pass) instead of failing a constraint or
     * making the session vanish. Same rule as iOS (ChatStore.swift:610).
     *
     * NOTE for anyone adding list diffing: this field MUST participate in
     * equality. Moving a session between groups changes nothing else — not even
     * `updatedAt`, by design — so a differ that ignores it keeps drawing the row
     * in its old section.
     */
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    /**
     * [T-role-session-bound] The persona bound to this session — determines
     * the personality for subsequent replies. Immutable within a session (no
     * in-session role switch); set at createSession time. Synced across
     * devices (NOT device-local). Orphan refs (role deleted) render via
     * RoleStore.loadDefault() fallback — same strategy as folder_id. Null
     * only for legacy/abnormal rows; new sessions always seed the current
     * global role at creation.
     *
     * NOTE for list diffing: this field MUST participate in equality (data
     * class == covers it automatically). A future sync could deliver an
     * updated roleId; a differ that ignored it would keep drawing the old
     * role. Mirrors folderId's equality requirement above.
     */
    @ColumnInfo(name = "role_id") val roleId: String? = null,
)
