package com.jarvis.app.agent

import android.content.Context
import androidx.room.withTransaction
import com.jarvis.app.data.db.AgentGroupEntity
import com.jarvis.app.data.db.AppDatabase
import com.jarvis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * [T-group-chat] One roster entry inside [AgentGroup.members]. Array position
 * in the persisted members_json IS the round-robin speaking order.
 *
 * @param participatesInRound false = excluded from @全体 rounds (a direct
 *   @mention still reaches the member — the toggle is a round-membership
 *   switch, not a mute button).
 */
data class AgentGroupMember(
    val roleId: String,
    val participatesInRound: Boolean = true,
)

/**
 * [T-group-chat-model-override] A complete model reference — the same shape
 * as the per-message attribution snapshot columns (`model_id` +
 * `provider_instance_id`), because users have several provider instances that
 * can carry the same model id. A bare modelId is ambiguous across instances.
 */
data class AgentGroupModelRef(
    val modelId: String,
    val providerInstanceId: String,
)

/** [T-group-chat] An agent group: a named roster of roles reusable across group sessions. */
data class AgentGroup(
    val id: String,
    val name: String,
    /** 群公告 / group instruction — injected into every member's system prompt. Null = none. */
    val instruction: String?,
    /** Roster in round-robin speaking order. See [AgentGroupMember]. */
    val members: List<AgentGroupMember>,
    /** Moderator role. Null = none (the default). Speaks last; extra prompt duties. */
    val hostRoleId: String?,
    /** [T-group-chat-model-override] Unified model for the whole group. Null = OFF (default). */
    val modelOverride: AgentGroupModelRef?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * [T-group-chat] A roster entry resolved against the live RoleStore.
 * Unresolvable members (role deleted) are dropped by [GroupStore.resolveMembers],
 * NOT replaced with the default role — a deleted "架构师" must not come back
 * speaking as Jarvis.
 */
data class ResolvedGroupMember(
    val role: RoleFile,
    val participatesInRound: Boolean,
)

/**
 * [T-group-chat] 门面：agent_groups 表的内存快照 + CRUD。API 风格仿
 * [RoleStore]（object + prime + StateFlow snapshot），存储走 Room
 * （[AgentGroupEntity]，AppDatabase v2）。
 *
 * 与 RoleStore 的关键差异：群是 DB 行而非文件，因此写路径直接进 DAO、
 * snapshot 在写成功后刷新（无文件监听）；删除群是显式事务（群行 + 该群
 * 全部会话，消息由 FK CASCADE 带走——设计文档 D6），不是 FK 级联。
 */
object GroupStore {

    private const val TAG = "GroupStore"

    @Volatile
    private var appContext: Context? = null

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("GroupStore.prime() 尚未调用")

    private val _groupsSnapshot = MutableStateFlow<List<AgentGroup>>(emptyList())

    /** Render-time group lookups (session list rows, pickers). Warmed by [prime]. */
    val groupsSnapshot: StateFlow<List<AgentGroup>> = _groupsSnapshot.asStateFlow()

    /**
     * App 启动时初始化（MinisApp.onCreate，RoleStore.prime 之后）。同步加载
     * 全部群——群数量级是个位数到十几，单表 LIMIT-less 全量查询在启动路径
     * 上可忽略；与 RoleStore 的 snapshot 预热同理，首个会话列表帧不能等待
     * 异步刷新。
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        _groupsSnapshot.value = runCatching {
            kotlinx.coroutines.runBlocking { allGroups() }
        }.getOrElse { e ->
            AppLogger.warning(TAG, "prime: group load failed: ${e.message}")
            emptyList()
        }
    }

    /** Restore 后重建快照（仿 RoleStore.refreshAfterRestore）。 */
    fun refreshAfterRestore() {
        if (appContext == null) return
        kotlinx.coroutines.runBlocking { refreshSnapshot() }
    }

    fun groupById(groupId: String?): AgentGroup? =
        if (groupId == null) null else _groupsSnapshot.value.find { it.id == groupId }

    // ─── CRUD ──────────────────────────────────────────────────────────────

    /**
     * @param members roster in round-robin order; caller decides order (UI
     *   passes the checkbox order). Host is stored as given — speaking-last
     *   is applied at schedule time, never by reordering this list.
     */
    suspend fun createGroup(
        name: String,
        members: List<AgentGroupMember>,
        hostRoleId: String? = null,
        instruction: String? = null,
        modelOverride: AgentGroupModelRef? = null,
    ): AgentGroup {
        val now = System.currentTimeMillis()
        val group = AgentGroup(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            instruction = instruction?.trim()?.ifBlank { null },
            members = members,
            hostRoleId = hostRoleId,
            modelOverride = modelOverride,
            createdAt = now,
            updatedAt = now,
        )
        dao().upsertGroup(toEntity(group))
        refreshSnapshot()
        return group
    }

    /**
     * Full-row update (rename / instruction / roster order / participation
     * toggles / host / unified-model override). REPLACE upsert — the entity
     * carries no local-only state a blind replace could lose.
     */
    suspend fun updateGroup(group: AgentGroup) {
        val updated = group.copy(updatedAt = System.currentTimeMillis())
        dao().upsertGroup(toEntity(updated))
        refreshSnapshot()
    }

    /**
     * Delete a group AND all its sessions (design doc D6 — UI must confirm;
     * there is no "leave sessions behind" path, unlike folders' dissolve).
     * One transaction so a crash mid-way can't strand sessions without their
     * group row.
     *
     * @return ids of the deleted sessions, so the UI can navigate away if the
     *   currently-open chat was among them.
     */
    suspend fun deleteGroup(groupId: String): List<String> {
        val db = AppDatabase.getInstance(requireContext())
        val deletedSessions = db.withTransaction {
            val sessionIds = db.chatDao().sessionsInGroup(groupId).map { it.id }
            db.chatDao().deleteSessionsInGroup(groupId)
            db.agentGroupDao().deleteGroup(groupId)
            sessionIds
        }
        refreshSnapshot()
        return deletedSessions
    }

    /**
     * Resolve the roster against the live RoleStore, keeping array order.
     * Unresolvable members are dropped silently here — the UI surfaces them
     * as grey "已删除" rows via [unresolvedMemberIds] and the chat scheduler
     * inserts a systemInfo line per dropped member (§3.3). NOT written back:
     * the stored roster keeps the stale id until the user cleans it up, so a
     * temporary snapshot race can never permanently shrink a group.
     */
    suspend fun resolveMembers(group: AgentGroup): List<ResolvedGroupMember> {
        val ctx = requireContext()
        val out = ArrayList<ResolvedGroupMember>(group.members.size)
        for (member in group.members) {
            val role = RoleStore.roleById(member.roleId)
                ?: runCatching { RoleStore.loadRole(ctx, member.roleId) }.getOrNull()
                ?: continue
            out.add(ResolvedGroupMember(role, member.participatesInRound))
        }
        return out
    }

    /** Roster ids that no longer resolve — the grey rows in the group settings screen. */
    fun unresolvedMemberIds(group: AgentGroup): List<String> {
        val known = RoleStore.rolesSnapshot.value.map { it.roleId }.toHashSet()
        return group.members.map { it.roleId }.filter { it !in known }
    }

    // ─── JSON (de)serialization ────────────────────────────────────────────
    // org.json everywhere (repo convention). Defensively tolerant: a malformed
    // members_json degrades to an empty roster (rendered as an empty group the
    // user can edit), never a crash on load.

    fun membersToJson(members: List<AgentGroupMember>): String {
        val arr = JSONArray()
        for (m in members) {
            arr.put(JSONObject().put("roleId", m.roleId).put("participatesInRound", m.participatesInRound))
        }
        return arr.toString()
    }

    fun membersFromJson(json: String): List<AgentGroupMember> {
        val out = ArrayList<AgentGroupMember>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val roleId = o.optString("roleId", "").trim()
                if (roleId.isEmpty()) continue
                out.add(AgentGroupMember(roleId, o.optBoolean("participatesInRound", true)))
            }
        }.onFailure { AppLogger.warning(TAG, "members_json parse failed: ${it.message}") }
        return out
    }

    fun modelOverrideToJson(ref: AgentGroupModelRef): String =
        JSONObject().put("modelId", ref.modelId).put("providerInstanceId", ref.providerInstanceId).toString()

    fun modelOverrideFromJson(json: String?): AgentGroupModelRef? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            val modelId = o.optString("modelId", "").trim()
            val instanceId = o.optString("providerInstanceId", "").trim()
            if (modelId.isEmpty() || instanceId.isEmpty()) null
            else AgentGroupModelRef(modelId, instanceId)
        }.getOrNull()
    }

    // ─── mapping ───────────────────────────────────────────────────────────

    private fun toEntity(group: AgentGroup) = AgentGroupEntity(
        id = group.id,
        name = group.name,
        instruction = group.instruction,
        membersJson = membersToJson(group.members),
        hostRoleId = group.hostRoleId,
        modelOverrideJson = group.modelOverride?.let { modelOverrideToJson(it) },
        maxRounds = 1,
        createdAt = group.createdAt,
        updatedAt = group.updatedAt,
    )

    private fun fromEntity(e: AgentGroupEntity) = AgentGroup(
        id = e.id,
        name = e.name,
        instruction = e.instruction,
        members = membersFromJson(e.membersJson),
        hostRoleId = e.hostRoleId,
        modelOverride = modelOverrideFromJson(e.modelOverrideJson),
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
    )

    private suspend fun refreshSnapshot() {
        _groupsSnapshot.value = runCatching { allGroups() }
            .getOrElse { e ->
                AppLogger.warning(TAG, "group refresh failed: ${e.message}")
                _groupsSnapshot.value // keep the last good snapshot on a transient DB error
            }
    }

    private suspend fun allGroups(): List<AgentGroup> =
        AppDatabase.getInstance(requireContext()).agentGroupDao().listGroups().map { fromEntity(it) }

    private fun dao() = AppDatabase.getInstance(requireContext()).agentGroupDao()
}
