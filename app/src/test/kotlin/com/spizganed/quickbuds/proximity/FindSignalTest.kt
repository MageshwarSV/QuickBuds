package com.spizganed.quickbuds.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindSignalTest {

    private val cfg = FindConfig()

    private fun processor(vararg raw: Int, scale: RssiScale = RssiScale.DBM, stepMs: Long = 300): RssiProcessor =
        RssiProcessor(scale).apply { raw.forEachIndexed { i, r -> add(r, i * stepMs) } }

    // ==================== RSSI filtering ====================

    @Test fun stableReadingsFilterToTheirValue() {
        val p = processor(*IntArray(12) { -60 })
        assertEquals(-60.0, p.filtered!!, 0.001)
        assertEquals(Stability.STABLE, p.stability())
    }

    @Test fun aLoneSpikeUpOrDownBarelyMovesTheFilter() {
        val up = processor(-60, -60, -60, -60, -30, -60, -60)
        val down = processor(-60, -60, -60, -60, -95, -60, -60)
        assertEquals(-60.0, up.filtered!!, 0.5)
        assertEquals(-60.0, down.filtered!!, 0.5)
    }

    @Test fun alternatingNoiseStaysBetweenTheSwings() {
        val p = processor(*IntArray(20) { if (it % 2 == 0) -55 else -70 })
        assertTrue(p.filtered!! in -70.0..-55.0)
        assertEquals(Stability.FLUCTUATING, p.stability())
    }

    @Test fun aSustainedChangeIsFollowed() {
        val p = processor(*(IntArray(8) { -70 } + IntArray(16) { -50 }))
        assertEquals(-50.0, p.filtered!!, 1.5)
    }

    @Test fun weakButValidReadingsAreKept() {
        val p = processor(-95, -96, -94, -95, -95)
        assertEquals(5, p.count)
        assertEquals(-95.0, p.filtered!!, 1.0)
    }

    @Test fun impossibleReadingsAreRejected() {
        val p = RssiProcessor(RssiScale.DBM)
        assertFalse(p.add(127, 0))     // Android's "not available"
        assertFalse(p.add(5, 0))       // no received signal is above 0 dBm
        assertFalse(p.add(-128, 0))
        assertEquals(0, p.count)
        assertNull(p.filtered)
        val g = RssiProcessor(RssiScale.GOLDEN_RANGE)
        assertTrue(g.add(9, 0))        // golden range: +9 above the range is real (measured touching the phone)
        assertFalse(g.add(25, 0))
    }

    @Test fun emptyHistoryHasNoValues() {
        val p = RssiProcessor(RssiScale.DBM)
        assertNull(p.filtered)
        assertNull(p.stability())
        assertFalse(p.ready(0))
        assertTrue(p.samples(0).isEmpty())
    }

    @Test fun tooFewReadingsAreNotReady() {
        assertFalse(processor(-60, -61, -60, -59).ready(900))
        assertNull(processor(-60, -61, -60, -59).filtered)      // no average from a partial median window
        assertTrue(processor(-60, -61, -60, -59, -60).ready(1_200))
    }

    @Test fun aStrongFirstReadingDoesNotSeedTheAverage() {
        // Found in review: -37 then -52 x3 seeded the average at -37 and gave "very close".
        val p = processor(-37, -52, -52, -52, -52)
        assertEquals(-52.0, p.filtered!!, 0.001)
    }

    @Test fun walkingTowardTheDeviceIsATrendNotInstability() {
        // Found in review: -70 x5 then a steady approach to -44 read as unstable.
        val p = RssiProcessor(RssiScale.DBM)
        for (i in 0 until 10) p.add(-70 + i * 3, i * 400L)
        assertEquals(Stability.STABLE, p.stability())
    }

    @Test fun staleReadingsAreNotReady() {
        val p = processor(-60, -60, -60, -60, -60)               // last at 1200 ms
        assertTrue(p.ready(1_200 + cfg.staleMs))
        assertFalse(p.ready(1_200 + cfg.staleMs + 1))
        assertTrue(p.stale(1_200 + cfg.staleMs + 1))
    }

    @Test fun historyIsBoundedInTimeAndCount() {
        val p = RssiProcessor(RssiScale.DBM)
        for (i in 0 until 1000) p.add(-60, i * 100L)             // 100 s of readings
        val s = p.samples(99_900)
        assertTrue(s.size <= cfg.historyMax)
        assertTrue(s.first().atMs >= 99_900 - cfg.historyMs)
    }

    @Test fun resetStartsOver() {
        val p = processor(-60, -60, -60, -60)
        p.reset()
        assertEquals(0, p.count)
        assertNull(p.filtered)
    }

    // ==================== Trend ====================

    private fun ramp(from: Int, to: Int, n: Int = 20, stepMs: Long = 400): RssiProcessor =
        RssiProcessor(RssiScale.DBM).apply { for (i in 0 until n) add(from + (to - from) * i / (n - 1), i * stepMs) }

    @Test fun aSteadyRiseIsImproving() {
        val p = ramp(-75, -55)
        assertEquals(Trend.IMPROVING, SignalTrendAnalyzer.trend(p.samples(7_600), p.stability(), 7_600))
    }

    @Test fun aSteadyFallIsWeakening() {
        val p = ramp(-55, -75)
        assertEquals(Trend.WEAKENING, SignalTrendAnalyzer.trend(p.samples(7_600), p.stability(), 7_600))
    }

    @Test fun aFlatSignalIsSteady() {
        val p = ramp(-60, -61)
        assertEquals(Trend.STEADY, SignalTrendAnalyzer.trend(p.samples(7_600), p.stability(), 7_600))
    }

    @Test fun oneStrongReadingIsNoTrend() {
        val p = processor(-70, -70, -70, -70, -70, -40)
        assertEquals(Trend.UNKNOWN, SignalTrendAnalyzer.trend(p.samples(1_500), p.stability(), 1_500))
    }

    @Test fun anUnstableSignalIsFluctuatingNotATrend() {
        val p = processor(*IntArray(20) { if (it % 2 == 0) -45 else -85 }, stepMs = 400)
        assertEquals(Stability.UNSTABLE, p.stability())
        assertEquals(Trend.FLUCTUATING, SignalTrendAnalyzer.trend(p.samples(7_600), p.stability(), 7_600))
    }

    // ==================== Distance ====================

    @Test fun theLogDistanceModelOnTheStatedLevel() {
        // Measured: stated 1 m level -52 dBm, -53 median at about 1 m.
        assertEquals(1.1, DistanceEstimate.metres(-52.0, -53.0)!!, 0.05)
        // Textbook check of the model: 10 dB under the 1 m level at n = 2.2 is 10 ^ (10 / 22) = 2.85 m.
        assertEquals(2.85, DistanceEstimate.metres(-55.0, -65.0)!!, 0.01)
    }

    @Test fun closerThanTheRangeShowsItsFloor() =
        assertEquals(DistanceEstimate.MIN_M, DistanceEstimate.metres(-52.0, -30.0)!!, 0.0)

    @Test fun beyondTheRangeIsNoNumber() {
        assertNull(DistanceEstimate.metres(-52.0, -90.0))            // about 53 m: beyond 30 m
        assertNull(DistanceEstimate.metres(-52.0, -1000.0))          // no overflow, no crash
        assertEquals(10.5, DistanceEstimate.metres(-52.0, -74.5)!!, 0.5)
    }

    @Test fun shownDistancesHaveHonestPrecision() {
        assertEquals(2.5, DistanceEstimate.rounded(2.4), 0.0)
        assertEquals(1.5, DistanceEstimate.rounded(1.3), 0.0)
        assertEquals(7.0, DistanceEstimate.rounded(6.6), 0.0)
    }

    // ==================== Proximity states ====================

    private val th = FindThresholds.dbm(-52.0)

    private class Run(val c: ProximityClassifier = ProximityClassifier()) {
        fun at(filtered: Double?, stability: Stability? = Stability.STABLE, ready: Boolean = true, seen: Boolean = true,
               searchingMs: Long = 20_000, linkUp: Boolean = true, on: Boolean = true, th: FindThresholds? = FindThresholds.dbm(-52.0)) =
            c.update(filtered, ready, stability, th, seen, searchingMs, linkUp, on)
    }

    @Test fun startsSearchingThenSaysNotDetected() {
        val r = Run()
        assertEquals(ProximityLevel.SEARCHING, r.at(null, seen = false, searchingMs = 1_000))
        assertEquals(ProximityLevel.NO_SIGNAL, r.at(null, seen = false, searchingMs = ProximityClassifier.SEARCH_GRACE_MS))
    }

    @Test fun readingsWithoutEnoughSamplesAreCalibrating() =
        assertEquals(ProximityLevel.CALIBRATING, Run().at(-50.0, ready = false))

    @Test fun firstJudgementNeedsNoStreak() = assertEquals(ProximityLevel.NEARBY, Run().at(-55.0))

    @Test fun veryCloseNeedsAStreakOfStrongReadings() {
        val r = Run()
        r.at(-55.0)                                             // nearby
        assertEquals(ProximityLevel.NEARBY, r.at(-40.0))
        assertEquals(ProximityLevel.NEARBY, r.at(-40.0))
        assertEquals(ProximityLevel.VERY_CLOSE, r.at(-40.0))   // third update in a row
    }

    @Test fun oneStrongReadingIsNotHere() {
        val r = Run()
        r.at(-55.0)
        r.at(-40.0)
        assertEquals(ProximityLevel.NEARBY, r.at(-55.0))
        assertEquals(ProximityLevel.NEARBY, r.at(-40.0))       // the streak started over
    }

    @Test fun anUnstableSignalIsNeverHere() {
        val r = Run()
        r.at(-55.0)
        repeat(5) { r.at(-40.0, stability = Stability.UNSTABLE) }
        assertEquals(ProximityLevel.SIGNAL_UNSTABLE, r.c.level)
    }

    @Test fun hysteresisHoldsNearTheEdge() {
        val r = Run()
        r.at(-55.0); repeat(3) { r.at(-44.0) }                  // very close (enter at -45)
        assertEquals(ProximityLevel.VERY_CLOSE, r.c.level)
        repeat(6) { r.at(-48.0) }                               // under the entry, above the exit (-49)
        assertEquals(ProximityLevel.VERY_CLOSE, r.c.level)
        repeat(3) { r.at(-51.0) }
        assertEquals(ProximityLevel.NEARBY, r.c.level)
    }

    @Test fun nearbyHoldsBetweenItsEdges() {
        val r = Run()
        r.at(-60.0)
        repeat(6) { r.at(-64.0) }                               // under nearby entry (-62), above its exit (-66)
        assertEquals(ProximityLevel.NEARBY, r.c.level)
        repeat(3) { r.at(-70.0) }
        assertEquals(ProximityLevel.FAR, r.c.level)
    }

    @Test fun veryCloseFromFreshReadingsNeedsTheStreak() {
        val r = Run()
        r.at(-50.0, ready = false)                              // calibrating
        assertEquals(ProximityLevel.NEARBY, r.at(-40.0))        // strong at once: nearby, not yet very close
        assertEquals(ProximityLevel.NEARBY, r.at(-40.0))
        assertEquals(ProximityLevel.VERY_CLOSE, r.at(-40.0))
    }

    @Test fun afterALossVeryCloseNeedsTheStreakAgain() {
        val r = Run()
        r.at(-55.0); repeat(3) { r.at(-40.0) }
        assertEquals(ProximityLevel.VERY_CLOSE, r.c.level)
        r.at(null)                                              // lost
        assertEquals(ProximityLevel.NEARBY, r.at(-40.0))
    }

    @Test fun sustainedImprovementReachesVeryClose() {
        val r = Run()
        r.at(-75.0)
        assertEquals(ProximityLevel.FAR, r.c.level)
        repeat(3) { r.at(-58.0) }
        assertEquals(ProximityLevel.NEARBY, r.c.level)
        repeat(3) { r.at(-42.0) }
        assertEquals(ProximityLevel.VERY_CLOSE, r.c.level)
    }

    @Test fun lossIsImmediateAndRecoveryNeedsNoStreak() {
        val r = Run()
        r.at(-55.0)
        assertEquals(ProximityLevel.NO_SIGNAL, r.at(null))
        assertEquals(ProximityLevel.NEARBY, r.at(-55.0))       // back after a gap: judged at once
    }

    @Test fun noConnectionAndNoBroadcastIsDisconnected() {
        val r = Run()
        r.at(-55.0)
        assertEquals(ProximityLevel.DEVICE_DISCONNECTED, r.at(null, linkUp = false))
    }

    @Test fun bluetoothOffWinsOverEverything() = assertEquals(ProximityLevel.BLUETOOTH_OFF, Run().at(-40.0, on = false))

    @Test fun goldenRangeOneMetreIsNearbyAndTouchingIsHere() {
        val r = Run()
        assertEquals(ProximityLevel.NEARBY, r.at(0.0, th = FindThresholds.GOLDEN))
        repeat(3) { r.at(6.0, th = FindThresholds.GOLDEN) }  // measured up to +9 touching the phone
        assertEquals(ProximityLevel.VERY_CLOSE, r.c.level)
    }

    @Test fun beingConnectedIsNotProximity() =
        // A link that answers but no fresh reading: never a proximity state.
        assertEquals(ProximityLevel.NO_SIGNAL, Run().at(null, linkUp = true))

    // ==================== Direction ====================

    @Test fun noDirectionWithoutRangingOnBothSides() {
        assertFalse(DirectionSupport.available(phoneUwb = true, phoneChannelSounding = true, peerRanging = false))
        assertFalse(DirectionSupport.available(phoneUwb = false, phoneChannelSounding = false, peerRanging = true))
        assertTrue(DirectionSupport.available(phoneUwb = false, phoneChannelSounding = true, peerRanging = true))
    }

    // ==================== Sound ====================

    @Test fun soundSucceedsOnlyWhenTheEarbudsAnswer() {
        val s = FindSound()
        assertTrue(s.request(true, 0))
        assertEquals(FindSound.State.PENDING, s.state)
        s.onAck(true)
        assertEquals(FindSound.State.PLAYING, s.state)
    }

    @Test fun noAnswerIsAFailure() {
        val s = FindSound(timeoutMs = 3_000)
        s.request(true, 0)
        s.tick(2_999)
        assertEquals(FindSound.State.PENDING, s.state)
        s.tick(3_000)
        assertEquals(FindSound.State.FAILED, s.state)
    }

    @Test fun aRefusedCommandIsAFailure() {
        val s = FindSound()
        s.request(true, 0)
        s.onAck(false)
        assertEquals(FindSound.State.FAILED, s.state)
    }

    @Test fun aSecondPressWhilePendingSendsNothing() {
        val s = FindSound()
        assertTrue(s.request(true, 0))
        assertFalse(s.request(true, 100))
    }

    @Test fun unsupportedAndDisconnectedSendNothing() {
        val s = FindSound()
        s.onCapability(supported = false, connected = true)
        assertFalse(s.request(true, 0))
        assertEquals(FindSound.State.UNSUPPORTED, s.state)
        s.onCapability(supported = true, connected = false)
        assertFalse(s.request(true, 0))
        assertEquals(FindSound.State.DISCONNECTED, s.state)
        s.onCapability(supported = true, connected = true)
        assertTrue(s.request(true, 0))
    }

    @Test fun stoppingReturnsToIdle() {
        val s = FindSound()
        s.request(true, 0); s.onAck(true)
        s.request(false, 500); s.onAck(true)
        assertEquals(FindSound.State.IDLE, s.state)
        assertFalse(s.playing)
    }

    @Test fun aLateAnswerAfterTheTimeoutChangesNothing() {
        val s = FindSound(timeoutMs = 3_000)
        s.request(true, 0); s.tick(3_000)
        s.onAck(true)
        assertEquals(FindSound.State.FAILED, s.state)
    }

    // ==================== Link scale ====================

    @Test fun nearZeroIsTheGoldenRange() {
        val l = LinkScale()
        assertFalse(l.onReading(0)); assertFalse(l.onReading(-2))
        assertTrue(l.onReading(0))
        assertEquals(RssiScale.GOLDEN_RANGE, l.scale)
    }

    @Test fun usualDbmIsDbm() {
        val l = LinkScale()
        l.onReading(-60); l.onReading(-62); l.onReading(-58)
        assertEquals(RssiScale.DBM, l.scale)
    }

    @Test fun aGoldenLinkOpenedFarAwayIsCorrected() {
        // Found in review: opened at golden -35 the link looked like dBm, then 0 read as touching.
        val l = LinkScale()
        l.onReading(-35); l.onReading(-34); l.onReading(-36)
        assertEquals(RssiScale.DBM, l.scale)
        assertTrue(l.onReading(0))
        assertEquals(RssiScale.GOLDEN_RANGE, l.scale)
    }

    @Test fun aWeakDbmLinkIsCorrectedFromGolden() {
        val l = LinkScale()
        l.onReading(-20); l.onReading(-25); l.onReading(-28)
        assertEquals(RssiScale.GOLDEN_RANGE, l.scale)
        assertTrue(l.onReading(-70))
        assertEquals(RssiScale.DBM, l.scale)
    }

    @Test fun theSoundFollowsTheLossCard() {
        val s = FindSound()
        s.syncPlaying(true)
        assertTrue(s.playing)
        s.syncPlaying(false)
        assertEquals(FindSound.State.IDLE, s.state)
    }

    // ==================== Coach ====================

    private fun coach(c: SearchCoach, level: ProximityLevel, trend: Trend = Trend.STEADY, fast: Boolean = false,
                      play: Boolean = true, seen: Boolean = true, at: Long) = c.update(level, trend, fast, play, seen, at)

    @Test fun theCoachSaysWaitWhileReadingsComeIn() {
        val c = SearchCoach()
        assertEquals(SearchCoach.Tip.SEARCHING, coach(c, ProximityLevel.SEARCHING, seen = false, at = 0))
        assertEquals(SearchCoach.Tip.WAIT, coach(c, ProximityLevel.CALIBRATING, at = 5_000))
    }

    @Test fun movingFastIsToldToSlowDownAtOnce() {
        val c = SearchCoach()
        coach(c, ProximityLevel.NEARBY, trend = Trend.IMPROVING, at = 0)
        assertEquals(SearchCoach.Tip.SLOW, coach(c, ProximityLevel.NEARBY, trend = Trend.IMPROVING, fast = true, at = 500))
    }

    @Test fun anUnsteadySignalAsksToHoldSteady() =
        assertEquals(SearchCoach.Tip.STEADY, coach(SearchCoach(), ProximityLevel.SIGNAL_UNSTABLE, at = 0))

    @Test fun closerAndWeakerFollowTheTrend() {
        assertEquals(SearchCoach.Tip.CLOSER, coach(SearchCoach(), ProximityLevel.FAR, trend = Trend.IMPROVING, at = 0))
        assertEquals(SearchCoach.Tip.WEAKER, coach(SearchCoach(), ProximityLevel.NEARBY, trend = Trend.WEAKENING, at = 0))
    }

    @Test fun noChangeForLongSuggestsAnotherDirection() {
        val c = SearchCoach(noChangeMs = 15_000)
        assertEquals(SearchCoach.Tip.FAR, coach(c, ProximityLevel.FAR, at = 0))
        assertEquals(SearchCoach.Tip.TRY_OTHER, coach(c, ProximityLevel.FAR, at = 15_000))
    }

    @Test fun rightHereNamesPlaySoundOnlyWhenThereIsOne() {
        assertEquals(SearchCoach.Tip.HERE_PLAY, coach(SearchCoach(), ProximityLevel.VERY_CLOSE, play = true, at = 0))
        assertEquals(SearchCoach.Tip.HERE, coach(SearchCoach(), ProximityLevel.VERY_CLOSE, play = false, at = 0))
    }

    @Test fun aLostSignalIsSaidAtOnce() {
        val c = SearchCoach()
        coach(c, ProximityLevel.NEARBY, at = 0)
        assertEquals(SearchCoach.Tip.LOST, coach(c, ProximityLevel.NO_SIGNAL, at = 100))
    }

    @Test fun messagesDoNotFlicker() {
        val c = SearchCoach(holdMs = 3_000)
        assertEquals(SearchCoach.Tip.CLOSER, coach(c, ProximityLevel.NEARBY, trend = Trend.IMPROVING, at = 0))
        assertEquals(SearchCoach.Tip.CLOSER, coach(c, ProximityLevel.NEARBY, trend = Trend.WEAKENING, at = 1_000))
        assertEquals(SearchCoach.Tip.WEAKER, coach(c, ProximityLevel.NEARBY, trend = Trend.WEAKENING, at = 3_000))
    }

    @Test fun tryAnotherWayWaitsAfterAStateChange() {
        // Found in review: right after "here", a long-steady trend fired "try another way" at once.
        val c = SearchCoach(noChangeMs = 15_000)
        c.update(ProximityLevel.VERY_CLOSE, Trend.STEADY, false, true, true, 0)
        assertEquals(SearchCoach.Tip.FAR, c.update(ProximityLevel.FAR, Trend.STEADY, false, true, true, 20_000))
        assertEquals(SearchCoach.Tip.TRY_OTHER, c.update(ProximityLevel.FAR, Trend.STEADY, false, true, true, 35_000))
    }

    @Test fun tryAnotherWayOnlyWhenWeak() {
        val c = SearchCoach(noChangeMs = 15_000)
        c.update(ProximityLevel.NEARBY, Trend.STEADY, false, true, true, 0)
        assertEquals(SearchCoach.Tip.NEARBY, c.update(ProximityLevel.NEARBY, Trend.STEADY, false, true, true, 30_000))
    }

    @Test fun theTipFollowsTheBandNotJustTheState() {
        // Found in review: FAR at 3-5 m showed the band "near" with the tip "weak signal".
        assertEquals(SearchCoach.Tip.NEARBY, SearchCoach().update(ProximityLevel.FAR, Trend.STEADY, false, true, true, 0, near = true))
        assertEquals(SearchCoach.Tip.FAR, SearchCoach().update(ProximityLevel.NEARBY, Trend.STEADY, false, true, true, 0, near = false))
    }

    @Test fun turningForTheArrowSilencesTheTrendTips() {
        val c = SearchCoach()
        assertEquals(SearchCoach.Tip.NONE, c.update(ProximityLevel.NEARBY, Trend.WEAKENING, false, true, true, 0, turning = true))
        // Urgent ones still speak.
        assertEquals(SearchCoach.Tip.SLOW, c.update(ProximityLevel.NEARBY, Trend.WEAKENING, true, true, true, 100, turning = true))
    }

    // ==================== Motion ====================

    @Test fun walkingSlowlyIsNotFast() {
        val m = MotionMeter()
        repeat(50) { m.add(1.2) }
        assertFalse(m.fast)
    }

    @Test fun swingingThePhoneIsFastUntilItCalms() {
        val m = MotionMeter()
        repeat(30) { m.add(6.0) }
        assertTrue(m.fast)
        repeat(5) { m.add(2.0) }
        assertTrue(m.fast)                                       // hysteresis: still settling
        repeat(40) { m.add(0.3) }
        assertFalse(m.fast)
    }

    // ==================== Bands ====================

    @Test fun eachDistanceHasItsBand() {
        assertEquals(DistanceBand.REACHED, DistanceBand.of(0.7, null))
        assertEquals(DistanceBand.NEAR, DistanceBand.of(3.0, null))
        assertEquals(DistanceBand.BETTER, DistanceBand.of(8.0, null))
        assertEquals(DistanceBand.CLOSER, DistanceBand.of(15.0, null))
        assertEquals(DistanceBand.WEAK, DistanceBand.of(25.0, null))
        assertEquals(DistanceBand.BEYOND, DistanceBand.of(null, null))
    }

    @Test fun aDistanceAtAnEdgeDoesNotFlicker() {
        assertEquals(DistanceBand.NEAR, DistanceBand.of(5.4, DistanceBand.NEAR))      // 5.4 m, just past 5: still near
        assertEquals(DistanceBand.BETTER, DistanceBand.of(6.5, DistanceBand.NEAR))    // clearly past: better
        assertEquals(DistanceBand.BETTER, DistanceBand.of(4.6, DistanceBand.BETTER))  // just under 5 again: still better
        assertEquals(DistanceBand.NEAR, DistanceBand.of(4.0, DistanceBand.BETTER))
    }

    @Test fun theGoldenRangeMapsOnlyToFarBands() {
        assertEquals(-60.0, DistanceEstimate.goldenAsDbm(5.0), 0.0)   // touching reads +5: still only the 1 m edge
        assertEquals(-70.0, DistanceEstimate.goldenAsDbm(-10.0), 0.0)
    }
}
