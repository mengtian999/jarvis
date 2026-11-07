package com.jarvis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * [T-group-chat] CRUD for [AgentGroupEntity]. Session-side group queries
 * (sessionsInGroup / deleteSessionsInGroup) live on ChatDao next to the
 * folder membership queries, so each DAO owns exactly one table.
 *
 * REPLACE upsert keeps GroupStore's update path single-statement; group rows
 * carry no local-only state that a blind replace could lose (updated_at is
 * supplied by the caller, never read-modify-written here).
 */
@Dao
interface AgentGroupDao {

    /** Blank-id rows are skipped, same defensive rule as observeFolders(). */
    @Query("SELECT * FROM agent_groups WHERE id != '' ORDER BY updated_at DESC")
    fun observeGroups(): Flow<List<AgentGroupEntity>>

    @Query("SELECT * FROM agent_groups WHERE id != '' ORDER BY updated_at DESC")
    suspend fun listGroups(): List<AgentGroupEntity>

    @Query("SELECT * FROM agent_groups WHERE id = :id")
    suspend fun getGroup(id: String): AgentGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: AgentGroupEntity)

    @Query("DELETE FROM agent_groups WHERE id = :id")
    suspend fun deleteGroup(id: String)
}
