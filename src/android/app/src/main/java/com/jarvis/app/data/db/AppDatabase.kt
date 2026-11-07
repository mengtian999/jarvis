package com.jarvis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        MessageEntity::class,
        CompactMarkerEntity::class,
        WebAppShortcutEntity::class,
        FolderEntity::class,
        AgentGroupEntity::class,
    ],
    // [T-role-reset] v1 is the canonical post-role-refactor baseline (role_id
    // columns are baseline columns; the pre-refactor chain was dropped with a
    // destructive reset). [T-group-chat] From v2 on, schema changes MUST go
    // through explicit migrations — see MIGRATION_1_2 below and the committed
    // schemas/ JSONs that MigrationTestHelper replays.
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun webAppShortcutDao(): WebAppShortcutDao
    abstract fun agentGroupDao(): AgentGroupDao

    companion object {
        /**
         * [T-group-chat] v1 → v2: agent_groups table + sessions.group_id.
         * Pure ADD TABLE + ADD COLUMN — every v1 row survives untouched, and
         * a v1 build opening a v2 DB still reads/writes everything it knows
         * (it just fails schema validation on the way in, which
         * DatabaseVersionGuard turns into the guidance screen instead of a
         * destructive fallback).
         *
         * The CREATE statements below are verified against the exported
         * schemas/1.json→2.json by AppDatabaseMigrationTest (MigrationTestHelper
         * replays the real SQL and Room validates the result), so they cannot
         * drift from the entity definitions.
         */
        // Visible to the instrumented migration test so it can replay the
        // exact production SQL (not a copy) through MigrationTestHelper.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS agent_groups (" +
                        "id TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "instruction TEXT, " +
                        "members_json TEXT NOT NULL, " +
                        "host_role_id TEXT, " +
                        "model_override_json TEXT, " +
                        "max_rounds INTEGER NOT NULL, " +
                        "created_at INTEGER NOT NULL, " +
                        "updated_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))",
                )
                // ALTER first — the index below references the column, and
                // SQLite compiles statements eagerly: index-then-add fails
                // with "no such column: group_id" (caught on device by
                // AppDatabaseMigrationTest).
                db.execSQL("ALTER TABLE sessions ADD COLUMN group_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_group_id ON sessions(group_id)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jarvis.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    // Last-resort backstop only: with MIGRATION_1_2 registered
                    // the normal upgrade path never lands here. A wipe from
                    // this fallback means an unregistered version jump — a bug
                    // to fix with a migration, not behavior to rely on.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
