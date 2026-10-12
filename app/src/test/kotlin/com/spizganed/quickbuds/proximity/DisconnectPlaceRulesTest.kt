package com.spizganed.quickbuds.proximity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisconnectPlaceRulesTest {

    private fun entry(at: Long, address: String = "AA:AA:AA:AA:AA:01") = DisconnectPlace(at, address, "Buds", 1.0, 2.0, 10f, at)

    @Test fun newestFirst() {
        val list = DisconnectPlaceRules.add(emptyList(), entry(1_000))!!
        val next = DisconnectPlaceRules.add(list, entry(100_000))!!
        assertEquals(listOf(100_000L, 1_000L), next.map { it.atMs })
    }

    @Test fun bothLinksOfOneDisconnectGiveOneEntry() {
        val list = DisconnectPlaceRules.add(emptyList(), entry(1_000))!!
        assertNull(DisconnectPlaceRules.add(list, entry(5_000)))   // LE link drops 4 s after classic
    }

    @Test fun anotherDeviceAtTheSameMomentCounts() {
        val list = DisconnectPlaceRules.add(emptyList(), entry(1_000))!!
        assertEquals(2, DisconnectPlaceRules.add(list, entry(2_000, "BB:BB:BB:BB:BB:02"))!!.size)
    }

    @Test fun historyIsCapped() {
        var list = emptyList<DisconnectPlace>()
        repeat(DisconnectPlaceRules.MAX + 5) { list = DisconnectPlaceRules.add(list, entry(it * 60_000L))!! }
        assertEquals(DisconnectPlaceRules.MAX, list.size)
        assertEquals((DisconnectPlaceRules.MAX + 4) * 60_000L, list.first().atMs)   // the oldest go
    }

    @Test fun lastKnownTakesTheNewestRecentFix() {
        val now = 100 * 60_000L
        val best = DisconnectPlaceRules.bestLastKnown(listOf(
            Fix(1.0, 1.0, 5f, now - 8 * 60_000L),
            Fix(2.0, 2.0, 50f, now - 30_000L),
            Fix(3.0, 3.0, 5f, now - 60 * 60_000L)    // an hour old: too old
        ), now)
        assertEquals(2.0, best!!.lat, 0.0)
    }

    @Test fun lastKnownPrefersTheMoreAccurateWithinTheSameMinute() {
        val now = 100 * 60_000L
        val best = DisconnectPlaceRules.bestLastKnown(listOf(
            Fix(1.0, 1.0, 80f, now - 10_000L),
            Fix(2.0, 2.0, 8f, now - 20_000L)
        ), now)
        assertEquals(2.0, best!!.lat, 0.0)
    }

    @Test fun noRecentFixIsNone() {
        val now = 100 * 60_000L
        assertNull(DisconnectPlaceRules.bestLastKnown(listOf(Fix(1.0, 1.0, 5f, now - 11 * 60_000L)), now))
    }
}
