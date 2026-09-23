package com.mytracksapp.service

import com.google.android.gms.location.Priority
import com.mytracksapp.domain.model.GpsPrecision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T07 (RF-05) — pure mapping test for [toLocationRequestPriority]. Both `Priority` constants are
 * plain integers from the Play Services `Priority` object, resolvable without a device/emulator,
 * exactly like `SamplingInterval.seconds` today.
 */
class FusedLocationSampleSourceTest {

    @Test
    fun `HIGH_ACCURACY maps to PRIORITY_HIGH_ACCURACY`() {
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, GpsPrecision.HIGH_ACCURACY.toLocationRequestPriority())
    }

    @Test
    fun `BALANCED maps to PRIORITY_BALANCED_POWER_ACCURACY`() {
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, GpsPrecision.BALANCED.toLocationRequestPriority())
    }
}
