package com.mytracksapp.domain.units

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceFormatterTest {

    @Test
    fun `KM divides by 1000`() {
        assertEquals(0.0, DistanceFormatter.toDisplayValue(0.0, DistanceUnit.KM), 0.0)
        assertEquals(1.0, DistanceFormatter.toDisplayValue(1000.0, DistanceUnit.KM), 0.0000001)
        assertEquals(2.5, DistanceFormatter.toDisplayValue(2500.0, DistanceUnit.KM), 0.0000001)
    }

    @Test
    fun `MILES divides by 1609-344`() {
        assertEquals(0.0, DistanceFormatter.toDisplayValue(0.0, DistanceUnit.MILES), 0.0)
        assertEquals(1.0, DistanceFormatter.toDisplayValue(1609.344, DistanceUnit.MILES), 0.0000001)
        assertEquals(6.2137119224, DistanceFormatter.toDisplayValue(10_000.0, DistanceUnit.MILES), 0.000001)
    }

    @Test
    fun `NAUTICAL_MILES divides by 1852`() {
        assertEquals(0.0, DistanceFormatter.toDisplayValue(0.0, DistanceUnit.NAUTICAL_MILES), 0.0)
        assertEquals(1.0, DistanceFormatter.toDisplayValue(1852.0, DistanceUnit.NAUTICAL_MILES), 0.0000001)
        assertEquals(5.399568034557, DistanceFormatter.toDisplayValue(10_000.0, DistanceUnit.NAUTICAL_MILES), 0.000001)
    }
}
