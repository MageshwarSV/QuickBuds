package com.spizganed.quickbuds.proximity

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Going back to a saved disconnect place: straight-line distance, bearing and an honest margin. Pure.
 *
 * Distance is recomputed from the current fix every time, never from "start distance minus distance walked".
 */
object PlaceNavigation {

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees from true north, 0..360. */
    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    /** Where to point the arrow on screen: the bearing relative to the phone's heading, -180..180 (0 = ahead). */
    fun relativeDeg(bearingDeg: Double, headingDeg: Double): Double {
        val d = normalize(bearingDeg - headingDeg)
        return if (d > 180) d - 360 else d
    }

    fun normalize(deg: Double): Double = ((deg % 360) + 360) % 360

    /**
     * The margin of the distance: both positions' accuracies combined (root of the sum of squares, as for two
     * independent errors). A heuristic: Android's accuracy is a 68% radius, not a guarantee. Null when either is unknown.
     */
    fun uncertaintyM(currentAccM: Float?, savedAccM: Float?): Double? {
        if (currentAccM == null || savedAccM == null) return null
        return sqrt(currentAccM.toDouble() * currentAccM + savedAccM.toDouble() * savedAccM)
    }

    enum class State { GO, NEAR }

    /** The smallest margin used for "near": GPS rarely does better than this. */
    const val MIN_NEAR_M = 8.0

    /**
     * Android's accuracy is the radius of 68% confidence. For a 2-D normal error the 95% radius is 1.62 times
     * that (sqrt(-2 ln 0.05) / sqrt(-2 ln 0.32)). Was: the 68% radius, so a phone that never moved, saved at
     * ±100 m (Wi-Fi) and now at ±5 m (GPS), showed "152 m" instead of "near".
     */
    const val CONFIDENCE_95 = 1.62

    /** Near when the distance is within the 95% margin: GPS cannot tell the last metres then. Never "0 m". */
    fun state(distanceM: Double, uncertaintyM: Double?): State =
        if (distanceM <= max((uncertaintyM ?: MIN_NEAR_M) * CONFIDENCE_95, MIN_NEAR_M)) State.NEAR else State.GO

    /** Worse than this, a saved place came from Wi-Fi or mobile network, not GPS: the screen says so. */
    const val ROUGH_M = 50f

    /** Whole metres below 1 km, then km with one decimal: "42 m", "1.3 km". The unit words come from strings. */
    fun splitDistance(distanceM: Double): Pair<Boolean, String> =
        if (distanceM < 1_000) false to distanceM.roundToInt().toString()
        else true to String.format(java.util.Locale.US, "%.1f", distanceM / 1_000)
}
