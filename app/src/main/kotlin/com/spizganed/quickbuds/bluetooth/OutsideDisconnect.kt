package com.spizganed.quickbuds.bluetooth

/**
 * The phone's audio to the supported buds went down, from outside the app (Android's Bluetooth settings, the quick
 * panel). Our RFCOMM link alone would keep the buds half connected: the battery card stays, the usage session runs on.
 */
object OutsideDisconnect {

    /**
     * Close our link too only when it is up, to these buds, and Bluetooth answered and lists no audio to them.
     * [connectedAudio] null: Bluetooth could not be asked, so nothing is closed.
     */
    fun shouldClose(linkUp: Boolean, linkAddress: String?, address: String, connectedAudio: Set<String>?): Boolean =
        linkUp && linkAddress == address && connectedAudio != null && address !in connectedAudio
}
