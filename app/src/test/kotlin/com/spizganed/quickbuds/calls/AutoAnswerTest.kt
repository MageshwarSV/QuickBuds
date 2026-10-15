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

    @Test fun oneNumberWrittenManyWays() {
        assertTrue(AutoAnswer.sameNumber("+91 98765-43210", "09876543210"))
        assertTrue(AutoAnswer.sameNumber("98765 43210", "+919876543210"))
        assertFalse(AutoAnswer.sameNumber("+91 98765 43210", "+91 98765 43211"))
        assertTrue(AutoAnswer.sameNumber("121", "121"))          // a short service number must match exactly
        assertFalse(AutoAnswer.sameNumber("121", "9876543121"))
        assertFalse(AutoAnswer.sameNumber("", ""))
    }

    private val mom = AutoAnswer.Contact("Mom", "+91 98765 43210")

    @Test fun onlyChosenContacts() {
        val f = AutoAnswer.Filter.ONLY
        assertTrue(AutoAnswer.callerAllowed(f, "9876543210", listOf(mom)))
        assertFalse(AutoAnswer.callerAllowed(f, "9000000000", listOf(mom)))
        assertFalse(AutoAnswer.callerAllowed(f, null, listOf(mom)))   // hidden number: not a chosen contact
    }

    @Test fun allExceptChosenContacts() {
        val f = AutoAnswer.Filter.EXCEPT
        assertFalse(AutoAnswer.callerAllowed(f, "09876543210", listOf(mom)))
        assertTrue(AutoAnswer.callerAllowed(f, "9000000000", listOf(mom)))
        assertTrue(AutoAnswer.callerAllowed(f, null, listOf(mom)))
    }

    @Test fun allCallsIgnoresTheList() {
        assertTrue(AutoAnswer.callerAllowed(AutoAnswer.Filter.ALL, null, listOf(mom)))
        assertTrue(AutoAnswer.callerAllowed(AutoAnswer.Filter.ALL, "9876543210", emptyList()))
    }
}
