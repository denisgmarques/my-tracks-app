package com.mytracksapp.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a single GPS point collected during a tracking session (RF-02).
 *
 * Fields cover the raw facts RF-02/RNF-03 require to be recorded at the moment of collection:
 * position ([latitude]/[longitude]), [timestamp] (the real observed time, not the configured
 * one), [accuracy] as reported by the location provider, and
 * [observedIntervalDriftMillis] — the difference between the real interval observed since the
 * previous point and the session's configured sampling interval, per RNF-03 ("desvio observado
 * ... registrado junto ao ponto"). It is null for a session's first point, which has no
 * predecessor to diff against.
 *
 * Derived-per-point values that other tasks compute FROM this raw data (instant speed for
 * RF-05/CSV `speed_instant`, moving/stopped classification for RF-06/CSV `segment_status`) are
 * deliberately not stored here: PLAN.md > Assumptions records that classification is computed
 * on demand from the point stream by StatsEngine/SegmentClassifier (T07), and the CSV schema
 * (csv-export-schema.json) describes `speed_instant` as "calculada entre este ponto e o ponto
 * anterior" at export/read time, not as a stored raw fact. Storing them here would duplicate
 * state that must always be recomputed anyway (e.g. re-running SegmentClassifier over an
 * amended point set) and risks the persisted value drifting from the recomputed one.
 */
@Entity(
    tableName = "gps_points",
    foreignKeys = [
        ForeignKey(
            entity = TrackingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class GpsPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val sessionId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val observedIntervalDriftMillis: Long? = null,
)
