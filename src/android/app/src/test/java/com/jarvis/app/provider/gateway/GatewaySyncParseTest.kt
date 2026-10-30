package com.jarvis.app.provider.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单池后 /v1/models 只返回扁平 data[]（无 pools / region）。
 * 这些用例锁定解析行为：按 kind 分流、同 id 去重、kind 缺省视为 chat、
 * 忽略旧网关残留的 pools 字段。
 */
class GatewaySyncParseTest {

    private fun json(input: String) = JSONObject(input)

    @Test
    fun `parses chat image video from flat data array`() {
        val payload = json(
            """
            {"object":"list","data":[
              {"id":"auto","kind":"chat","display_name":"Jarvis (Default)","is_default":true},
              {"id":"image","kind":"image","display_name":"Images"},
              {"id":"video","kind":"video","display_name":"Videos"}
            ]}
            """.trimIndent()
        )

        assertEquals(listOf("auto"), GatewaySync.parseModels(payload, "chat").map { it.id })
        assertEquals(listOf("image"), GatewaySync.parseModels(payload, "image").map { it.id })
        assertEquals(listOf("video"), GatewaySync.parseModels(payload, "video").map { it.id })
        assertTrue(GatewaySync.parseModels(payload, "chat").first().isDefault)
    }

    @Test
    fun `treats missing kind as chat`() {
        val payload = json("""{"data":[{"id":"auto","display_name":"A"}]}""")

        assertEquals(listOf("auto"), GatewaySync.parseModels(payload, "chat").map { it.id })
        assertTrue(GatewaySync.parseModels(payload, "image").isEmpty())
    }

    @Test
    fun `deduplicates repeated ids`() {
        val payload = json(
            """{"data":[
              {"id":"auto","kind":"chat"},
              {"id":"auto","kind":"chat"},
              {"id":"office","kind":"chat"}
            ]}"""
        )

        assertEquals(listOf("auto", "office"), GatewaySync.parseModels(payload, "chat").map { it.id })
    }

    @Test
    fun `ignores legacy pools key`() {
        val payload = json(
            """{"pools":{"cn":{"chat":[{"id":"auto"}]}},"data":[{"id":"office","kind":"chat"}]}"""
        )

        // 只读 data[]，不再读 pools
        assertEquals(listOf("office"), GatewaySync.parseModels(payload, "chat").map { it.id })
    }

    @Test
    fun `parses context budget from flat data`() {
        val payload = json(
            """{"data":[
              {"id":"auto","kind":"chat","context_window":200000,"max_output_tokens":32000},
              {"id":"office","kind":"chat"}
            ]}"""
        )

        val tiers = GatewaySync.parseModels(payload, "chat")
        val auto = tiers.first { it.id == "auto" }
        val office = tiers.first { it.id == "office" }

        assertEquals(200000, auto.contextWindow)
        assertEquals(32000, auto.maxOutputTokens)
        // 未声明 → null，由 LLMModel 侧兜底
        assertNull(office.contextWindow)
        assertNull(office.maxOutputTokens)
    }

    @Test
    fun `returns empty when data missing`() {
        assertTrue(GatewaySync.parseModels(json("""{"object":"list"}"""), "chat").isEmpty())
    }
}