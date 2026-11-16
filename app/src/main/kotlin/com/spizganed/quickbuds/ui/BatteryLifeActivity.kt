package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsDevice
import com.spizganed.quickbuds.bluetooth.ConnectedDevices
import com.spizganed.quickbuds.notify.BatteryCard
import com.spizganed.quickbuds.usage.BatteryDrain
import com.spizganed.quickbuds.usage.CaseCharge
import com.spizganed.quickbuds.widget.WidgetStateStore
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * Battery life of one device (Usage statistics › Battery life › a device): left, case and right as rings, how long
 * a full charge lasts as bars (each bud, and with the case's charges), and the listening time left now: in the
 * buds, plus what the case can still give. All from history: how fast each bud's level fell while in use
 * ([BatteryDrain]), and how much case level a charge of the buds took ([CaseCharge]). Other earbuds: one ring.
 * It updates while open: at once when the buds report (a bud into the case, a new level), and every [REFRESH_MS]
 * for the times. Without the sweep again. Was: only when opened. Battery health: a full charge per month, so a
 * battery that wears shows as shorter bars.
 */
class BatteryLifeActivity : Activity() {

    private fun dp(v: Float) = ThemeRes.dp(this, v)

    private lateinit var scroll: ScrollView
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** The first build animates; updates after it do not. */
    private var animate = true
    private val refresh = object : Runnable {
        override fun run() { render(); main.postDelayed(this, REFRESH_MS) }
    }
    /** The buds reported: one redraw for a burst of reports. */
    private val soon = Runnable { render() }
    private val stateListener: (WidgetStateStore.State) -> Unit = {
        main.removeCallbacks(soon)
        main.postDelayed(soon, SETTLE_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        scroll = ScrollView(this).apply { setBackgroundColor(ThemeRes.color(this@BatteryLifeActivity, R.attr.appColorBg)) }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        WidgetStateStore.addListener(stateListener)
        main.post(refresh)
    }

    override fun onPause() {
        WidgetStateStore.removeListener(stateListener)
        main.removeCallbacks(refresh)
        main.removeCallbacks(soon)
        super.onPause()
    }

    /** Builds the screen from the history as it is now; the scroll position stays. */
    private fun render() {
        val y = scroll.scrollY
        scroll.removeAllViews()
        scroll.addView(build())
        animate = false
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun text(s: String, size: Float, secondary: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        setTextColor(ThemeRes.color(this@BatteryLifeActivity, if (secondary) R.attr.appColorTextSecondary else R.attr.appColorTextPrimary))
    }

    private fun card() = SettingRowFactory.card(this).apply { setPadding(dp(16f), dp(12f), dp(16f), dp(14f)) }

    private fun build(): LinearLayout {
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.usage_battery_title))
        root.addView(text(intent.getStringExtra(EXTRA_LABEL).orEmpty(), 14f, secondary = true).apply { setPadding(dp(4f), 0, dp(4f), dp(8f)) })

        val pair = BatteryDrain.rate(this, id)
        val st = WidgetStateStore.read(this)
        val live = st.connected && BudsDevice.find(this)?.address == id
        val snap = CaseCharge.load(this, id).snap
        if (!live && snap == null && !BatteryDrain.hasBuds(this, id)) return other(root, id, pair)

        // The levels: live while connected, else the last report with the case known.
        val left = if (live) st.leftBattery else snap?.left ?: -1
        val right = if (live) st.rightBattery else snap?.right ?: -1
        val case = if (live) st.caseBattery else snap?.case ?: -1
        val leftInUse = if (live) BatteryDrain.inUse(st.leftStatus, BatteryCard.leftCharging) else snap?.leftDocked == false
        val rightInUse = if (live) BatteryDrain.inUse(st.rightStatus, BatteryCard.rightCharging) else snap?.rightDocked == false
        val leftCharging = live && BatteryCard.leftCharging
        val rightCharging = live && BatteryCard.rightCharging
        val caseCharging = if (live) BatteryCard.caseCharging else snap?.caseCharging == true
        // Each bud's own rate; until it has enough history, the pair's.
        val leftOwn = BatteryDrain.rate(this, BatteryDrain.budId(id, true))
        val rightOwn = BatteryDrain.rate(this, BatteryDrain.budId(id, false))
        val leftRate = leftOwn ?: pair
        val rightRate = rightOwn ?: pair
        val est = CaseCharge.estimate(this, id)

        fun budLine(level: Int, inUse: Boolean, charging: Boolean, rate: BatteryDrain.Rate?) = when {
            charging -> getString(R.string.bl_charging)
            !inUse && level >= 0 -> getString(R.string.bl_in_case)
            level >= 0 && rate != null -> getString(R.string.bl_time_left, UsageFormat.duration(this, BatteryDrain.lastsMs(level, rate)))
            else -> ""
        }
        val caseLine = when {
            caseCharging -> getString(R.string.bl_charging)
            est != null && case >= 0 -> getString(R.string.bl_case_charges, one(CaseCharge.charges(est, case)))
            else -> ""
        }
        root.addView(card().apply {
            addView(BatteryRingsView(this@BatteryLifeActivity).apply {
                setRings(listOf(
                    BatteryRingsView.Ring(getString(R.string.status_left), left, budLine(left, leftInUse, leftCharging, leftRate)),
                    BatteryRingsView.Ring(getString(R.string.status_case), case, caseLine),
                    BatteryRingsView.Ring(getString(R.string.status_right), right, budLine(right, rightInUse, rightCharging, rightRate))
                ), animate)
            })
            if (!live && snap != null) addView(text(getString(R.string.bl_last_known, DateUtils.formatDateTime(this@BatteryLifeActivity, snap.atMs,
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)), 12f, secondary = true))
        })

        // A full charge: each bud, and with the case's charges on top (the bud that runs out first).
        root.addView(SettingRowFactory.sectionLabel(this, R.string.bl_full_title))
        val leftFull = leftRate?.let { BatteryDrain.lastsMs(100, it) }
        val rightFull = rightRate?.let { BatteryDrain.lastsMs(100, it) }
        val weakest = listOfNotNull(leftFull, rightFull).minOrNull()
        root.addView(card().apply {
            if (weakest == null) addView(text(getString(R.string.usage_battery_learning), 13f, secondary = true))
            else addView(BatteryBarsView(this@BatteryLifeActivity).apply {
                setBars(listOfNotNull(
                    leftFull?.let { BatteryBarsView.Bar(getString(R.string.status_left), it, UsageFormat.duration(this@BatteryLifeActivity, it)) },
                    rightFull?.let { BatteryBarsView.Bar(getString(R.string.status_right), it, UsageFormat.duration(this@BatteryLifeActivity, it)) },
                    est?.let {
                        val total = (weakest * (1 + CaseCharge.charges(it, 100))).toLong()
                        BatteryBarsView.Bar(getString(R.string.bl_with_case), total, UsageFormat.duration(this@BatteryLifeActivity, total))
                    }
                ), animate)
            })
            if (weakest != null && pair != null && (leftOwn == null || rightOwn == null))
                addView(text(getString(R.string.bl_shared), 12f, secondary = true).apply { setPadding(0, dp(6f), 0, 0) })
        })

        health(root, id)

        // Now: what the buds have left, and what the case can still give them.
        root.addView(SettingRowFactory.sectionLabel(this, R.string.bl_now_title))
        root.addView(card().apply {
            val inBuds = listOfNotNull(
                leftRate?.takeIf { left >= 0 }?.let { BatteryDrain.lastsMs(left, it) },
                rightRate?.takeIf { right >= 0 }?.let { BatteryDrain.lastsMs(right, it) }
            ).minOrNull()
            val rate = listOfNotNull(leftRate, rightRate).maxByOrNull { it.pctPerHour }
            val fromCase = if (est != null && case >= 0 && rate != null) BatteryDrain.lastsMs(CaseCharge.budPctFrom(est, case).toInt(), rate) else null
            val lines = mutableListOf<String>()
            when {
                inBuds != null && fromCase != null -> lines += getString(R.string.bl_now_total, UsageFormat.duration(this@BatteryLifeActivity, inBuds + fromCase),
                    UsageFormat.duration(this@BatteryLifeActivity, inBuds), UsageFormat.duration(this@BatteryLifeActivity, fromCase))
                inBuds != null -> lines += getString(R.string.bl_now_buds, UsageFormat.duration(this@BatteryLifeActivity, inBuds))
                else -> lines += getString(R.string.usage_battery_learning)
            }
            if (est != null) {
                lines += getString(R.string.bl_case_holds, one(CaseCharge.charges(est, 100)))
                if (case >= 0 && !caseCharging && CaseCharge.charges(est, case) < 1.0) lines += getString(R.string.bl_case_low)
            } else lines += getString(R.string.bl_case_learning)
            listOfNotNull(leftRate, rightRate).maxByOrNull { it.listenedMs }?.let {
                lines += getString(R.string.usage_battery_basis, UsageFormat.duration(this@BatteryLifeActivity, it.listenedMs))
            }
            est?.let { lines += getString(R.string.bl_case_basis, it.gainPct) }
            lines.forEachIndexed { i, l -> addView(text(l, if (i == 0) 15f else 13f, secondary = i > 0).apply { if (i > 0) setPadding(0, dp(6f), 0, 0) }) }
        })
        return root
    }

    /** Other earbuds: one level, one rate. */
    private fun other(root: LinearLayout, id: String, rate: BatteryDrain.Rate?): LinearLayout {
        val level = ConnectedDevices.all().firstOrNull { it.address == id }?.level?.takeIf { it > 0 } ?: -1
        root.addView(card().apply {
            addView(BatteryRingsView(this@BatteryLifeActivity).apply {
                setRings(listOf(BatteryRingsView.Ring(intent.getStringExtra(EXTRA_LABEL).orEmpty(), level,
                    if (level >= 0 && rate != null) getString(R.string.bl_time_left, UsageFormat.duration(this@BatteryLifeActivity, BatteryDrain.lastsMs(level, rate))) else "")), animate)
            })
        })
        root.addView(SettingRowFactory.sectionLabel(this, R.string.bl_full_title))
        root.addView(card().apply {
            if (rate == null) addView(text(getString(R.string.usage_battery_learning), 13f, secondary = true))
            else {
                val full = BatteryDrain.lastsMs(100, rate)
                addView(BatteryBarsView(this@BatteryLifeActivity).apply {
                    setBars(listOf(BatteryBarsView.Bar(intent.getStringExtra(EXTRA_LABEL).orEmpty(), full, UsageFormat.duration(this@BatteryLifeActivity, full))), animate)
                })
                addView(text(getString(R.string.usage_battery_basis, UsageFormat.duration(this@BatteryLifeActivity, rate.listenedMs)), 13f, secondary = true))
            }
        })
        health(root, id)
        return root
    }

    /**
     * Battery health: a full charge per month ([BatteryDrain.health]), oldest first, then the latest month against
     * the first one shown.
     */
    private fun health(root: LinearLayout, id: String) {
        val months = BatteryDrain.health(BatteryDrain.months(this, id))
        root.addView(SettingRowFactory.sectionLabel(this, R.string.bh_title))
        root.addView(card().apply {
            if (months.isNotEmpty()) addView(BatteryBarsView(this@BatteryLifeActivity).apply {
                setBars(months.map { (month, ms) ->
                    BatteryBarsView.Bar(monthName(month), ms, UsageFormat.duration(this@BatteryLifeActivity, ms))
                }, animate)
            })
            val line = if (months.size < 2) getString(R.string.bh_learning) else {
                val (firstMonth, first) = months.first()
                val change = BatteryDrain.changePct(first, months.last().second)
                when {
                    change < 0 -> getString(R.string.bh_less, -change, monthName(firstMonth))
                    change > 0 -> getString(R.string.bh_more, change, monthName(firstMonth))
                    else -> getString(R.string.bh_same, monthName(firstMonth))
                }
            }
            addView(text(line, 13f, secondary = true).apply { if (months.isNotEmpty()) setPadding(0, dp(6f), 0, 0) })
        })
    }

    /** The short month and the year in the user's language: "Oct 2026". */
    private fun monthName(month: YearMonth): String = DateUtils.formatDateTime(this,
        month.atDay(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR)

    /** One decimal in the user's language: "2.5", "2,5". */
    private fun one(v: Double) = String.format(Locale.getDefault(), "%.1f", v)

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        private const val REFRESH_MS = 30_000L
        private const val SETTLE_MS = 300L

        fun open(c: Context, id: String, label: String) =
            c.startActivity(Intent(c, BatteryLifeActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_LABEL, label))
    }
}
