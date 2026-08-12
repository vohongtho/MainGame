package com.example.treedirectiondemo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object BearingMath {
    fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    /** Signed shortest angle from current to target, in [-180, 180). */
    fun angleDifference(target: Double, current: Double): Double =
        ((target - current + 540.0) % 360.0) - 180.0

    fun bearingDegrees(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double
    ): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLng = Math.toRadians(toLng - fromLng)

        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) -
            sin(lat1) * cos(lat2) * cos(dLng)

        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }
}
