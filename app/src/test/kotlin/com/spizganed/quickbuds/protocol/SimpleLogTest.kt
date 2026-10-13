package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleLogTest {

    @Test fun theVersionLineShows() {
        val e = SimpleLog.of("APP: QuickBuds 4.10.9 (45) started, Android 16, OnePlus CPH2717")!!
        assertEquals("QuickBuds 4.10.9 (45) started, Android 16, OnePlus CPH2717", e.text)
        assertEquals(SimpleLog.Kind.CONNECTION, e.kind)
    }

    @Test fun whichDeviceIsTheBudsShows() {
        assertEquals("Buds: Airdopes 800 dropped (boAt, another brand)",
            SimpleLog.of("BUDS: Airdopes 800 dropped (boAt, another brand)")!!.text)
    }

    @Test fun otherEarbudsAndFastPairShowAsReceivedNotAsProblems() {
        // "not listening" must not be caught by the generic problem words.
        val other = SimpleLog.of("OTHER EARBUDS: Fast Pair battery not listening (1 other device(s) NECKBAND, card on)")!!
        assertEquals(SimpleLog.Kind.RECEIVED, other.kind)
        val fp = SimpleLog.of("FASTPAIR: none fits (standard 70): [-60 dBm L=20 R=25 C=90]")!!
        assertEquals("Fast Pair battery: none fits (standard 70): [-60 dBm L=20 R=25 C=90]", fp.text)
        assertEquals(SimpleLog.Kind.RECEIVED, fp.kind)
    }
}
