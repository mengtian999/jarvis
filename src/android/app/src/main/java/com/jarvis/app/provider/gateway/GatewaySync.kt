package com.jarvis.app.provider.gateway

import android.util.Log
import com.jarvis.app.data.model.LLMModel
import com.jarvis.app.data.model.ModelGroup
import com.jarvis.app.data.model.ProviderCredential
import com.jarvis.app.data.model.ProviderInstance
import com.jarvis.app.data.model.ProviderType
import com.jarvis.app.data.repository.ProviderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Gateway model catalog sync — mirrors iOS GatewaySync.swift and desktop gateway-sync.ts. */
class GatewaySync(
    private val context: android.content.Context,
    private val repository: ProviderRepository,
    private val client: GatewayClient = GatewayClient(context),
) {
    companion object {
        private const val TAG = "GatewaySync"
        const val INSTANCE_ID = "jarvis-gateway"
        const val PROVIDER_LABEL = "Jarvis Cloud"
        const val PROVIDER_NAME = "Jarvis Cloud"
        private const val PREFS_NAME = "gateway_sync_prefs"
        private const val KEY_AGENT_LOOP_MEDIA_SEEDED = "agent_loop_media_models_seeded"

        fun isOfficeTier(entry: com.jarvis.app.data.model.ModelEntry): Boolean {
            val id = entry.baseModel.id
            val name = entry.model.displayName
            return id.equals("office", ignoreCase = true) ||
                id.contains("office", ignoreCase = true) ||
                name.contains("办公") ||
                name.contains("Office", ignoreCase = true)
        }

        fun isRoleTier(entry: com.jarvis.app.data.model.ModelEntry): Boolean {
            val id = entry.baseModel.id
            val name = entry.model.displayName
            return id.equals("role", ignoreCase = true) ||
                id.contains("role", ignoreCase = true) ||
                name.contains("角色") ||
                name.contains("Role", ignoreCase = true)
        }

        fun isCodingTier(entry: com.jarvis.app.data.model.ModelEntry): Boolean {
            val id = entry.baseModel.id
            val name = entry.model.displayName
            return id.equals("coding", ignoreCase = true) ||
                id.contains("coding", ignoreCase = true) ||
                name.contains("代码") ||
                name.contains("Coding", ignoreCase = true)
        }

        fun isAutoTier(entry: com.jarvis.app.data.model.ModelEntry): Boolean {
            val id = entry.baseModel.id
            val name = entry.model.displayName
            return id.equals("auto", ignoreCase = true) ||
                id.contains("auto", ignoreCase = true) ||
                name.contains("贾维斯") ||
                name.contains("通用") ||
                name.contains("智能推荐") ||
                name.contains("Auto", ignoreCase = true)
        }

        fun defaultGatewayTierEntryIds(entries: List<com.jarvis.app.data.model.ModelEntry>): List<String> {
            val gatewayEntries = entries.filter { it.providerInstanceId == INSTANCE_ID && !it.isHidden }
            val target = if (gatewayEntries.isNotEmpty()) gatewayEntries else entries.filter { !it.isHidden }
            val autoEntry = target.firstOrNull { isAutoTier(it) }
            val officeEntry = target.firstOrNull { isOfficeTier(it) }
            val roleEntry = target.firstOrNull { isRoleTier(it) }
            val preferred = listOfNotNull(autoEntry, officeEntry, roleEntry).distinctBy { it.id }
            if (preferred.size >= 3) return preferred.map { it.id }

            val nonCoding = target.filterNot { isCodingTier(it) }
            return (preferred + nonCoding + target).distinctBy { it.id }.take(3).map { it.id }
        }

        internal data class GatewayTier(val id: String, val displayName: String, val isDefault: Boolean)

        internal fun parseModels(json: JSONObject, region: String, kind: String): List<GatewayTier> {
            json.optJSONObject("pools")?.optJSONObject(region)?.let { pool ->
                parseTierArray(pool, kind).takeIf { it.isNotEmpty() }?.let { return it }
            }
            val data = json.optJSONArray("data") ?: return emptyList()
            val tiers = mutableListOf<GatewayTier>()
            for (i in 0 until data.length()) {
                val entry = data.getJSONObject(i)
                val id = entry.optString("id", "")
                if (id.isEmpty()) continue
                val entryKind = entry.optString("kind", null)
                val entryRegion = entry.optString("region", null)
                val kindOK = entryKind == null || entryKind == kind
                val regionOK = entryRegion == null || entryRegion == region
                if (kindOK && regionOK) parseTier(entry)?.let { tiers.add(it) }
            }
            return tiers
        }

        private fun parseTierArray(pool: JSONObject, kind: String): List<GatewayTier> {
            val arr = pool.optJSONArray(kind) ?: return emptyList()
            val tiers = mutableListOf<GatewayTier>()
            for (i in 0 until arr.length()) parseTier(arr.getJSONObject(i))?.let { tiers.add(it) }
            return tiers
        }

        private fun parseTier(entry: JSONObject): GatewayTier? {
            val id = entry.optString("id", "")
            if (id.isEmpty()) return null
            return GatewayTier(id, entry.optString("display_name", id), entry.optBoolean("is_default", false))
        }
    }

    data class SyncResult(
        val ok: Boolean,
        val models: List<String> = emptyList(),
        val imageModels: List<String> = emptyList(),
        val videoModels: List<String> = emptyList(),
        val defaultModelId: String? = null,
        val deviceId: String? = null,
        val error: String? = null,
    ) {
        companion object {
            val notReached = SyncResult(ok = false, error = "Gateway unreachable")
        }
    }

    private val _syncState = MutableStateFlow<SyncResult?>(null)
    val syncState: StateFlow<SyncResult?> = _syncState.asStateFlow()

    suspend fun sync(): SyncResult {
        val identity = try {
            client.ensureDevice()
        } catch (e: Exception) {
            Log.w(TAG, "Device registration failed: ${e.message}")
            val r = SyncResult.notReached
            _syncState.value = r
            return r
        }

        val modelsJSON = try {
            client.listModels()
        } catch (e: Exception) {
            Log.w(TAG, "Model list fetch failed: ${e.message}")
            val r = SyncResult.notReached
            _syncState.value = r
            return r
        }

        val region = identity.region
        val chatEntries = parseModels(modelsJSON, region, "chat")
        val imageEntries = parseModels(modelsJSON, region, "image")
        val videoEntries = parseModels(modelsJSON, region, "video")

        if (chatEntries.isEmpty()) {
            Log.w(TAG, "Gateway model list is empty for region=$region")
            val result = SyncResult(ok = false, deviceId = identity.deviceId, error = "Gateway model list is empty")
            _syncState.value = result
            return result
        }

        val chatModels = chatEntries.map { tier -> LLMModel(tier.id, tier.displayName, PROVIDER_NAME) }
        val imageModels = imageEntries.map { tier ->
            val tierId = if (tier.id.startsWith("image")) tier.id else "image/${tier.id}"
            val displayName = if (tier.displayName.contains("图") || tier.displayName.contains("Image", ignoreCase = true)) {
                tier.displayName
            } else {
                "${tier.displayName} (图片)"
            }
            LLMModel(
                id = tierId,
                displayName = displayName,
                provider = PROVIDER_NAME,
                inputModalities = listOf("text", "image"),
                outputModalities = listOf("image"),
            )
        }
        val videoModels = videoEntries.map { tier ->
            val tierId = if (tier.id.startsWith("video")) tier.id else "video/${tier.id}"
            val displayName = if (tier.displayName.contains("视") || tier.displayName.contains("Video", ignoreCase = true)) {
                tier.displayName
            } else {
                "${tier.displayName} (视频)"
            }
            LLMModel(
                id = tierId,
                displayName = displayName,
                provider = PROVIDER_NAME,
                inputModalities = listOf("text", "image"),
                outputModalities = listOf("video"),
            )
        }
        val allModels = chatModels + imageModels + videoModels

        upsertGatewayInstance()
        repository.saveApiKey(INSTANCE_ID, identity.token)
        repository.replaceEntries(INSTANCE_ID, allModels)
        ensureDefaultModelGroup()
        ensureDefaultAgentLoopMediaModels()

        val defaultModelId = chatEntries.firstOrNull { it.isDefault }?.id
            ?: (if (chatEntries.any { it.id == "auto" }) "auto" else chatEntries.firstOrNull()?.id)

        Log.i(TAG, "Synced: ${chatModels.size} chat, ${imageEntries.size} image, ${videoEntries.size} video, region=$region")

        val result = SyncResult(
            ok = true,
            models = chatModels.map { it.id },
            imageModels = imageEntries.map { it.id },
            videoModels = videoEntries.map { it.id },
            defaultModelId = defaultModelId,
            deviceId = identity.deviceId,
        )
        _syncState.value = result
        return result
    }

    private fun upsertGatewayInstance() {
        val existing = repository.config.value.instances
            .firstOrNull { it.id == INSTANCE_ID }

        if (existing != null) {
            if (existing.customBaseURL != client.baseURL) {
                existing.customBaseURL = client.baseURL
                repository.updateInstance(existing)
            }
            return
        }

        val instance = ProviderInstance(
            id = INSTANCE_ID,
            label = PROVIDER_LABEL,
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = client.baseURL,
        )
        repository.addInstance(instance)
    }

    /**
     * First-install experience: when the user has no model groups yet, auto-create
     * a "Default Models" group seeded with the gateway's preferred chat tiers (capped
     * at 3: auto / office / role — the 4th tier "coding" stays available but unchecked)
     * and set it as the default primary group. The user can enter a conversation
     * directly without opening model selection.
     *
     * Also migrates existing "Default Models" groups if they contain the "coding" tier
     * and are missing "role" or "office".
     *
     * No-op when any group already exists, so users who configured models
     * manually are never touched. Mirrors iOS GatewaySync.
     */
    private fun ensureDefaultModelGroup() {
        val cfg = repository.config.value
        val gatewayEntries = cfg.modelEntries.filter { it.providerInstanceId == INSTANCE_ID && !it.isHidden }

        val existingGroup = cfg.modelGroups.firstOrNull { it.name == "Default Models" }
        if (existingGroup != null) {
            val codingEntry = gatewayEntries.firstOrNull { isCodingTier(it) }
            val roleEntry = gatewayEntries.firstOrNull { isRoleTier(it) }
            val officeEntry = gatewayEntries.firstOrNull { isOfficeTier(it) }
            val targetReplacement = when {
                roleEntry != null && !existingGroup.memberEntryIds.contains(roleEntry.id) -> roleEntry
                officeEntry != null && !existingGroup.memberEntryIds.contains(officeEntry.id) -> officeEntry
                else -> null
            }
            if (codingEntry != null && targetReplacement != null &&
                existingGroup.memberEntryIds.contains(codingEntry.id)
            ) {
                val updatedIds = existingGroup.memberEntryIds.map { id ->
                    if (id == codingEntry.id) targetReplacement.id else id
                }
                val updatedGroup = existingGroup.copy(memberEntryIds = updatedIds.toMutableList())
                repository.updateGroup(updatedGroup)
                Log.i(TAG, "Migrated Default Models group: replaced coding tier with ${targetReplacement.baseModel.id} tier")
            }
            return
        }

        if (cfg.modelGroups.isNotEmpty()) return

        val entryIds = defaultGatewayTierEntryIds(gatewayEntries)
        if (entryIds.isEmpty()) return

        val group = ModelGroup(name = "Default Models")
        group.memberEntryIds.addAll(entryIds)
        repository.addGroup(group)
        if (cfg.defaultPrimaryGroupId == null) {
            repository.defaultPrimaryGroupId = group.id
        }
        Log.i(TAG, "Auto-created Default Models group with ${entryIds.size} gateway tier(s)")
    }

    /**
     * Seeds gateway image and video models into the Agent Loop usable models list
     * (Settings > Model Groups > Usable Models in Agent Loop) on initial setup.
     * Uses a preference flag so if the user explicitly unpins/removes them later,
     * subsequent syncs won't re-add them.
     */
    private fun ensureDefaultAgentLoopMediaModels() {
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
        if (prefs?.getBoolean(KEY_AGENT_LOOP_MEDIA_SEEDED, false) == true) return

        val cfg = repository.config.value
        val gatewayMediaEntries = cfg.modelEntries.filter { entry ->
            entry.providerInstanceId == INSTANCE_ID && !entry.isHidden &&
                (entry.model.outputModalities?.contains("image") == true ||
                 entry.model.outputModalities?.contains("video") == true)
        }
        if (gatewayMediaEntries.isEmpty()) return

        for (entry in gatewayMediaEntries) {
            repository.addAgentLoopEntry(entry.id)
        }
        prefs?.edit()?.putBoolean(KEY_AGENT_LOOP_MEDIA_SEEDED, true)?.apply()
        Log.i(TAG, "Seeded ${gatewayMediaEntries.size} gateway media model(s) into Agent Loop usable set")
    }
}
