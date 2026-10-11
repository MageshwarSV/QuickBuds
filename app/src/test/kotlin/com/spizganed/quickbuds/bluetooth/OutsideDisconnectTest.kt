package com.spizganed.quickbuds.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutsideDisconnectTest {

    private val buds = "BU:DS"

    @Test fun disconnectedInSettingsClosesOurLink() {
        assertTrue(OutsideDisconnect.shouldClose(true, buds, buds, emptySet()))
        // Another headset still connected does not keep ours.
        assertTrue(OutsideDisconnect.shouldClose(true, buds, buds, setOf("NE:CK")))
    }

    @Test fun audioBackOrStillThereKeepsOurLink() =
        assertFalse(OutsideDisconnect.shouldClose(true, buds, buds, setOf(buds, "NE:CK")))

    @Test fun bluetoothCouldNotBeAskedKeepsOurLink() =
        assertFalse(OutsideDisconnect.shouldClose(true, buds, buds, null))

    @Test fun ourLinkAlreadyDownDoesNothing() =
        assertFalse(OutsideDisconnect.shouldClose(false, buds, buds, emptySet()))

    @Test fun anotherPairOfBudsDoesNotCloseOurs() =
        assertFalse(OutsideDisconnect.shouldClose(true, buds, "OT:HR", emptySet()))
}
