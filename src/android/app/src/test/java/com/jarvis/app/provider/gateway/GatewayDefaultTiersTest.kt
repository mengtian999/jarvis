package com.jarvis.app.provider.gateway

import com.jarvis.app.data.model.LLMModel
import com.jarvis.app.data.model.ModelEntry
import com.jarvis.app.data.model.ModelGroup
import com.jarvis.app.data.model.ProviderConfig
import com.jarvis.app.data.model.ProviderInstance
import com.jarvis.app.data.model.ProviderCredential
import com.jarvis.app.data.model.ProviderType
import com.jarvis.app.ui.onboarding.initialModelPreselections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayDefaultTiersTest {

    private fun makeEntry(id: String, displayName: String): ModelEntry {
        return ModelEntry(
            providerInstanceId = GatewaySync.INSTANCE_ID,
            baseModel = LLMModel(id = id, displayName = displayName, provider = GatewaySync.PROVIDER_NAME),
        )
    }

    @Test
    fun `default tiers prioritize auto, office, role and exclude coding`() {
        val auto = makeEntry("auto", "贾维斯（通用）")
        val office = makeEntry("office", "办公友好")
        val coding = makeEntry("coding", "代码友好")
        val role = makeEntry("role", "角色友好")

        val entries = listOf(auto, office, coding, role)
        val selectedIds = GatewaySync.defaultGatewayTierEntryIds(entries)

        assertEquals(3, selectedIds.size)
        assertEquals(listOf(auto.id, office.id, role.id), selectedIds)
        assertFalse("Coding must not be in default tiers", selectedIds.contains(coding.id))
        assertTrue("Auto must be in default tiers", selectedIds.contains(auto.id))
        assertTrue("Office must be in default tiers", selectedIds.contains(office.id))
        assertTrue("Role must be in default tiers", selectedIds.contains(role.id))
    }

    @Test
    fun `initialModelPreselections migrates coding to role for existing Default Models group`() {
        val auto = makeEntry("auto", "贾维斯（通用）")
        val office = makeEntry("office", "办公友好")
        val coding = makeEntry("coding", "代码友好")
        val role = makeEntry("role", "角色友好")

        val instance = ProviderInstance(
            id = GatewaySync.INSTANCE_ID,
            label = GatewaySync.PROVIDER_LABEL,
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
        )

        // Existing group had auto, office, coding (missing role)
        val group = ModelGroup(
            name = "Default Models",
            memberEntryIds = mutableListOf(auto.id, office.id, coding.id),
        )

        val config = ProviderConfig(
            instances = mutableListOf(instance),
            modelEntries = mutableListOf(auto, office, coding, role),
            modelGroups = mutableListOf(group),
        )

        val selectedIds = initialModelPreselections(config)

        assertEquals(3, selectedIds.size)
        assertEquals(listOf(auto.id, office.id, role.id), selectedIds)
        assertFalse("Coding must be replaced", selectedIds.contains(coding.id))
        assertTrue(selectedIds.contains(role.id))
    }

    @Test
    fun `initialModelPreselections migrates coding to office if group had auto, role, coding`() {
        val auto = makeEntry("auto", "贾维斯（通用）")
        val office = makeEntry("office", "办公友好")
        val coding = makeEntry("coding", "代码友好")
        val role = makeEntry("role", "角色友好")

        val instance = ProviderInstance(
            id = GatewaySync.INSTANCE_ID,
            label = GatewaySync.PROVIDER_LABEL,
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
        )

        // Group had auto, role, coding (missing office)
        val group = ModelGroup(
            name = "Default Models",
            memberEntryIds = mutableListOf(auto.id, role.id, coding.id),
        )

        val config = ProviderConfig(
            instances = mutableListOf(instance),
            modelEntries = mutableListOf(auto, office, coding, role),
            modelGroups = mutableListOf(group),
        )

        val selectedIds = initialModelPreselections(config)

        assertEquals(3, selectedIds.size)
        assertEquals(listOf(auto.id, role.id, office.id), selectedIds)
        assertFalse("Coding must be replaced", selectedIds.contains(coding.id))
        assertTrue(selectedIds.contains(office.id))
    }

    @Test
    fun `default tiers ignore image and video media models`() {
        val auto = makeEntry("auto", "贾维斯（通用）")
        val office = makeEntry("office", "办公友好")
        val role = makeEntry("role", "角色友好")
        val image = ModelEntry(
            providerInstanceId = GatewaySync.INSTANCE_ID,
            baseModel = LLMModel(
                id = "image/standard",
                displayName = "标准图片 (图片)",
                provider = GatewaySync.PROVIDER_NAME,
                outputModalities = listOf("image"),
            ),
        )
        val video = ModelEntry(
            providerInstanceId = GatewaySync.INSTANCE_ID,
            baseModel = LLMModel(
                id = "video/standard",
                displayName = "标准视频 (视频)",
                provider = GatewaySync.PROVIDER_NAME,
                outputModalities = listOf("video"),
            ),
        )

        val entries = listOf(image, auto, video, office, role)
        val selectedIds = GatewaySync.defaultGatewayTierEntryIds(entries)

        assertEquals(3, selectedIds.size)
        assertEquals(listOf(auto.id, office.id, role.id), selectedIds)
        assertFalse(selectedIds.contains(image.id))
        assertFalse(selectedIds.contains(video.id))
    }

    @Test
    fun `parseModels extracts image and video tiers from pools structure`() {
        val raw = """
        {
          "pools": {
            "cn": {
              "chat": [{"id": "auto", "display_name": "贾维斯", "is_default": true}],
              "image": [{"id": "standard", "display_name": "标准图片", "is_default": true}],
              "video": [{"id": "standard", "display_name": "标准视频", "is_default": true}]
            }
          }
        }
        """.trimIndent()
        val json = org.json.JSONObject(raw)

        val chatTiers = GatewaySync.parseModels(json, "cn", "chat")
        val imageTiers = GatewaySync.parseModels(json, "cn", "image")
        val videoTiers = GatewaySync.parseModels(json, "cn", "video")

        assertEquals(1, chatTiers.size)
        assertEquals("auto", chatTiers[0].id)
        assertEquals(1, imageTiers.size)
        assertEquals("standard", imageTiers[0].id)
        assertEquals("标准图片", imageTiers[0].displayName)
        assertEquals(1, videoTiers.size)
        assertEquals("standard", videoTiers[0].id)
        assertEquals("标准视频", videoTiers[0].displayName)
    }

    @Test
    fun `parseModels extracts image and video tiers from flat data structure`() {
        val raw = """
        {
          "data": [
            {"id": "auto", "display_name": "通用", "kind": "chat", "region": "cn"},
            {"id": "standard", "display_name": "标准图", "kind": "image", "region": "cn"},
            {"id": "standard", "display_name": "标准视", "kind": "video", "region": "cn"}
          ]
        }
        """.trimIndent()
        val json = org.json.JSONObject(raw)

        val imageTiers = GatewaySync.parseModels(json, "cn", "image")
        val videoTiers = GatewaySync.parseModels(json, "cn", "video")

        assertEquals(1, imageTiers.size)
        assertEquals("standard", imageTiers[0].id)
        assertEquals("标准图", imageTiers[0].displayName)
        assertEquals(1, videoTiers.size)
        assertEquals("standard", videoTiers[0].id)
        assertEquals("标准视", videoTiers[0].displayName)
    }

    @Test
    fun `sync result holds image and video model ids`() {
        val result = GatewaySync.SyncResult(
            ok = true,
            models = listOf("auto", "office", "role", "coding"),
            imageModels = listOf("standard"),
            videoModels = listOf("standard"),
            defaultModelId = "auto",
            deviceId = "dev-test",
        )
        assertTrue(result.ok)
        assertEquals(listOf("auto", "office", "role", "coding"), result.models)
        assertEquals(listOf("standard"), result.imageModels)
        assertEquals(listOf("standard"), result.videoModels)
    }
}
