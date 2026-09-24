package com.mytracksapp.service

import android.content.Context
import android.location.Geocoder
import com.mytracksapp.domain.geocoding.ReverseGeocoder
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [ReverseGeocoder] (RF-01, RF-02), wrapping the platform's
 * `android.location.Geocoder`. Thin adapter over the Android SDK — same precedent as
 * `FusedLocationSampleSource` (not directly unit tested; the "one attempt, graceful failure, no
 * retry" contract is exercised via a fake `ReverseGeocoder` in `FirstPointGeocodingCoordinatorTest`, T06).
 *
 * Never throws to the caller: any [IOException] (no network / geocoder service unavailable) or
 * an empty/blank result is swallowed and reported as `null`.
 */
class AndroidReverseGeocoder(
    private val context: Context,
    private val logger: Logger = FileLogger,
) : ReverseGeocoder {

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            val addresses = try {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
            } catch (e: IOException) {
                logger.log(LogLevel.WARN, "AndroidReverseGeocoder", "Geocoder lookup failed", e)
                null
            }

            val address = addresses?.firstOrNull() ?: return@withContext null
            val name = address.locality ?: address.subAdminArea ?: address.adminArea ?: address.featureName
            name?.trim()?.takeIf { it.isNotBlank() }
        }
}
