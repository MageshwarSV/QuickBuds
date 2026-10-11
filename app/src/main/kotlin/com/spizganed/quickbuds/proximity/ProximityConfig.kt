package com.spizganed.quickbuds.proximity

/** Settings › Loss protection sensitivity. */
enum class Sensitivity { LOW, MEDIUM, HIGH }

/**
 * What an RSSI number means. Android reads a Classic (BR/EDR) link's RSSI with HCI Read RSSI, which the
 * Bluetooth spec defines as the distance from the controller's "golden receive power range": 0 inside
 * it, negative below. Many controllers return that; some return plain dBm. [RssiFilter] tells them apart
 * from the first readings, and each scale gets its own thresholds.
 */
enum class RssiScale { DBM, GOLDEN_RANGE }

/**
 * Starting points, not distances. RSSI depends on walls, bodies, orientation, interference and the
 * hardware on both ends, so no value here means "N metres". A warning needs all of: a smoothed level
 * at or under [weak], for [delayMs], with evidence of moving away ([slopePer10s] or [dropDb]).
 * It re-arms only at [recovery] or better (hysteresis).
 *
 * @param weak smoothed level that counts as weak.
 * @param recovery smoothed level that counts as healthy again; above [weak].
 * @param dropDb fall from the recent strongest level that counts as "moved away".
 * @param slopePer10s trend (dB per 10 s, negative) that counts as "moving away now".
 * @param delayMs how long the signal must stay weak before a warning.
 */
data class ProximityThresholds(
    val weak: Double,
    val recovery: Double,
    val dropDb: Double,
    val slopePer10s: Double,
    val delayMs: Long
)

object ProximityConfig {
    /** One RSSI read this often while monitoring. */
    const val SAMPLE_INTERVAL_MS = 3_000L
    /** A read without an answer by then counts as failed. */
    const val READ_TIMEOUT_MS = 2_500L
    /** This many failed reads in a row, with none ever answered, means this phone cannot read these buds' RSSI. */
    const val PROBE_FAILURES = 5
    /** Exponential moving average weight of a new reading. */
    const val EMA_ALPHA = 0.3
    /** The trend is the slope over this much recent time. */
    const val TREND_WINDOW_MS = 15_000L
    /** "Moved away" compares with the strongest smoothed level in this much recent time. */
    const val BASELINE_WINDOW_MS = 120_000L
    /** Readings needed before any decision: the first ones only settle the filter and the scale. */
    const val MIN_SAMPLES = 4
    /** Readings at the recovery level in a row before the warning clears (one strong spike is not recovery). */
    const val RECOVERY_SAMPLES = 2
    /** At least this long between two warnings, even with a recovery in between. */
    const val REARM_MS = 60_000L
    /** With the screen off, the CPU is kept awake for monitoring this long at most ([ProximityMonitor]). */
    const val WAKE_WINDOW_MS = 5 * 60_000L

    /** Weak, recovery: the suggested -70 / -65 dBm at Medium, shifted for Low and High. */
    fun thresholds(sensitivity: Sensitivity, scale: RssiScale): ProximityThresholds = when (scale) {
        RssiScale.DBM -> when (sensitivity) {
            Sensitivity.LOW -> ProximityThresholds(-76.0, -68.0, 12.0, -6.0, 10_000)
            Sensitivity.MEDIUM -> ProximityThresholds(-70.0, -65.0, 9.0, -4.0, 8_000)
            Sensitivity.HIGH -> ProximityThresholds(-65.0, -60.0, 6.0, -3.0, 5_000)
        }
        // Golden range: 0 = strong enough. Measured on a OnePlus Nord CE5 with Nord Buds 3r (2026-10-09): buds on the
        // table beside the phone dip to -10..-22 raw (smoothed about -11); a walk to the next room reads -10..-13
        // first, then falls further. So the warning sits under the everyday dips.
        RssiScale.GOLDEN_RANGE -> when (sensitivity) {
            Sensitivity.LOW -> ProximityThresholds(-18.0, -10.0, 12.0, -5.0, 12_000)
            Sensitivity.MEDIUM -> ProximityThresholds(-14.0, -7.0, 9.0, -4.0, 10_000)
            Sensitivity.HIGH -> ProximityThresholds(-10.0, -5.0, 6.0, -3.0, 6_000)
        }
    }
}
