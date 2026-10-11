package com.spizganed.quickbuds.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityEngineTest {

    private val engine = ProximityEngine(Sensitivity.MEDIUM).also { it.onConnected() }
    private var t = 0L
    private val effects = mutableListOf<ProximityEffect>()
    private val states = mutableListOf<ProximityState>()

    /** One reading per sample interval, as [ProximityMonitor] takes them. */
    private fun feed(vararg readings: Int) {
        for (r in readings) {
            t += ProximityConfig.SAMPLE_INTERVAL_MS
            engine.onRssi(r, t).let { if (it != ProximityEffect.NONE) effects += it }
            if (states.lastOrNull() != engine.state) states += engine.state
        }
    }

    private fun repeatFeed(value: Int, times: Int) = feed(*IntArray(times) { value })

    /**
     * Near, then a fast drop: the smoothed level reaches the weak threshold (-70) on the last reading (medians
     * of three: -55, -65, -75, -80, -82; EMA: -55, -58, -63.1, -68.2, -72.3), so the state is WEAK_SIGNAL with
     * the delay not yet passed.
     */
    private fun goWeak() {
        repeatFeed(-55, 4)
        feed(-65, -75, -80, -82, -82)
    }

    private fun warnings() = effects.count { it == ProximityEffect.WARN || it == ProximityEffect.WARN_LINK_LOST }

    /** Near the phone, then walking away: -55 to -79 dBm, 3 dB a reading. */
    private val walkAway = intArrayOf(-55, -55, -56, -55, -58, -61, -64, -67, -70, -73, -76, -79, -79, -80, -80, -81)

    // 27
    @Test fun smoothingIgnoresAnUnstableSignal() {
        repeat(5) { feed(-72, -60, -73, -61, -70, -58) }
        assertEquals(0, warnings())
        assertTrue(engine.state == ProximityState.MONITORING)
        val s = engine.filter.smoothed
        assertTrue("smoothed $s stays between the swings", s > -70 && s < -60)
    }

    @Test fun oneBadReadingDoesNothing() {
        repeatFeed(-55, 6)
        feed(-90)
        repeatFeed(-55, 6)
        assertEquals(ProximityState.MONITORING, engine.state)
        assertEquals(0, warnings())
    }

    // 28
    @Test fun weakThresholdEntersWeakSignalWithoutWarningAtOnce() {
        repeatFeed(-55, 4)
        repeatFeed(-75, 5)
        assertEquals(ProximityState.WEAK_SIGNAL, engine.state)
        assertEquals(0, warnings())
    }

    // 29
    @Test fun downwardTrendWarnsOnceAfterTheDelay() {
        feed(*walkAway)
        assertEquals(1, warnings())
        assertEquals(ProximityState.WARNING_SENT, engine.state)
        assertTrue(states.indexOf(ProximityState.WEAK_SIGNAL) < states.indexOf(ProximityState.WARNING_SENT))
    }

    @Test fun warningWaitsForTheDelay() {
        goWeak()
        // Weak for one reading so far: not long enough.
        assertEquals(ProximityState.WEAK_SIGNAL, engine.state)
        assertEquals(0, warnings())
    }

    // 30
    @Test fun stableWeakSignalNeverWarns() {
        // Buds behind a wall since they connected: weak, but nothing moves away.
        repeatFeed(-75, 60)
        assertEquals(0, warnings())
        assertEquals(ProximityState.WEAK_SIGNAL, engine.state)
    }

    // 31
    @Test fun recoveryClearsAndAFreshSeparationWarnsAgain() {
        feed(*walkAway)
        assertEquals(1, warnings())
        repeatFeed(-55, 6)                            // walked back
        assertEquals(ProximityEffect.CLEAR, effects.last())
        assertEquals(ProximityState.RECOVERED, engine.state)
        repeatFeed(-55, 20)                           // re-arms after REARM_MS
        assertEquals(ProximityState.MONITORING, engine.state)
        feed(*walkAway)                               // walks away again
        assertEquals(2, warnings())
    }

    // 32
    @Test fun hysteresisHoldsAroundTheThreshold() {
        goWeak()
        assertEquals(ProximityState.WEAK_SIGNAL, engine.state)
        val entered = states.count { it == ProximityState.WEAK_SIGNAL }
        // Hovering between the weak (-70) and recovery (-65) levels: no flapping back to MONITORING.
        repeat(10) { feed(-69, -66, -68, -67) }
        assertEquals(entered, states.count { it == ProximityState.WEAK_SIGNAL })
        assertTrue(engine.state == ProximityState.WEAK_SIGNAL || engine.state == ProximityState.WARNING_SENT)
    }

    @Test fun recoveryLevelEndsTheWeakState() {
        goWeak()
        assertEquals(ProximityState.WEAK_SIGNAL, engine.state)
        repeatFeed(-58, 6)
        assertEquals(ProximityState.MONITORING, engine.state)
        assertEquals(0, warnings())
    }

    // 33
    @Test fun cooldownSendsNoSecondWarningWhileStillWeak() {
        feed(*walkAway)
        repeatFeed(-82, 100)                          // five more minutes away
        feed(-50)                                     // one strong spike is not a recovery
        repeatFeed(-82, 20)
        assertEquals(1, warnings())
    }

    // 34
    @Test fun disconnectStopsMonitoringAndOnlyALossWhileWeakWarns() {
        goWeak()
        assertEquals(ProximityState.WEAK_SIGNAL, engine.state)
        assertEquals(ProximityEffect.WARN_LINK_LOST, engine.onDisconnected(DropReason.LINK_LOST))
        assertEquals(ProximityState.DISCONNECTED, engine.state)
        feed(-90, -90, -90, -90, -90, -90)            // nothing is read while disconnected
        assertEquals(ProximityState.DISCONNECTED, engine.state)
    }

    @Test fun normalDisconnectsNeverWarn() {
        for (reason in listOf(DropReason.USER, DropReason.CASE_CLOSED, DropReason.BUDS_RESTART, DropReason.BLUETOOTH_OFF)) {
            engine.onConnected()
            goWeak()
            assertEquals(ProximityState.WEAK_SIGNAL, engine.state)
            assertEquals(ProximityEffect.NONE, engine.onDisconnected(reason))
        }
    }

    @Test fun linkLossWithAHealthySignalDoesNotWarn() {
        repeatFeed(-55, 8)
        assertEquals(ProximityEffect.NONE, engine.onDisconnected(DropReason.LINK_LOST))
    }

    // 35
    @Test fun inTheCaseNothingWarns() {
        repeatFeed(-55, 4)
        engine.onPlacement(Placement.of(4, 4))
        assertEquals(ProximityState.IN_CASE, engine.state)
        feed(*walkAway)
        assertEquals(0, warnings())
        assertEquals(ProximityEffect.NONE, engine.onDisconnected(DropReason.LINK_LOST))
    }

    @Test fun takenOutOfTheCaseMonitoringResumes() {
        engine.onPlacement(Placement.of(4, 4))
        engine.onPlacement(Placement.of(1, 4))        // one bud out, on the table
        assertEquals(ProximityState.MONITORING, engine.state)
        feed(*walkAway)
        assertEquals(1, warnings())
    }

    @Test fun wornBudsNeverWarnAndPutBackInTheCaseClears() {
        engine.onPlacement(Placement.of(3, 7))
        feed(*walkAway)
        assertEquals(0, warnings())
        engine.onPlacement(Placement.of(1, 1))
        feed(*walkAway)
        assertEquals(1, warnings())
        assertEquals(ProximityEffect.CLEAR, engine.onPlacement(Placement.of(4, 4)))
    }

    @Test fun placementIsForgottenWithTheLink() {
        engine.onPlacement(Placement.of(4, 4))
        engine.onDisconnected(DropReason.CASE_CLOSED)
        engine.onConnected()
        // Out of the case on the next link, before any wear report: the signal decides.
        assertEquals(ProximityState.MONITORING, engine.state)
    }

    @Test fun placementFromWearCodes() {
        assertEquals(Placement.UNKNOWN, Placement.of(-1, -1))
        assertEquals(Placement.IN_CASE, Placement.of(4, 4))
        assertEquals(Placement.IN_CASE, Placement.of(0, 0))   // all zeros: the lid is closing
        assertEquals(Placement.IN_CASE, Placement.of(4, -1))
        assertEquals(Placement.WORN, Placement.of(3, 4))
        assertEquals(Placement.WORN, Placement.of(1, 7))
        assertEquals(Placement.OUT, Placement.of(1, 5))
        assertEquals(Placement.OUT, Placement.of(4, 1))       // one bud left out of the case
    }

    @Test fun noCaseStateFallsBackToSignalOnly() {
        // Placement never reported (UNKNOWN): the signal alone decides.
        feed(*walkAway)
        assertEquals(1, warnings())
    }

    // 36
    @Test fun unsupportedRssiTurnsOnlyTheWarningOff() {
        assertEquals(ProximityEffect.NONE, engine.onRssiUnavailable())
        assertEquals(ProximityState.UNSUPPORTED, engine.state)
        feed(*walkAway)
        assertEquals(0, warnings())
        assertEquals(ProximityEffect.NONE, engine.onDisconnected(DropReason.LINK_LOST))
        // The next connection probes again.
        engine.onConnected()
        assertEquals(ProximityState.MONITORING, engine.state)
    }

    @Test fun goldenRangeScaleIsDetectedAndWarns() {
        // Controllers reporting the BR/EDR golden range: 0 when close, negative beyond it.
        feed(0, 0, 0, 0, -2, -5, -9, -13, -17, -20, -22, -24, -25, -26, -27, -28, -28, -29)
        assertEquals(RssiScale.GOLDEN_RANGE, engine.filter.scale)
        assertEquals(1, warnings())
    }

    @Test fun everydayDipsBesideThePhoneDoNotWarn() {
        // Golden-range readings like the OnePlus Nord CE5 + Nord Buds 3r gave with the buds on the table beside
        // the phone (2026-10-09, two false warnings with the first thresholds): mostly 0, dips to -10..-14, a
        // lone -22.
        repeat(20) { feed(0, 0, -3, 0, -14, -5, 0, -22, -8, -2, 0, -10, -13, -6, 0, -1) }
        assertEquals(RssiScale.GOLDEN_RANGE, engine.filter.scale)
        assertEquals(0, warnings())
    }

    @Test fun aLoneSpikeIsFilteredOut() {
        repeatFeed(-55, 6)
        val before = engine.filter.smoothed
        feed(-95)
        assertEquals(before, engine.filter.smoothed, 0.01)
    }

    @Test fun dbmScaleIsDetected() {
        repeatFeed(-50, 4)
        assertEquals(RssiScale.DBM, engine.filter.scale)
    }

    @Test fun implausibleReadingsAreIgnored() {
        repeatFeed(127, 10)
        assertEquals(0, engine.filter.count)
    }

    @Test fun sensitivityMovesTheThresholds() {
        val low = ProximityConfig.thresholds(Sensitivity.LOW, RssiScale.DBM)
        val medium = ProximityConfig.thresholds(Sensitivity.MEDIUM, RssiScale.DBM)
        val high = ProximityConfig.thresholds(Sensitivity.HIGH, RssiScale.DBM)
        assertEquals(-70.0, medium.weak, 0.0)
        assertEquals(-65.0, medium.recovery, 0.0)
        assertTrue(low.weak < medium.weak && medium.weak < high.weak)
        for (s in Sensitivity.values()) for (scale in RssiScale.values()) {
            val th = ProximityConfig.thresholds(s, scale)
            assertTrue("recovery above weak", th.recovery > th.weak)
        }
    }

    // Found in review: RECOVERED was left only on a strong signal.
    @Test fun aSecondWalkAwayInsideTheCooldownStillWarns() {
        feed(*walkAway)
        repeatFeed(-55, 6)                            // back: CLEAR, RECOVERED
        assertEquals(ProximityState.RECOVERED, engine.state)
        feed(*walkAway)                               // gone again within 60 s
        repeatFeed(-82, 30)
        assertEquals(2, warnings())
    }

    @Test fun aLinkLostWhileWeakAgainInTheCooldownWarns() {
        feed(*walkAway)
        repeatFeed(-55, 6)
        repeatFeed(-85, 8)                            // weak again, still in the cooldown
        assertEquals(ProximityState.RECOVERED, engine.state)
        assertEquals(ProximityEffect.WARN_LINK_LOST, engine.onDisconnected(DropReason.LINK_LOST))
    }

    @Test fun aLinkLostInTheCooldownWithAStrongSignalDoesNotWarn() {
        feed(*walkAway)
        repeatFeed(-55, 6)
        assertEquals(ProximityEffect.NONE, engine.onDisconnected(DropReason.LINK_LOST))
    }

    @Test fun aWarningSurvivesAReconnectAndClearsOnRecovery() {
        feed(*walkAway)
        assertEquals(ProximityEffect.WARN_LINK_LOST, engine.onDisconnected(DropReason.LINK_LOST))
        engine.onConnected()
        assertEquals(ProximityState.WARNING_SENT, engine.state)
        repeatFeed(-55, 6)
        assertEquals(ProximityEffect.CLEAR, effects.last())
    }

    @Test fun aWarningSurvivesAReconnectAndClearsInTheEar() {
        feed(*walkAway)
        engine.onDisconnected(DropReason.LINK_LOST)
        engine.onConnected()
        assertEquals(ProximityEffect.CLEAR, engine.onPlacement(Placement.WORN))
    }

    @Test fun aClosedWarningDoesNotComeBackAsALostLink() {
        feed(*walkAway)
        engine.onWarningDismissed()                   // "I have them"
        assertEquals(ProximityEffect.NONE, engine.onDisconnected(DropReason.LINK_LOST))
        engine.onConnected()
        assertEquals(ProximityState.MONITORING, engine.state)
    }

    @Test fun aWarningSentAndThenALostLinkSaysTheLinkIsGone() {
        feed(*walkAway)
        assertEquals(ProximityEffect.WARN_LINK_LOST, engine.onDisconnected(DropReason.LINK_LOST))
    }

    @Test fun aNormalDisconnectAfterAWarningStaysQuiet() {
        feed(*walkAway)
        assertEquals(ProximityEffect.NONE, engine.onDisconnected(DropReason.CASE_CLOSED))
    }
}
