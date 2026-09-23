package com.mytracksapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackingSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: TrackingSessionEntity)

    @Update
    suspend fun update(session: TrackingSessionEntity)

    @Query("SELECT * FROM tracking_sessions WHERE id = :sessionId")
    fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?>

    @Query("SELECT * FROM tracking_sessions ORDER BY startTimestamp DESC")
    fun getAllSessions(): Flow<List<TrackingSessionEntity>>

    @Query("SELECT * FROM tracking_sessions WHERE status = :status ORDER BY startTimestamp DESC")
    fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>>

    /** Cascades to delete every [com.mytracksapp.data.local.entity.GpsPointEntity] of this session too (FK `onDelete = CASCADE`). */
    @Query("DELETE FROM tracking_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String)
}
