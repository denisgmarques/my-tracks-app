package com.mytracksapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /**
     * T02 (RF-12, RNF-03) — removes every session, cascading (FK `onDelete = CASCADE`) to remove
     * every [com.mytracksapp.data.local.entity.GpsPointEntity] as well. `@Transaction` guarantees
     * this is all-or-nothing.
     */
    @Transaction
    @Query("DELETE FROM tracking_sessions")
    suspend fun deleteAll()

    /**
     * T02 — updates only the `locationName` column, leaving every other field of the row
     * untouched. Used by [com.mytracksapp.domain.geocoding.FirstPointGeocodingCoordinator] so a
     * background geocoding result never races with [update]'s whole-row write from
     * `SessionControllerImpl.stopSession`.
     */
    @Query("UPDATE tracking_sessions SET locationName = :locationName WHERE id = :sessionId")
    suspend fun updateLocationName(sessionId: String, locationName: String?)

    /**
     * T01 — retry-eligible sessions for [com.mytracksapp.domain.geocoding.GeocodingRetryOnStartup]:
     * finished sessions still missing a [TrackingSessionEntity.locationName] whose
     * [TrackingSessionEntity.startTimestamp] falls within the caller-supplied window (RF-01,
     * RF-04, RF-05). One-shot `suspend` (not `Flow`) since this is a single startup scan, not a
     * live-observed list.
     */
    @Query(
        "SELECT * FROM tracking_sessions WHERE status = :status AND locationName IS NULL AND startTimestamp >= :sinceTimestamp",
    )
    suspend fun getFinishedSessionsWithoutLocationNameSince(
        status: SessionStatus,
        sinceTimestamp: Long,
    ): List<TrackingSessionEntity>
}
