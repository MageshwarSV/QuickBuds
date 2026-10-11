package com.spizganed.quickbuds.proximity

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The direction to the device from the user's own turn: a body between phone and earbuds weakens the signal by
 * several dB, so while the user turns slowly in a full circle, holding the phone in front, the signal is strongest
 * facing the device. Each reading goes in with the compass heading of that moment; once most of the circle is
 * covered, the strongest part is the direction. An estimate, and the Arrow tab says so: it is not a direction
 * measurement (signal strength has none), and with no clear difference around the circle there is no arrow at all.
 * Pure: headings in degrees (0 north, clockwise), times in ms.
 */
class DirectionFinder(
    private val sectors: Int = 12,
    /** Readings older than this are dropped: the user walks, the old directions stop being true. */
    private val windowMs: Long = 25_000,
    /** Part of the circle that needs readings before a direction is given. */
    private val minCoverage: Double = 0.75,
    /** Strongest minus weakest part, in dB: less than this is no direction (open air, or too close to tell). */
    private val minContrastDb: Double = 4.0
) {

    sealed class Result {
        /** Still turning: [coverage] 0 to 1 of the circle has readings. */
        data class Turning(val coverage: Double) : Result()
        /** The circle is covered but no part of it stands out. */
        object Unclear : Result()
        /** The device is that way: [bearing] in degrees, 0 north, clockwise. */
        data class Found(val bearing: Double) : Result()
    }

    private class Sample(val sector: Int, val level: Double, val atMs: Long)

    private val samples = ArrayDeque<Sample>()

    fun add(headingDeg: Double, level: Double, nowMs: Long) {
        samples.addLast(Sample(sectorOf(headingDeg), level, nowMs))
        prune(nowMs)
    }

    fun reset() = samples.clear()

    /** The sectors with readings, for the coverage ring; index 0 is centred on north. */
    fun covered(nowMs: Long): BooleanArray {
        prune(nowMs)
        return BooleanArray(sectors).also { a -> samples.forEach { a[it.sector] = true } }
    }

    fun result(nowMs: Long): Result {
        prune(nowMs)
        val sums = DoubleArray(sectors)
        val counts = IntArray(sectors)
        for (s in samples) { sums[s.sector] += s.level; counts[s.sector]++ }
        val filled = (0 until sectors).filter { counts[it] > 0 }
        val coverage = filled.size.toDouble() / sectors
        if (coverage < minCoverage) return Result.Turning(coverage)
        val mean = DoubleArray(sectors) { if (counts[it] > 0) sums[it] / counts[it] else Double.NaN }
        val best = filled.maxBy { mean[it] }
        val weakest = filled.minOf { mean[it] }
        if (mean[best] - weakest < minContrastDb) return Result.Unclear
        // The strongest sector and its neighbours, weighted by how far each stands above the weakest.
        var x = 0.0
        var y = 0.0
        for (d in -1..1) {
            val i = Math.floorMod(best + d, sectors)
            if (counts[i] == 0) continue
            val w = mean[i] - weakest
            val a = Math.toRadians(i * 360.0 / sectors)
            x += w * cos(a)
            y += w * sin(a)
        }
        return Result.Found(normalize(Math.toDegrees(atan2(y, x))))
    }

    private fun sectorOf(headingDeg: Double): Int {
        val width = 360.0 / sectors
        return Math.floorMod(((normalize(headingDeg) + width / 2) / width).toInt(), sectors)
    }

    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > windowMs) samples.removeFirst()
    }

    companion object {
        fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

        /** The turn from [from] to [to], -180 to 180 degrees: where to rotate an arrow, the short way. */
        fun turn(from: Double, to: Double): Double {
            val d = normalize(to - from)
            return if (d > 180) d - 360 else d
        }
    }
}
