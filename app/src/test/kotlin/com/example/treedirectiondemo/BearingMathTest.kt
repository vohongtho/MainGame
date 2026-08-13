package com.example.treedirectiondemo

import org.junit.Assert.assertEquals
import org.junit.Test

class BearingMathTest {

    @Test
    fun normalizeDegrees_wrapsAcrossNorth() {
        assertEquals(1.0, BearingMath.normalizeDegrees(361.0), 0.0001)
        assertEquals(359.0, BearingMath.normalizeDegrees(-1.0), 0.0001)
    }

    @Test
    fun angleDifference_usesShortestTurnAcrossNorth() {
        assertEquals(2.0, BearingMath.angleDifference(1.0, 359.0), 0.0001)
        assertEquals(-2.0, BearingMath.angleDifference(359.0, 1.0), 0.0001)
    }

    @Test
    fun bearingDueNorth_isZero() {
        assertEquals(0.0, BearingMath.bearingDegrees(10.0, 106.0, 10.001, 106.0), 0.01)
    }

    @Test
    fun bearingDueEast_isApproximatelyNinety() {
        assertEquals(90.0, BearingMath.bearingDegrees(0.0, 0.0, 0.0, 0.001), 0.01)
    }
}
