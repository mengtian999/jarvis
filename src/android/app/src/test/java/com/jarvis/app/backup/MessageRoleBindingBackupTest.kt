package com.jarvis.app.backup

import com.jarvis.app.data.db.MessageEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-role-message-level] The message↔persona binding must survive a backup.
 *
 * The bug this pins: `messageRecord` wrote every column EXCEPT `roleId`, and
 * `BackupImporter` omitted `roleId` when inserting `MessageEntity`. A restore
 * rebuilt every assistant row with `role_id = NULL`. When entering a chat,
 * bubbles resolved a null roleId and fell back to the default persona (Jarvis),
 * even though the session list and top bar had the correct role.
 *
 * iOS RawMessage is Codable and carries `roleId`, so the key name is the
 * cross-platform contract, asserted literally here.
 */
class MessageRoleBindingBackupTest {

    private fun JsonElement.str(key: String): String? =
        (this as? JsonObject)?.get(key)
            ?.takeIf { it !is JsonNull }
            ?.runCatching { jsonPrimitive.content }
            ?.getOrNull()

    private fun message(role: String, roleId: String?) = MessageEntity(
        id = "11111111-2222-3333-4444-555555555555",
        sessionId = "6F1A2B3C-0000-0000-0000-00000000ABCD",
        role = role,
        partsJson = """[{"type":"text","value":"你好，我是专属助手"}]""",
        createdAt = 1_755_000_000_000L,
        sortOrder = 1,
        roleId = roleId,
        modelId = "claude-sonnet-5",
    )

    @Test
    fun `an assistant message keeps its roleId in the package`() {
        val record = BackupExporter.messageRecord(message("assistant", "custom-role"))
        assertEquals("custom-role", record.str("roleId"))
    }

    @Test
    fun `an assistant message without roleId falls back to sessionRoleId`() {
        val record = BackupExporter.messageRecord(message("assistant", null), sessionRoleId = "custom-role")
        assertEquals("custom-role", record.str("roleId"))
    }

    @Test
    fun `a user message never carries roleId even if sessionRoleId is passed`() {
        val record = BackupExporter.messageRecord(message("user", null), sessionRoleId = "custom-role")
        assertTrue("roleId must be present on the record", (record as JsonObject).containsKey("roleId"))
        assertNull(record.str("roleId"))
    }

    @Test
    fun `an assistant message without roleId and without sessionRoleId writes null`() {
        val record = BackupExporter.messageRecord(message("assistant", null), sessionRoleId = null)
        assertTrue("roleId must be present on the record", (record as JsonObject).containsKey("roleId"))
        assertNull(record.str("roleId"))
    }

    @Test
    fun `the rest of the message record is unchanged`() {
        val record = BackupExporter.messageRecord(message("assistant", "custom-role"))
        assertEquals("11111111-2222-3333-4444-555555555555", record.str("id"))
        assertEquals("6F1A2B3C-0000-0000-0000-00000000ABCD", record.str("sessionId"))
        assertEquals("assistant", record.str("role"))
        assertEquals("2025-08-12T12:00:00Z", record.str("createdAt"))
        assertEquals("claude-sonnet-5", record.str("modelId"))
    }
}
