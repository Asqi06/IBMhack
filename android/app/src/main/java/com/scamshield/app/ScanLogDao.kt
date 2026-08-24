package com.scamshield.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanLogDao {
    @Insert
    suspend fun insert(log: ScanLog): Long

    @Query("SELECT * FROM scan_logs ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<ScanLog>>

    @Query("SELECT * FROM scan_logs ORDER BY timestamp DESC")
    suspend fun getAll(): List<ScanLog>

    @Query("SELECT * FROM scan_logs WHERE overallRisk IN ('high','medium') ORDER BY timestamp DESC LIMIT 50")
    suspend fun getDangerous(): List<ScanLog>

    @Query("UPDATE scan_logs SET advisedAction = :action WHERE id = :id")
    suspend fun updateAction(id: Long, action: String)

    @Query("DELETE FROM scan_logs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM scan_logs")
    suspend fun clearAll()
}
