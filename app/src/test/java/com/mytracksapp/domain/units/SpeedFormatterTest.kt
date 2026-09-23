package com.mytracksapp.domain.units

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedFormatterTest {

    @Test
    fun `MS returns the value unconverted`() {
        assertEquals(0.0, SpeedFormatter.toDisplayValue(0.0, SpeedUnit.MS), 0.0)
        assertEquals(10.0, SpeedFormatter.toDisplayValue(10.0, SpeedUnit.MS), 0.0)
        assertEquals(3.456, SpeedFormatter.toDisplayValue(3.456, SpeedUnit.MS), 0.0000001)
    }

    @Test
    fun `KMH multiplies by 3-6`() {
        assertEquals(0.0, SpeedFormatter.toDisplayValue(0.0, SpeedUnit.KMH), 0.0)
        assertEquals(36.0, SpeedFormatter.toDisplayValue(10.0, SpeedUnit.KMH), 0.0000001)
        assertEquals(3.6, SpeedFormatter.toDisplayValue(1.0, SpeedUnit.KMH), 0.0000001)
    }

    @Test
    fun `KNOTS multiplies by 1-9438444924406`() {
        assertEquals(0.0, SpeedFormatter.toDisplayValue(0.0, SpeedUnit.KNOTS), 0.0)
        assertEquals(1.9438444924406, SpeedFormatter.toDisplayValue(1.0, SpeedUnit.KNOTS), 0.0000000001)
        assertEquals(19.438444924406, SpeedFormatter.toDisplayValue(10.0, SpeedUnit.KNOTS), 0.000000001)
    }
}
