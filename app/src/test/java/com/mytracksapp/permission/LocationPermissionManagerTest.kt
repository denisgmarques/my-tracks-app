package com.mytracksapp.permission

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T03 — LocationPermissionManager unit tests (local JVM, via Robolectric).
 *
 * Runs against API 29 (Android 10, minSdk of this app), where `ACCESS_BACKGROUND_LOCATION`
 * is a distinct runtime permission from `ACCESS_FINE_LOCATION` per RF-03.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocationPermissionManagerTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager = LocationPermissionManager(context)

    @Test
    fun `isBackgroundLocationGranted returns false when no permission granted on API 29+`() {
        // No permissions granted at all (default Robolectric state).
        assertFalse(manager.isBackgroundLocationGranted())
    }

    @Test
    fun `isBackgroundLocationGranted returns false when only fine location granted on API 29+`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertFalse(manager.isBackgroundLocationGranted())
    }

    @Test
    fun `isBackgroundLocationGranted returns true when fine and background location granted`() {
        shadowOf(context).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )

        assertTrue(manager.isBackgroundLocationGranted())
    }

    @Test
    fun `isFineLocationGranted reflects PackageManager state without throwing`() {
        assertFalse(manager.isFineLocationGranted())

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertTrue(manager.isFineLocationGranted())
        assertTrue(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
}
