package com.jarvis.app.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-role-memory-isolation] JVM tests for方案 B: daily logs are scoped per
 * role, GLOBAL.md is shared. Runs under `unitTests.isReturnDefaultValues`.
 */
class MemoryRepositoryRoleIsolationTest {

    private lateinit var root: File
    private lateinit var repo: MemoryRepository

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mem-role-iso").toFile()
        repo = MemoryRepository(root)
    }

    @After
    fun tearDown() { root.deleteRecursively() }

    @Test
    fun `writeMemory lands in the role's per-role dir, not the global root`() {
        repo.writeMemory("note for Jarvis", roleId = "default")
        repo.writeMemory("note for Nova", roleId = "nova")
        val rootMds = root.listFiles { _, n -> n.endsWith(".md") && n != "GLOBAL.md" }
            ?: emptyArray<File>()
        assertTrue("legacy daily logs leaked to global root", rootMds.isEmpty())
        val today = todayFileName()
        val jarvisLog = File(root, "roles/default/memory/$today")
        val novaLog = File(root, "roles/nova/memory/$today")
        assertTrue(jarvisLog.exists())
        assertTrue(novaLog.exists())
        assertTrue(jarvisLog.readText().contains("note for Jarvis"))
        assertTrue(novaLog.readText().contains("note for Nova"))
        assertFalse(jarvisLog.readText().contains("note for Nova"))
    }

    @Test
    fun `loadRecentDailyMemoryFragment only reads the given role's logs`() {
        repo.writeMemory("Jarvis-only fact", roleId = "default")
        repo.writeMemory("Nova-only fact", roleId = "nova")
        val j = repo.loadRecentDailyMemoryFragment("default")
        val n = repo.loadRecentDailyMemoryFragment("nova")
        assertNotNull(j); assertNotNull(n)
        assertTrue(j!!.contains("Jarvis-only fact"))
        assertFalse(j.contains("Nova-only fact"))
        assertTrue(n!!.contains("Nova-only fact"))
        assertFalse(n.contains("Jarvis-only fact"))
    }


    @Test
    fun `getMemory searches only the active role's logs plus shared GLOBAL`() {
        repo.saveGlobalMd("shared global instruction")
        repo.writeMemory("Jarvis secret", roleId = "default")
        repo.writeMemory("Nova secret", roleId = "nova")
        val ja = repo.getMemory("", "all", "default")
        assertTrue(ja.contains("shared global instruction"))
        assertTrue(ja.contains("Jarvis secret"))
        assertFalse(ja.contains("Nova secret"))
        val na = repo.getMemory("", "all", "nova")
        assertTrue(na.contains("Nova secret"))
        assertFalse(na.contains("Jarvis secret"))
        assertTrue(na.contains("shared global instruction"))
        val jd = repo.getMemory("", "daily", "default")
        assertFalse(jd.contains("shared global instruction"))
        assertTrue(jd.contains("Jarvis secret"))
    }

    @Test
    fun `null roleId falls back to the default role`() {
        repo.writeMemory("legacy-path note", roleId = null)
        val today = todayFileName()
        assertTrue(File(root, "roles/default/memory/$today").exists())
        val frag = repo.loadRecentDailyMemoryFragment(null)
        assertNotNull(frag)
        assertTrue(frag!!.contains("legacy-path note"))
    }

    @Test
    fun `legacy daily logs are migrated into the default role once and idempotently`() {
        val legacyLog = File(root, todayFileName())
        legacyLog.writeText("old global memory entry")
        File(root, ".legacy-memory-migrated").delete()
        MemoryRepository(root) // re-construct → triggers migration
        assertTrue("legacy log should have moved out of the global root", !legacyLog.exists())
        val migrated = File(root, "roles/default/memory/${legacyLog.name}")
        assertTrue(migrated.exists())
        assertEquals("old global memory entry", migrated.readText())
        MemoryRepository(root) // second construction must not move anything again
        assertTrue(migrated.exists())
        assertEquals("old global memory entry", migrated.readText())
    }

    @Test
    fun `GLOBAL dot md stays shared and is never role-scoped`() {
        repo.saveGlobalMd("global")
        repo.saveFile("GLOBAL.md", "global-via-saveFile", roleId = "nova")
        assertEquals("global-via-saveFile", repo.readFile("GLOBAL.md", roleId = "nova"))
        assertEquals("global-via-saveFile", repo.readFile("GLOBAL.md", roleId = "default"))
        assertFalse(repo.deleteFile("GLOBAL.md", roleId = "nova"))
    }

    @Test
    fun `SOUL dot md and other non-date files stay in the shared global root`() {
        repo.saveFile("SOUL.md", "soul body", roleId = "nova")
        assertTrue(File(root, "SOUL.md").exists())
        assertFalse(File(root, "roles/nova/memory/SOUL.md").exists())
        assertEquals("soul body", repo.readFile("SOUL.md", roleId = "nova"))
        assertEquals("soul body", repo.readFile("SOUL.md", roleId = "default"))
    }

    @Test
    fun `revokeEntry is scoped to the active role and never touches another role`() {
        repo.writeMemory("ephemeral note", roleId = "default")
        repo.writeMemory("ephemeral note", roleId = "nova")
        val result = repo.revokeEntry("ephemeral note", "default")
        assertTrue(result is MemoryRepository.EntryMutationResult.Success)
        val novaFrag = repo.loadRecentDailyMemoryFragment("nova")
        assertNotNull(novaFrag)
        assertTrue(novaFrag!!.contains("ephemeral note"))
        val defaultFrag = repo.loadRecentDailyMemoryFragment("default")
        if (defaultFrag != null) assertFalse(defaultFrag.contains("ephemeral note"))
    }

    @Test
    fun `null fragment when no logs exist for the role but GLOBAL stays loadable`() {
        repo.saveGlobalMd("only global")
        assertNull(repo.loadRecentDailyMemoryFragment("ghost-role"))
        assertNotNull(repo.loadGlobalMemoryFragment())
    }

    private fun todayFileName(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return "${fmt.format(java.util.Date())}.md"
    }
}
