package com.mytracksapp.domain.session

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.stats.SegmentClassifier
import com.mytracksapp.domain.stats.StatsEngine
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * The minimal contract [SessionControllerImpl] needs to start/stop the actual point-collection
 * mechanism (T06's `LocationForegroundService`, Phase 5 of PLAN.md — not implemented yet).
 *
 * Kept deliberately narrow and Android-free (no `Context`, no `Intent`) so:
 *  - [SessionControllerImpl] can be unit tested with a plain in-memory fake, no Robolectric/
 *    instrumentation needed (see `SessionControllerTest`);
 *  - T06 has a small, stable surface to implement against — e.g. a class that calls
 *    `context.startForegroundService(...)`/`context.stopService(...)` under the hood — without
 *    T05 needing to change when T06 lands.
 */
interface LocationServiceController {

    /**
     * Starts collecting GPS points for [sessionId] at [interval]. Called by
     * [SessionControllerImpl.startSession] only AFTER the session row has been persisted
     * (RF-01: "criar uma nova sessão ... antes de iniciar qualquer coleta de ponto GPS"), so a
     * real implementation may safely assume the session already exists in the DB the moment this
     * is invoked.
     */
    fun start(sessionId: String, interval: SamplingInterval)

    /**
     * Stops collecting GPS points for [sessionId]. Must be safe to call even if collection was
     * never started for this id (e.g. defensive double-stop).
     */
    fun stop(sessionId: String)
}

/**
 * Returns whether [seconds] is one of RF-01's 9 allowed sampling-interval values.
 *
 * This is a defense-in-depth check: [SamplingInterval] is a closed enum (see
 * `domain/model/SamplingInterval.kt`), so [SessionController.startSession] can never actually be
 * called with an `interval.seconds` outside [SamplingInterval.ALLOWED_SECONDS] through the public
 * API — the RF-01 acceptance criterion "qualquer valor fora do conjunto ... é rejeitado" is
 * structurally unreachable via the type system. [isAllowedIntervalSeconds] is still exercised by
 * [SessionControllerImpl.startSession] (belt-and-suspenders, in case a future refactor loosens
 * the enum's closure) and is exposed at `internal` visibility purely so `SessionControllerTest`
 * can assert its behavior directly against raw `Int`s that could never reach it via
 * [SamplingInterval].
 */
internal fun isAllowedIntervalSeconds(seconds: Int): Boolean =
    seconds in SamplingInterval.ALLOWED_SECONDS

/**
 * Real implementation of [SessionController] (T05): orchestrates a tracking session's full
 * lifecycle per RF-01/RF-03/RF-07.
 *
 * [isBackgroundLocationGranted] is a function reference rather than the concrete
 * `LocationPermissionManager` (T03) class directly, so this class stays a plain, Android-free
 * unit under test — production wiring passes
 * `locationPermissionManager::isBackgroundLocationGranted`; tests pass a plain lambda double.
 */
class SessionControllerImpl(
    private val trackingSessionDao: TrackingSessionDao,
    private val gpsPointDao: GpsPointDao,
    private val locationServiceController: LocationServiceController,
    private val isBackgroundLocationGranted: () -> Boolean,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SessionController {

    override suspend fun startSession(interval: SamplingInterval): SessionStartOutcome {
        // RF-01 defensive guard — see isAllowedIntervalSeconds' doc for why this can never
        // actually trigger through the public API today.
        if (!isAllowedIntervalSeconds(interval.seconds)) {
            return SessionStartOutcome.PermissionDenied
        }

        // RF-03: no session row is created, and the service is never started, unless background
        // location permission is granted. This check happens strictly before any DB write.
        if (!isBackgroundLocationGranted()) {
            return SessionStartOutcome.PermissionDenied
        }

        val sessionId = idGenerator()
        val session = TrackingSessionEntity(
            id = sessionId,
            samplingIntervalSeconds = interval.seconds,
            startTimestamp = clock(),
            status = SessionStatus.ACTIVE,
        )
        // RF-01: the session is persisted BEFORE collection starts.
        trackingSessionDao.insert(session)
        locationServiceController.start(sessionId, interval)

        return SessionStartOutcome.Started(sessionId)
    }

    override suspend fun stopSession(sessionId: String) {
        // Stop collection first so the point set read below is the final one for this session.
        locationServiceController.stop(sessionId)

        val existing = trackingSessionDao.getSessionById(sessionId).first() ?: return
        val points = gpsPointDao.getPointsForSession(sessionId).first()

        val classification = SegmentClassifier.classify(points)
        val averageSpeed = StatsEngine.averageSpeedMetersPerSecond(points)
        val distanceMeters = StatsEngine.totalDistanceMeters(points)
        val endTimestamp = if (points.isNotEmpty()) points.last().timestamp else clock()

        trackingSessionDao.update(
            existing.copy(
                endTimestamp = endTimestamp,
                status = SessionStatus.FINISHED,
                stoppedTimeMillis = classification.stoppedTimeMillis,
                movingTimeMillis = classification.movingTimeMillis,
                averageSpeedMetersPerSecond = averageSpeed,
                distanceMeters = distanceMeters,
            ),
        )
    }
}
