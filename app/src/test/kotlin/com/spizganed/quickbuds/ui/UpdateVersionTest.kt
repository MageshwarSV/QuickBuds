package com.spizganed.quickbuds.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    @Test fun numbersDecide() {
        assertTrue(UpdateChecker.isNewer("v4.10.16", "4.10.15"))
        assertTrue(UpdateChecker.isNewer("v4.10.10", "4.10.9"))
        assertFalse(UpdateChecker.isNewer("v4.10.15", "4.10.16"))
        assertFalse(UpdateChecker.isNewer("v4.10.16", "4.10.16"))
    }

    @Test fun aTestBuildGetsItsRelease() {
        assertTrue(UpdateChecker.isNewer("v4.10.16", "4.10.16-test1"))
        // A debug build of the same version does not (a release cannot install over it).
        assertFalse(UpdateChecker.isNewer("v4.10.16", "4.10.16-debug"))
        // A test build is never offered: the updater skips pre-releases, and it is not newer anyway.
        assertFalse(UpdateChecker.isNewer("v4.10.16-test2", "4.10.16-test1"))
    }
}
