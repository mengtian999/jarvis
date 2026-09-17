package com.jarvis.app.ui.chat

import com.jarvis.app.agent.RoleStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-role-forward] / [T-role-session-bound] JVM tests for the pure parts of
 * the role-refactor: forward-text extraction, draft-id role encoding, and
 * the render-time role resolver's fallback chain.
 *
 * RoleStore.loadDefault() is exercised here too — under
 * `unitTests.isReturnDefaultValues` the avatar-bit build degrades to null
 * via its catch(Throwable) guard, which is exactly the startup-crash
 * defense the P0 fix added; the metadata must survive regardless.
 */
class RoleForwardLogicTest {

    // ─── forwardExtractText ───────────────────────────────────────────────

    @Test
    fun `forwardExtractText concatenates text parts with blank-line join`() {
        val partsJson = """
            [{"type":"text","value":"first"},{"type":"text","value":"second"}]
        """.trimIndent()
        assertEquals("first\n\nsecond", forwardExtractText(partsJson))
    }

    @Test
    fun `forwardExtractText appends media note when images present`() {
        val partsJson = """
            [{"type":"text","value":"look at this"},{"type":"mediaRef","value":"img.png"}]
        """.trimIndent()
        assertEquals("look at this\n\n[图片未转发]", forwardExtractText(partsJson))
    }

    @Test
    fun `forwardExtractText skips toolUse parts silently`() {
        val partsJson = """
            [{"type":"toolUse","value":{"name":"file_read","input":"{}"}},
             {"type":"text","value":"the answer"}]
        """.trimIndent()
        assertEquals("the answer", forwardExtractText(partsJson))
    }

    @Test
    fun `forwardExtractText blank when no text parts`() {
        val partsJson = """[{"type":"toolResult","value":{"output":"..."}}]"""
        assertEquals("", forwardExtractText(partsJson))
    }

    @Test
    fun `forwardExtractText passes malformed json through as-is`() {
        assertEquals("not json at all", forwardExtractText("not json at all"))
    }

    // ─── draftInitialRoleId (draft-id __role__ encoding) ──────────────────

    @Test
    fun `draftInitialRoleId parses role segment`() {
        assertEquals(
            "architect",
            draftInitialRoleId("__new__abc-123__role__architect"),
        )
    }

    @Test
    fun `draftInitialRoleId null for plain draft`() {
        assertNull(draftInitialRoleId("__new__abc-123"))
    }

    @Test
    fun `draftInitialRoleId composes with group and folder segments`() {
        // createNewSession appends __grp__, __fld__, __role__ in that order.
        assertEquals(
            "reviewer",
            draftInitialRoleId("__new__abc__grp__g1__fld__f1__role__reviewer"),
        )
        // And stays correct when role comes before the other markers.
        assertEquals(
            "reviewer",
            draftInitialRoleId("__new__abc__role__reviewer__grp__g1"),
        )
    }

    @Test
    fun `draftInitialRoleId null when segment empty`() {
        assertNull(draftInitialRoleId("__new__abc__role__"))
    }

    // ─── RoleStore.resolveRole fallback chain ─────────────────────────────

    @Test
    fun `resolveRole null roleId falls back to default`() {
        val role = RoleStore.resolveRole(null)
        assertEquals("Jarvis", role.metadata.name)
        assertEquals("default", role.roleId)
    }

    @Test
    fun `resolveRole unknown roleId falls back to default on cold snapshot`() {
        // JVM tests never prime() the store, so the snapshot is empty —
        // exactly the orphan-ref (role deleted) path renderers must survive.
        val role = RoleStore.resolveRole("no-such-role")
        assertEquals("Jarvis", role.metadata.name)
    }

    @Test
    fun `roleById misses on empty snapshot without throwing`() {
        assertNull(RoleStore.roleById("no-such-role"))
    }

    @Test
    fun `loadDefault survives bitmap-less JVM environment`() {
        // returnDefaultValues makes android.graphics calls return null/0;
        // the P0 catch(Throwable) guard must degrade the avatar to null
        // instead of throwing ExceptionInInitializerError.
        val role = RoleStore.loadDefault()
        assertEquals("Jarvis", role.metadata.name)
        assertEquals("default", role.roleId)
    }
}
