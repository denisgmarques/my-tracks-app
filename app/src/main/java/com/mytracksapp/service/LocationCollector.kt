package com.mytracksapp.service

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.domain.model.SamplingInterval

/**
 * A single raw location fix as reported by whatever underlying provider is wired in — production:
 * a thin adapter over `FusedLocationProviderClient` (see `FusedLocationSampleSource` in
 * `LocationForegroundService.kt`); tests: a fake emitting synthetic fixes directly.
 */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
)

/**
 * Minimal abstraction [LocationCollector] needs over a location provider. Kept Android/Play
 * Services free so [LocationCollector] — the actual collection/guard/drift logic behind T06 — is
 * a plain JVM unit under test (see `LocationCollectorTest`/`LocationForegroundServiceStartGuardTest`),
 * with no Robolectric or instrumentation required.
 */
interface LocationSampleSource {

    /**
     * Begins requesting fixes at [intervalMillis], invoking [onLocation] for each one received.
     * [onLocation] is `suspend` so implementations of [LocationCollector]'s guard/persistence logic
     * can call suspend DAO functions directly; a real (non-suspend) callback source such as
     * Play Services' `LocationCallback` is expected to bridge into a coroutine itself before
     * invoking it.
     */
    fun start(intervalMillis: Long, onLocation: suspend (LocationSample) -> Unit)

    /** Stops requesting fixes. Must be safe to call even if [start] was never called. */
    fun stop()
}

/**
 * Testable core of T06's periodic GPS point collection (RF-02, RF-03, RNF-03), deliberately kept
 * independent of both the `Service` Android lifecycle wrapper ([LocationForegroundService]) and of
 * `FusedLocationProviderClient` itself (behind [LocationSampleSource]) — see PLAN.md T06.
 *
 * Responsibilities:
 *  - refuses to start collecting at all unless there is an active session for [sessionId] AND
 *    background location permission is granted (RF-03) — checked once at [start] time, before
 *    [locationSampleSource] is ever asked to begin requesting fixes;
 *  - defensively re-checks that the session is still active for every individual fix that arrives
 *    (a session may be stopped concurrently with a fix already in flight) — RF-02's "nenhum ponto
 *    é registrado fora de uma sessão ativa" is enforced per point, not just at start time;
 *  - on every accepted fix, records the real observed interval drift versus the session's
 *    configured sampling interval (RNF-03: "o desvio observado ... é registrado junto ao ponto"),
 *    `null` for a session's first accepted point (no predecessor to diff against).
 */
class LocationCollector(
    private val sessionId: String,
    private val interval: SamplingInterval,
    private val gpsPointDao: GpsPointDao,
    private val locationSampleSource: LocationSampleSource,
    private val isSessionActive: suspend () -> Boolean,
    private val isLocationPermissionGranted: () -> Boolean,
    private val onFirstPointRecorded: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
) {
    private val configuredIntervalMillis: Long = interval.seconds * 1_000L

    @Volatile
    private var collecting = false

    @Volatile
    private var lastAcceptedTimestamp: Long? = null

    /**
     * Attempts to start collection. Returns `false` — without ever calling
     * [LocationSampleSource.start] — if there is no active session for [sessionId] or background
     * location permission is not currently granted (RF-03's "impedir o início da coleta ... em
     * segundo plano"). Returns `true` once [locationSampleSource] has been asked to begin
     * requesting fixes.
     */
    suspend fun start(): Boolean {
        if (!isLocationPermissionGranted() || !isSessionActive()) {
            return false
        }

        collecting = true
        locationSampleSource.start(configuredIntervalMillis) { sample -> onLocationSample(sample) }
        return true
    }

    /** Stops collection. Safe to call even if [start] was never called or already returned `false`. */
    fun stop() {
        collecting = false
        locationSampleSource.stop()
    }

    private suspend fun onLocationSample(sample: LocationSample) {
        // Defensive guards per RF-02/RF-03: never persist a point once collection has been
        // stopped, or once the session it belongs to is no longer active — even if a fix was
        // already in flight when either happened.
        if (!collecting) return
        if (!isSessionActive()) return

        val previousTimestamp = lastAcceptedTimestamp
        val observedIntervalDriftMillis = previousTimestamp?.let { previous ->
            (sample.timestamp - previous) - configuredIntervalMillis
        }

        gpsPointDao.insert(
            GpsPointEntity(
                sessionId = sessionId,
                timestamp = sample.timestamp,
                latitude = sample.latitude,
                longitude = sample.longitude,
                accuracy = sample.accuracy,
                observedIntervalDriftMillis = observedIntervalDriftMillis,
            ),
        )
        lastAcceptedTimestamp = sample.timestamp

        if (previousTimestamp == null) {
            onFirstPointRecorded(sample.latitude, sample.longitude)
        }
    }
}
