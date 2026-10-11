package com.spizganed.quickbuds.ui

import com.spizganed.quickbuds.bluetooth.ConnectedDevices.Entry

/** What the home screen's device switcher ([DevicesTile]) shows. Plain values in, plain values out. */
object DeviceOrder {

    /**
     * [devices]: all connected, the selected one first. [strip]: two or more, so there is a choice to show.
     * [budsControls]: the supported buds are selected, so their own tiles show below.
     */
    data class Plan(val devices: List<Entry>, val strip: Boolean, val budsControls: Boolean)

    /**
     * Null while no device of another brand is connected: the home screen as before. Else the selected device is
     * [picked] while it stays connected, else the one [playing] audio, else the buds, else the first to connect.
     */
    fun plan(buds: Entry?, others: List<Entry>, picked: String?, playing: String?): Plan? {
        if (others.isEmpty()) return null
        val all = listOfNotNull(buds) + others.filter { it.address != buds?.address }
        val selected = all.firstOrNull { it.address == picked }?.address
            ?: all.firstOrNull { it.address == playing }?.address
            ?: all.first().address
        val ordered = all.sortedBy { if (it.address == selected) 0 else 1 }
        return Plan(ordered, ordered.size >= 2, buds != null && selected == buds.address)
    }
}
