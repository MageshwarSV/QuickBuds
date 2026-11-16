package com.spizganed.quickbuds.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatCallTest {

    private val t0 = 1_000_000L

    @Test fun theSameNumberWithinThreeMinutes() {
        val recent = listOf("+91 98765 43210" to t0)
        assertTrue(AutoAnswer.repeatCall("09876543210", t0 + 2 * 60_000L, recent))
        assertFalse(AutoAnswer.repeatCall("09876543210", t0 + AutoAnswer.REPEAT_WINDOW_MS + 1, recent))
        assertFalse(AutoAnswer.repeatCall("9111111111", t0 + 60_000L, recent))
        assertFalse(AutoAnswer.repeatCall(null, t0 + 60_000L, recent))
    }

    @Test fun onlyNumbersGetATextBack() {
        assertTrue(AutoReply.textable("+91 98765 43210"))
        assertFalse(AutoReply.textable(null))
        assertFalse(AutoReply.textable("12345"))
    }
}
