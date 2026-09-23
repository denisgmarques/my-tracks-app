package com.mytracksapp.domain.geocoding

/**
 * Android-free abstraction over reverse geocoding (RF-01, RF-02): turning a raw lat/lon fix into
 * a human-readable place name. Kept free of any `android.*` reference in its signature so
 * consumers (e.g. [FirstPointGeocodingCoordinator]) stay plain-JVM testable; the real
 * implementation ([com.mytracksapp.service.AndroidReverseGeocoder]) wraps `android.location.Geocoder`.
 */
interface ReverseGeocoder {

    /**
     * Attempts to resolve a human-readable place name for ([latitude], [longitude]).
     *
     * Returns `null` when no name could be resolved (no result, blank result, or any failure) —
     * implementations must never throw to the caller. No retry logic is implied or required here;
     * "one attempt, graceful failure" is the whole contract.
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String?
}
