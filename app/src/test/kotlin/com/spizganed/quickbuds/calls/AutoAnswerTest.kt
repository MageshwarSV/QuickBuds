package com.spizganed.quickbuds.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAnswerTest {

    @Test fun answersOnlyAStillRingingCallWithTheEarbudsConnected() {
        assertTrue(AutoAnswer.shouldAnswer(stillRinging = true, earbudsConnected = true))
        assertFalse(AutoAnswer.shouldAnswer(stillRinging = true, earbudsConnected = false))   // the phone alone: ring on
        assertFalse(AutoAnswer.shouldAnswer(stillRinging = false, earbudsConnected = true))   // answered or rejected first
    }

    @Test fun theDelaysOffered() {
        assertEquals(listOf(3, 5, 10, 15), AutoAnswer.DELAYS)
        assertTrue(AutoAnswer.DEFAULT_DELAY in AutoAnswer.DELAYS)
    }
}
