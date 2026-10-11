package com.spizganed.quickbuds.proximity

/** Where the buds are, from their wear reports. */
enum class Placement {
    /** No wear report (the buds do not send one, or none yet). Monitoring runs on signal alone. */
    UNKNOWN,
    /** At least one bud out of the case and not in an ear: the buds can be left behind. */
    OUT,
    /** A bud is in an ear: the user has them. */
    WORN,
    /** Every reported bud is in the case (or off in a closing case). */
    IN_CASE;

    companion object {
        /**
         * From the raw wear codes (PROTOCOL.md §8): 3/7 in ear, 1/5 out of ear, 4 in case, 0 off,
         * -1 not reported. 0 counts with the case: the buds send all zeros just before a lid close.
         */
        fun of(left: Int, right: Int): Placement {
            val known = listOf(left, right).filter { it >= 0 }
            return when {
                known.isEmpty() -> UNKNOWN
                known.any { it == 3 || it == 7 } -> WORN
                known.all { it == 4 || it == 0 } -> IN_CASE
                else -> OUT
            }
        }
    }
}

/** Why the link went down. Only [LINK_LOST] can mean "out of range". */
enum class DropReason { USER, CASE_CLOSED, BUDS_RESTART, BLUETOOTH_OFF, LINK_LOST }

/** The loss warning's states. Every transition is in [ProximityEngine]. */
enum class ProximityState {
    /** Loss protection off, or nothing connected yet. */
    IDLE,
    /** Connected, signal healthy or not yet settled. */
    MONITORING,
    /** All buds in the case: no warning. */
    IN_CASE,
    /** A bud is in an ear: no warning. */
    WORN,
    /** Signal at or under the weak level; waiting for it to persist and show movement. */
    WEAK_SIGNAL,
    /** Warning shown; waiting for the signal to recover. */
    WARNING_SENT,
    /** Recovered after a warning; re-arms after [ProximityConfig.REARM_MS]. */
    RECOVERED,
    /** Link down; nothing is read. */
    DISCONNECTED,
    /** This phone cannot read these buds' signal strength. Usage tracking is unaffected. */
    UNSUPPORTED
}

/** What the caller must do after an event. */
enum class ProximityEffect { NONE, WARN, WARN_LINK_LOST, CLEAR }

/**
 * The loss warning's decision logic, as an explicit state machine. No Android, no clock of its own:
 * [ProximityMonitor] feeds it events with their time, tests feed it numbers.
 *
 * MONITORING -> WEAK_SIGNAL when the smoothed level reaches the weak threshold.
 * WEAK_SIGNAL -> MONITORING when it climbs back to the recovery threshold (hysteresis: between the two
 * nothing changes). WEAK_SIGNAL -> WARNING_SENT ([ProximityEffect.WARN]) once it stayed weak for the delay
 * AND it shows movement away: a falling trend, or a fall from the recent strongest level. A signal that
 * was weak all along (buds in the next room since connect) never warns.
 * WARNING_SENT -> RECOVERED ([ProximityEffect.CLEAR]) after [ProximityConfig.RECOVERY_SAMPLES] healthy
 * readings. RECOVERED, after [ProximityConfig.REARM_MS]: -> WEAK_SIGNAL if the signal is weak again (a second
 * walk-away), -> MONITORING if it is strong. So one separation gives one warning, and a new one needs a recovery
 * first. Was: RECOVERED left only on a strong signal, so walking away again within the cooldown never warned.
 * In the case or in an ear: IN_CASE / WORN, nothing read counts. A link lost while the signal was weak
 * warns once ([ProximityEffect.WARN_LINK_LOST]); any other drop (case closed, the user, Bluetooth off) never does.
 * A warning on screen survives a reconnect: the link comes back in WARNING_SENT, so a recovery, the case or an ear
 * clears it.
 */
class ProximityEngine(var sensitivity: Sensitivity = Sensitivity.MEDIUM) {

    var state = ProximityState.IDLE
        private set
    var placement = Placement.UNKNOWN
        private set

    val filter = RssiFilter()
    private var weakSince = 0L
    private var warnedAt = 0L
    private var healthy = 0
    private var unsupported = false
    /** A warning is on screen: kept across reconnects, so the card goes when the buds are found. */
    private var warningShown = false

    val thresholds: ProximityThresholds?
        get() = filter.scale?.let { ProximityConfig.thresholds(sensitivity, it) }

    /** The link came up (or loss protection was turned on while connected). */
    fun onConnected(): ProximityEffect {
        filter.reset()
        val placed = placementState()
        state = when {
            unsupported -> ProximityState.UNSUPPORTED
            warningShown && placed == ProximityState.MONITORING -> { healthy = 0; ProximityState.WARNING_SENT }
            else -> placed
        }
        return ProximityEffect.NONE
    }

    fun onPlacement(p: Placement): ProximityEffect {
        placement = p
        if (state == ProximityState.IDLE || state == ProximityState.DISCONNECTED || state == ProximityState.UNSUPPORTED) return ProximityEffect.NONE
        val was = state
        val next = placementState()
        if (next == ProximityState.IN_CASE || next == ProximityState.WORN) {
            state = next
            filter.reset()
            // Back in the case or an ear: found. A warning still showing goes.
            return if (was == ProximityState.WARNING_SENT || was == ProximityState.RECOVERED || warningShown) clear()
                else ProximityEffect.NONE
        }
        if (was == ProximityState.IN_CASE || was == ProximityState.WORN) {
            state = ProximityState.MONITORING
            filter.reset()
        }
        return ProximityEffect.NONE
    }

    fun onRssi(raw: Int, nowMs: Long): ProximityEffect {
        if (state !in READING) return ProximityEffect.NONE
        if (!filter.add(raw, nowMs) || !filter.ready) return ProximityEffect.NONE
        val th = thresholds ?: return ProximityEffect.NONE
        val level = filter.smoothed
        when (state) {
            ProximityState.MONITORING -> if (level <= th.weak) {
                state = ProximityState.WEAK_SIGNAL
                weakSince = nowMs
            }
            ProximityState.WEAK_SIGNAL -> when {
                level >= th.recovery -> state = ProximityState.MONITORING
                level <= th.weak && nowMs - weakSince >= th.delayMs && movingAway(th, nowMs) -> {
                    state = ProximityState.WARNING_SENT
                    warnedAt = nowMs
                    healthy = 0
                    warningShown = true
                    return ProximityEffect.WARN
                }
            }
            ProximityState.WARNING_SENT -> {
                healthy = if (level >= th.recovery) healthy + 1 else 0
                if (healthy >= ProximityConfig.RECOVERY_SAMPLES) {
                    state = ProximityState.RECOVERED
                    return clear()
                }
            }
            ProximityState.RECOVERED -> if (nowMs - warnedAt >= ProximityConfig.REARM_MS) when {
                level <= th.weak -> { state = ProximityState.WEAK_SIGNAL; weakSince = nowMs }
                level >= th.recovery -> state = ProximityState.MONITORING
            }
            else -> {}
        }
        return ProximityEffect.NONE
    }

    /** No reading could be taken on this link: the feature stays off for these buds until the next connect. */
    fun onRssiUnavailable(): ProximityEffect {
        unsupported = true
        state = ProximityState.UNSUPPORTED
        filter.reset()
        return if (warningShown) clear() else ProximityEffect.NONE
    }

    /**
     * A lost link warns when the signal was weak: getting weaker, already warned (the card then says the link is
     * gone), or weak again in the cooldown after a recovery.
     */
    fun onDisconnected(reason: DropReason): ProximityEffect {
        val was = state
        val weakNow = thresholds?.let { filter.smoothed <= it.weak } == true
        // A warning the user closed ("I have them") does not come back as a lost link.
        val weak = was == ProximityState.WEAK_SIGNAL || (was == ProximityState.WARNING_SENT && warningShown) ||
            (was == ProximityState.RECOVERED && weakNow)
        val effect = if (reason == DropReason.LINK_LOST && weak && placement != Placement.IN_CASE) {
            warningShown = true
            ProximityEffect.WARN_LINK_LOST
        } else ProximityEffect.NONE
        state = ProximityState.DISCONNECTED
        filter.reset()
        unsupported = false
        // Where the buds are is unknown until they report again on the next link.
        placement = Placement.UNKNOWN
        return effect
    }

    /** Loss protection turned off. */
    fun stop() {
        state = ProximityState.IDLE
        filter.reset()
        unsupported = false
        warningShown = false
    }

    /** The user closed the card ("I have them"): nothing left to clear. */
    fun onWarningDismissed() {
        warningShown = false
    }

    private fun clear(): ProximityEffect {
        warningShown = false
        return ProximityEffect.CLEAR
    }

    private fun movingAway(th: ProximityThresholds, nowMs: Long): Boolean =
        filter.slopePer10s(nowMs) <= th.slopePer10s || filter.baseline() - filter.smoothed >= th.dropDb

    private fun placementState() = when (placement) {
        Placement.IN_CASE -> ProximityState.IN_CASE
        Placement.WORN -> ProximityState.WORN
        else -> ProximityState.MONITORING
    }

    private companion object {
        val READING = setOf(ProximityState.MONITORING, ProximityState.WEAK_SIGNAL, ProximityState.WARNING_SENT, ProximityState.RECOVERED)
    }
}
