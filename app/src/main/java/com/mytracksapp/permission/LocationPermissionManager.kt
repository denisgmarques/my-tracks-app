package com.mytracksapp.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Encapsulates location permission checks required by RF-03: on Android 10+ (API 29+),
 * background GPS collection must not start unless both `ACCESS_FINE_LOCATION` and
 * `ACCESS_BACKGROUND_LOCATION` are granted.
 *
 * Below API 29, `ACCESS_BACKGROUND_LOCATION` does not exist as a distinct runtime permission —
 * foreground location access implies background access — so only fine location is required
 * there. This app's minSdk is 29 (RNF-01), but the check is written defensively for API level
 * regardless.
 */
class LocationPermissionManager(private val context: Context) {

    /** Whether `ACCESS_FINE_LOCATION` is currently granted. */
    fun isFineLocationGranted(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * Whether background location collection is allowed to start.
     *
     * On API 29+, this requires both fine location AND `ACCESS_BACKGROUND_LOCATION` to be
     * granted. Never throws — a missing/denied permission simply yields `false`, per T03's
     * acceptance criteria.
     */
    fun isBackgroundLocationGranted(): Boolean {
        if (!isFineLocationGranted()) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            true
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
