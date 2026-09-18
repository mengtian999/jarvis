package com.jarvis.app.backup

import com.jarvis.app.data.db.ChatSessionEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-role-session-bound] The session↔persona binding must survive a backup.
 *
 * The bug this pins: `sessionRecord` wrote every column EXCEPT `roleId`, so a
 * restore rebuilt every session row with `role_id = NULL`. `RoleStore` renders
 * an unresolved id through its default-role fallback, so the user's whole
 * restored history — every role's conversations — came back named and avatared
 * as Jarvis, while the roles themselves restored correctly (they travel as
 * `roles/<roleId>/…` files, untouched by this path).
 *
 * iOS never had the bug: its `ChatSession` is `Codable`, so `roleId` was
 * already in the synthesized encoding. That makes the key name part of the
 * cross-platform contract, which is why it is asserted literally here.
 */
class SessionRoleBindingBackupTest {

    private fun JsonElement.str(key: String): String? =
        (this as? JsonObject)?.get(key)
            ?.takeIf { it !is JsonNull }
            ?.runCatching { jsonPrimitive.content }
            ?.getOrNull()

    private fun session(roleId: String?) = ChatSessionEntity(
        id = "6F1A2B3C-0000-0000-0000-00000000ABCD",
        title = "体检报告解读",
        modelId = "claude-sonnet-5",
        createdAt = 1_755_000_000_000L,
        updatedAt = 1_755_000_600_000L,
        roleId = roleId,
    )

    @Test
    fun `a bound session keeps its roleId in the package`() {
        val record = BackupExporter.sessionRecord(session("health"))
        // Key name is the contract with iOS's Codable ChatSession.
        assertEquals("health", record.str("roleId"))
    }

    @Test
    fun `the key is present as an explicit null for a legacy row`() {
        val record = BackupExporter.sessionRecord(session(null))
        // Present-but-null, not absent: the importer's
        // `s.str("roleId") ?: existing?.roleId` then keeps a live row's
        // binding instead of having to distinguish "absent" from "no role".
        assertTrue("roleId must be written even when unset", (record as JsonObject).containsKey("roleId"))
        assertNull(record.str("roleId"))
    }

    @Test
    fun `the rest of the record is unchanged`() {
        // Guards the edit that added roleId from having moved or dropped a
        // field the importer reads by name.
        val record = BackupExporter.sessionRecord(session("health"))
        assertEquals("6F1A2B3C-0000-0000-0000-00000000ABCD", record.str("id"))
        assertEquals("体检报告解读", record.str("title"))
        assertEquals("claude-sonnet-5", record.str("modelId"))
        assertEquals("2025-08-12T12:00:00Z", record.str("createdAt"))
        assertEquals("2025-08-12T12:10:00Z", record.str("updatedAt"))
        assertEquals("true", record.str("memoryEnabled"))
    }
}