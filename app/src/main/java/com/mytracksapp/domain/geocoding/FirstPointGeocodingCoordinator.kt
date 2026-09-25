package com.mytracksapp.domain.geocoding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Orchestrates RF-01/RF-02/RF-03's "geocode the first point, once, best-effort" behavior.
 *
 * [onFirstPointRecorded] is a plain, non-suspend function so [com.mytracksapp.service.LocationCollector]
 * (T04) can call it directly from its own suspend point-handling code without needing to know
 * anything about geocoding, coroutines beyond "fire and forget", or persistence — it just calls
 * this once per session, and this class takes it from there entirely on [coroutineScope]:
 *
 *  - the actual geocode-and-persist attempt ([GeocodeAndPersist.attempt]) runs asynchronously on
 *    [coroutineScope], so [onFirstPointRecorded] returns immediately without waiting for it
 *    (RNF-01: never blocks point collection or session start/stop);
 *  - all of RF-02/RF-03/RF-06's "never throws, null/blank treated as failure, persistence
 *    exception logged and swallowed" behavior now lives entirely in [GeocodeAndPersist] (T02 of
 *    `.spec/features/startup-geocoding-retry-and-log-viewer/PLAN.md`), shared with the new
 *    startup-retry path — this class is a thin fire-and-forget wrapper with zero duplicated
 *    geocode/persist logic (RF-03's "no second copy" AC);
 *  - [geocodeAndPersist].[GeocodeAndPersist.attempt] is invoked at most once per
 *    [onFirstPointRecorded] call — since [com.mytracksapp.service.LocationCollector] guarantees at
 *    most one call per session (T04), and nothing here or anywhere else re-invokes this for a
 *    session that already got its one attempt, RF-03's "nenhuma nova tentativa automática" holds
 *    structurally, with no extra tracking state needed in this class.
 */
class FirstPointGeocodingCoordinator(
    private val geocodeAndPersist: GeocodeAndPersist,
    private val coroutineScope: CoroutineScope,
) {

    /**
     * Fire-and-forget: launches the single geocode-and-persist attempt for [sessionId] on
     * [coroutineScope] and returns immediately, before that attempt (success, failure, or
     * `null`/blank result) has necessarily completed.
     */
    fun onFirstPointRecorded(sessionId: String, latitude: Double, longitude: Double) {
        coroutineScope.launch {
            geocodeAndPersist.attempt(sessionId, latitude, longitude)
        }
    }
}
