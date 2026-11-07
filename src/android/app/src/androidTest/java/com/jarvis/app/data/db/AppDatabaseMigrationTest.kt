package com.jarvis.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [T-group-chat] Replays the real production migration SQL (AppDatabase.
 * MIGRATION_1_2 — imported, not copied) against a database created from the
 * committed schemas/1.json, then lets Room validate the result against the
 * expected 2.json entity shape. This is the check that the hand-written
 * CREATE TABLE / ADD COLUMN statements cannot drift from the @Entity
 * definitions: a wrong column name, type affinity, NOT NULL, index or a
 * missing table fails runMigrationsAndValidate with a schema-mismatch
 * IllegalStateException naming the offending definition.
 *
 * Needs an instrumented run (schemas ship as test assets):
 * `./gradlew :app:connectedDebugAndroidTest`
 */
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To2_v1SessionRowsSurviveUntouched() {
        helper.createDatabase(TEST_DB, 1).use { v1 ->
            // Minimal v1-shaped session row (only NN columns + the ones this
            // test reads). NOT NULL columns are listed in schemas/1.json —
            // note stream_interrupt_count is a MESSAGES column, not sessions.
            v1.execSQL(
                "INSERT INTO sessions (id, title, model_id, created_at, updated_at, " +
                    "memory_enabled, edit_count) " +
                    "VALUES ('s1', 'kept', 'm1', 100, 100, 1, 0)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2).use { v2 ->
            v2.query("SELECT group_id, title, model_id FROM sessions WHERE id = 's1'").use { c ->
                assertTrue("the v1 row must survive the migration", c.moveToFirst())
                assertNull("group_id must default to NULL for pre-migration rows", c.getString(0))
                assertEquals("kept", c.getString(1))
                assertEquals("m1", c.getString(2))
            }
        }
    }

    @Test
    fun migrate1To2_agentGroupsTableIsUsable() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2).use { v2 ->
            // The full column set must be writable — this compiles only if
            // every column the entity expects exists with a compatible type.
            v2.execSQL(
                "INSERT INTO agent_groups (id, name, instruction, members_json, " +
                    "host_role_id, model_override_json, max_rounds, created_at, updated_at) " +
                    "VALUES ('g1', '评审团', NULL, '[{\"roleId\":\"r1\"}]', NULL, NULL, 1, 1, 1)",
            )
            v2.execSQL(
                "INSERT INTO sessions (id, title, model_id, created_at, updated_at, " +
                    "memory_enabled, edit_count, group_id, role_id) " +
                    "VALUES ('s2', 'group chat', 'm1', 200, 200, 1, 0, 'g1', NULL)",
            )
            v2.query(
                "SELECT s.id FROM sessions s " +
                    "JOIN agent_groups g ON s.group_id = g.id WHERE g.name = '评审团'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("s2", c.getString(0))
            }
        }
    }

    @Test
    fun freshV2Create_hasGroupColumnAndIndex() {
        // Not a migration test in the strict sense: asserts the v2 identity
        // schema itself (a fresh install) exposes group_id — guarding against
        // the entity and the migration drifting in OPPOSITE directions.
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' " +
                    "AND name='index_sessions_group_id'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getLong(0) > 0)
            }
            db.query("SELECT group_id FROM sessions LIMIT 1").use { c ->
                assertTrue(
                    "group_id must be selectable on a fresh v2 database",
                    c.getColumnIndex("group_id") >= 0,
                )
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test-jarvis.db"
    }
}
