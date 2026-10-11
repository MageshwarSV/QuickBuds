package com.spizganed.quickbuds.ui

import android.app.Activity
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * Find my earbuds, a bottom sheet like the fit test: the buds' OWN locator tone, `0x0400` `01` start /
 * `00` stop (`[CAPTURE]` 2026-09-23, PROTOCOL.md §9). It rings BOTH buds at once (no side byte; HeyMelody
 * offers no per-bud choice). Like HeyMelody, starting it while a bud reports being in an ear (wear status
 * 3/7) asks first. Closing the sheet sends the stop, so the tone cannot outlive it.
 */
class FindBudsSheet(
    private val activity: Activity,
    private val send: (Boolean) -> Unit,
    /** Opens Find nearby (distance and arrow); null hides the button. */
    private val navigate: (() -> Unit)? = null
) {

    private val sheet = BottomSheetDialog(activity)
    private var playing = false

    fun show() {
        sheet.title(activity.getString(R.string.find_title))
            .message(activity.getString(R.string.find_hint))
            .onDismiss { if (playing) send(false) }
        render()
        sheet.show()
    }

    private fun render() {
        sheet.confirm(activity.getString(if (playing) R.string.find_stop else R.string.find_play)) {
            if (playing) setTone(false) else startWithWarning()
        }
        // Closing first: the sheet's onDismiss stops a tone it started, so none rings on untracked.
        navigate?.let { go -> sheet.cancel(activity.getString(R.string.find_nav_title)) { sheet.close(); go() } }
    }

    private fun startWithWarning() = confirmIfWorn(activity) { setTone(true) }

    private fun setTone(on: Boolean) {
        playing = on
        send(on)
        render()
    }

    companion object {
        /** A bud in an ear (wear status 3/7): the tone is loud, so ask first, as HeyMelody does. Else [go] at once. */
        fun confirmIfWorn(activity: Activity, go: () -> Unit) {
            val st = WidgetStateStore.read(activity)
            if (listOf(st.leftStatus, st.rightStatus).none { it == 3 || it == 7 }) return go()
            val warn = BottomSheetDialog(activity)
            warn.title(activity.getString(R.string.find_warn_title))
                .message(activity.getString(R.string.find_warn_msg))
                .confirm(activity.getString(R.string.find_warn_play)) { warn.close(); go() }
                .show()
        }
    }
}
