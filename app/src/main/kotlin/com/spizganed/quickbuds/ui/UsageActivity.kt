package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.usage.DayUsage
import com.spizganed.quickbuds.usage.Usage
import com.spizganed.quickbuds.usage.UsageRetention
import com.spizganed.quickbuds.usage.UsageStats
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Usage statistics: today / this week / this month at the top, then a Day, Week or Month chart with its
 * figures, from the usage database ([Usage]). Every number and bar is real data; with none, the screen
 * says so and draws no chart. Arrows step back through the kept history; an optional device filter
 * recomputes everything for one pair of buds.
 */
class UsageActivity : Activity() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var snapshot: Usage.Snapshot? = null
    private var days: Map<LocalDate, DayUsage> = emptyMap()

    private var tab = TAB_DAY
    private var device: String? = null
    /**
     * The day, week and month the user stepped back to; null while on the current one, so the screen follows
     * midnight. Was: set on every draw, so an open screen stayed on yesterday after midnight.
     */
    private var selectedDay: LocalDate? = null
    /** The day the ring last showed: its arc sweeps in only when the day changes, not on the minute refresh. */
    private var ringShown: LocalDate? = null
    private var week: LocalDate? = null
    private var month: YearMonth? = null
    /** The week or month bar whose bubble is open; kept across the minute refresh, dropped when the view changes. */
    private var bubbleAt = -1

    private val changed: () -> Unit = { reload() }
    private val tick = object : Runnable {
        override fun run() { reload() }
    }
    /** The minute refresh runs only on screen: a load that ends after onPause must not start it again. */
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt(STATE_TAB) ?: TAB_DAY
        // From the Model screen's Your earbuds: open on that pair.
        device = savedInstanceState?.getString(STATE_DEVICE) ?: intent.getStringExtra(EXTRA_DEVICE)
        // Where the user stepped back to survives a rotation.
        selectedDay = savedInstanceState?.getString(STATE_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        week = savedInstanceState?.getString(STATE_WEEK)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        month = savedInstanceState?.getString(STATE_MONTH)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }

        root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.usage_title))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@UsageActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
        outState.putString(STATE_DEVICE, device)
        outState.putString(STATE_DAY, selectedDay?.toString())
        outState.putString(STATE_WEEK, week?.toString())
        outState.putString(STATE_MONTH, month?.toString())
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Usage.addListener(changed)
        reload()
    }

    override fun onPause() {
        resumed = false
        Usage.removeListener(changed)
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun reload() {
        Usage.load(this) { s ->
            if (isFinishing || isDestroyed) return@load
            snapshot = s
            if (device != null && s.devices.none { it.id == device }) device = null
            days = UsageStats.byDay(s.spans, s.summaries, zone, device)
            render()
            // An open session grows: redraw it every minute while the screen is up.
            handler.removeCallbacks(tick)
            if (s.hasOpenSession && resumed) handler.postDelayed(tick, REFRESH_MS)
        }
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(snapshot?.nowMs ?: System.currentTimeMillis()).atZone(zone).toLocalDate()

    private fun render() {
        val s = snapshot ?: return
        content.removeAllViews()
        if (!Usage.enabled(this)) content.addView(note(getString(R.string.usage_tracking_off)))
        if (s.isEmpty) {
            content.addView(emptyState())
            return
        }
        val today = today()
        if (s.devices.size > 1) content.addView(deviceRow(s))
        content.addView(summaryCards(today))
        content.addView(batteryLife(s))

        content.addView(AncSegmentedView(this, listOf(
            getString(R.string.usage_tab_day), getString(R.string.usage_tab_week), getString(R.string.usage_tab_month)
        )).apply {
            selected = tab
            onSegmentTapped = { i -> if (i != tab) { tab = i; bubbleAt = -1; render() } }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16f) }
        })
        when (tab) {
            TAB_DAY -> renderDay(today)
            TAB_WEEK -> renderWeek(today)
            else -> renderMonth(today)
        }
    }

    // --- Top: today, this week, this month ---

    private fun summaryCards(today: LocalDate): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8f) }
        }
        val periods = listOf(
            R.string.usage_today to UsageStats.period(days, today, today, today),
            R.string.usage_this_week to UsageStats.week(days, today, today),
            R.string.usage_this_month to UsageStats.month(days, YearMonth.from(today), today)
        )
        periods.forEachIndexed { i, (label, p) ->
            row.addView(SettingRowFactory.card(this, SettingRowFactory.SPLIT_RADIUS).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i > 0) marginStart = dp(8f) }
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                addView(text(getString(label), 12f, secondary = true))
                addView(text(UsageFormat.duration(this@UsageActivity, p.totalMs), 19f, bold = true).apply { setPadding(0, dp(4f), 0, dp(2f)) })
                addView(text(getString(R.string.usage_sessions_count, p.sessions), 12f, secondary = true))
            })
        }
        return row
    }

    // --- Battery life: how long a charge lasts, from how fast the level fell while listening ---

    private fun batteryLife(s: Usage.Snapshot): View {
        val card = SettingRowFactory.card(this).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8f) }
        }
        card.addView(text(getString(R.string.usage_battery_title), 13f, secondary = true).apply {
            setPadding(dp(16f), dp(12f), dp(16f), 0)
        })
        for (d in s.devices.filter { device == null || it.id == device }) {
            val rate = com.spizganed.quickbuds.usage.BatteryDrain.rate(this, d.id)
            val now = levelNow(d.id)
            val lines = if (rate == null) listOf(getString(R.string.usage_battery_learning)) else listOfNotNull(
                now?.let { getString(R.string.usage_battery_left, UsageFormat.duration(this, com.spizganed.quickbuds.usage.BatteryDrain.lastsMs(it, rate)), it) },
                getString(R.string.usage_battery_full, UsageFormat.duration(this, com.spizganed.quickbuds.usage.BatteryDrain.lastsMs(100, rate)),
                    Math.round(rate.pctPerHour).toInt()),
                getString(R.string.usage_battery_basis, UsageFormat.duration(this, rate.listenedMs))
            )
            val row = SettingRowFactory.build(this, R.drawable.ic_case, 0, 0, null, minHeightDp = 56f)
            row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).text = d.label
            SettingRowFactory.subtitle(this, row).text = lines.joinToString("\n")
            SettingRowFactory.addRow(card, row)
        }
        return card
    }

    /** The level that runs out first, while the device is connected: the buds out of the case, or the other earbuds' level. */
    private fun levelNow(id: String): Int? {
        val st = com.spizganed.quickbuds.widget.WidgetStateStore.read(this)
        if (st.connected && com.spizganed.quickbuds.bluetooth.BudsDevice.find(this)?.address == id) {
            return com.spizganed.quickbuds.usage.BatteryDrain.budsLevel(st.leftBattery, st.rightBattery,
                leftInUse = !com.spizganed.quickbuds.notify.BatteryCard.leftCharging && st.leftStatus != 4,
                rightInUse = !com.spizganed.quickbuds.notify.BatteryCard.rightCharging && st.rightStatus != 4)
        }
        return com.spizganed.quickbuds.bluetooth.ConnectedDevices.all().firstOrNull { it.address == id }?.level?.takeIf { it > 0 }
    }

    // --- Day: one day as a 24-hour ring, its figures and sessions ---

    private fun renderDay(today: LocalDate) {
        val selected = (selectedDay ?: today).let { if (it.isAfter(today)) today else it }
        val animate = selected != ringShown
        ringShown = selected
        val used = days[selected]?.totalMs ?: 0L
        val ring = UsageRingView(this).apply {
            label = UsageFormat.duration(this@UsageActivity, used)
            sublabel = getString(R.string.usage_ring_sub, (used * 100 / DAY_MS).toInt())
            setUsed(used, animate)
            contentDescription = "${UsageFormat.day(this@UsageActivity, selected)}: $label, $sublabel"
        }
        content.addView(chartCard(
            UsageFormat.dayWithWeekday(this, selected), ring,
            prev = selected.isAfter(earliest(today)), next = selected.isBefore(today),
            onPrev = { selectedDay = selected.minusDays(1); render() },
            onNext = { selectedDay = selected.plusDays(1).takeIf { it.isBefore(today) }; render() }
        ))

        val u = days[selected] ?: DayUsage.empty(selected)
        content.addView(SettingRowFactory.sectionLabel(this, R.string.usage_tab_day))
        content.addView(figures(
            R.string.usage_total to UsageFormat.duration(this, u.totalMs),
            R.string.usage_sessions to u.sessions.toString(),
            R.string.usage_longest_session to UsageFormat.duration(this, u.longestMs),
            R.string.usage_average_session to UsageFormat.duration(this, u.averageSessionMs)
        ))
        sessionList(selected, u, today)
    }

    private fun sessionList(date: LocalDate, u: DayUsage, today: LocalDate) {
        val s = snapshot ?: return
        val list = UsageStats.sessionsOn(s.spans, date, zone, device)
        val rolled = date.isBefore(UsageRetention.firstKeptDay(today, s.retentionDays)) && u.totalMs > 0
        if (list.isEmpty()) {
            content.addView(note(if (rolled) getString(R.string.usage_details_rolled, UsageFormat.days(this, s.retentionDays)) else getString(R.string.usage_no_sessions)))
            return
        }
        val card = SettingRowFactory.card(this).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(12f) }
        }
        val names = s.devices.associate { it.id to it.label }
        for ((span, inDay) in list) {
            val end = if (span.open) getString(R.string.usage_now) else UsageFormat.time(this, span.endMs)
            val row = SettingRowFactory.build(this, 0, 0, 0, null,
                value = SettingRowFactory.buildValue(this, UsageFormat.duration(this, inDay)), minHeightDp = 52f)
            row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).text = UsageFormat.range(this, UsageFormat.time(this, span.startMs), end)
            if (device == null && s.devices.size > 1) SettingRowFactory.subtitle(this, row).text = names[span.deviceId] ?: span.deviceId
            SettingRowFactory.addRow(card, row)
        }
        content.addView(card)
        if (rolled) content.addView(note(getString(R.string.usage_details_rolled, UsageFormat.days(this, s.retentionDays))))
    }

    // --- Week: Monday to Sunday ---

    private fun renderWeek(today: LocalDate) {
        val start = UsageStats.weekStart(week ?: today).let { if (it.isAfter(today)) UsageStats.weekStart(today) else it }
        val bars = UsageStats.weekBars(days, start)
        val p = UsageStats.week(days, start, today)
        val chart = chart(bars, bars.map { UsageFormat.weekday(this, it.date) }, today, tipDates = true)
        content.addView(chartCard(
            UsageFormat.range(this, UsageFormat.dayShort(this, start), UsageFormat.dayShort(this, start.plusDays(6))), chart,
            prev = start.isAfter(earliest(today)), next = start.plusDays(6).isBefore(today),
            onPrev = { week = start.minusWeeks(1); bubbleAt = -1; render() },
            onNext = {
                week = start.plusWeeks(1).takeIf { it.isBefore(UsageStats.weekStart(today)) }
                bubbleAt = -1
                render()
            }
        ))
        content.addView(SettingRowFactory.sectionLabel(this, R.string.usage_tab_week))
        content.addView(figures(
            R.string.usage_total to UsageFormat.duration(this, p.totalMs),
            R.string.usage_average_day to UsageFormat.duration(this, p.averagePerDayMs),
            R.string.usage_active_days to "${p.activeDays} / 7",
            R.string.usage_sessions to p.sessions.toString(),
            R.string.usage_longest_day to longestDay(p.longestDay)
        ))
    }

    // --- Month: day 1 to the last day ---

    private fun renderMonth(today: LocalDate) {
        val m = (month ?: YearMonth.from(today)).let { if (it.isAfter(YearMonth.from(today))) YearMonth.from(today) else it }
        val bars = UsageStats.monthBars(days, m)
        val p = UsageStats.month(days, m, today)
        // Every day's number does not fit: 1, 5, 10, ... and the last day (30 next to 31 ran together).
        val labels = bars.map { d ->
            val n = d.date.dayOfMonth
            val last = m.lengthOfMonth()
            if (n == 1 || n == last || (n % 5 == 0 && last - n >= 3)) n.toString() else ""
        }
        val chart = chart(bars, labels, today, tipDates = true)
        content.addView(chartCard(
            UsageFormat.month(this, m), chart,
            prev = m.atDay(1).isAfter(earliest(today)), next = m.isBefore(YearMonth.from(today)),
            onPrev = { month = m.minusMonths(1); bubbleAt = -1; render() },
            onNext = {
                month = m.plusMonths(1).takeIf { it.isBefore(YearMonth.from(today)) }
                bubbleAt = -1
                render()
            }
        ))
        content.addView(SettingRowFactory.sectionLabel(this, R.string.usage_tab_month))
        content.addView(figures(
            R.string.usage_total to UsageFormat.duration(this, p.totalMs),
            R.string.usage_average_day to UsageFormat.duration(this, p.averagePerDayMs),
            R.string.usage_average_active_day to UsageFormat.duration(this, p.averagePerActiveDayMs),
            R.string.usage_active_days to "${p.activeDays} / ${m.lengthOfMonth()}",
            R.string.usage_sessions to p.sessions.toString(),
            R.string.usage_longest_day to longestDay(p.longestDay)
        ))
    }

    // --- Parts ---

    /** The first day the arrows can reach: the first day with data. */
    private fun earliest(today: LocalDate) = UsageStats.firstDay(days) ?: today

    private fun longestDay(d: DayUsage?) =
        if (d == null) "–" else "${UsageFormat.duration(this, d.totalMs)} · ${UsageFormat.dayShort(this, d.date)}"

    private fun chart(bars: List<DayUsage>, labels: List<String>, today: LocalDate, tipDates: Boolean) = UsageChartView(this).apply {
        this.bars = bars
        this.labels = labels
        marked = bars.indexOfFirst { it.date == today }
        axisLabel = { UsageFormat.axis(this@UsageActivity, it) }
        if (tipDates) {
            tooltip = { i ->
                val b = bars[i]
                listOf(UsageFormat.dayWithWeekday(this@UsageActivity, b.date), UsageFormat.duration(this@UsageActivity, b.totalMs),
                    getString(R.string.usage_sessions_count, b.sessions))
            }
            onSelect = { i -> bubbleAt = if (bubble) i else -1 }
            if (bubbleAt >= 0) showBubble(bubbleAt)
        }
        contentDescription = bars.joinToString { "${UsageFormat.day(this@UsageActivity, it.date)}: ${UsageFormat.duration(this@UsageActivity, it.totalMs)}" }
    }

    /** The chart in a card, under a "‹ period ›" line. */
    private fun chartCard(title: String, chart: View, prev: Boolean, next: Boolean, onPrev: () -> Unit, onNext: () -> Unit): View {
        val card = SettingRowFactory.card(this).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(12f) }
            setPadding(dp(8f), dp(8f), dp(8f), dp(12f))
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun arrow(back: Boolean, enabled: Boolean, onClick: () -> Unit) =
            SettingRowFactory.iconButton(this, R.drawable.ic_chevron_right,
                if (back) R.string.usage_previous else R.string.usage_next, onClick).apply {
                if (back) rotation = 180f
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.3f
            }
        nav.addView(arrow(true, prev, onPrev))
        nav.addView(text(title, 15f, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        nav.addView(arrow(false, next, onNext))
        card.addView(nav)
        card.addView(chart.apply { setPadding(0, dp(8f), 0, 0) })
        return card
    }

    /** Label-value rows in one card. */
    private fun figures(vararg rows: Pair<Int, String>): View {
        val card = SettingRowFactory.card(this)
        for ((label, value) in rows) {
            SettingRowFactory.addRow(card, SettingRowFactory.build(this, 0, label, 0, null,
                value = SettingRowFactory.buildValue(this, value).apply {
                    setTextColor(ThemeRes.color(this@UsageActivity, R.attr.appColorTextPrimary))
                    textSize = 15f
                }, minHeightDp = 48f))
        }
        return card
    }

    private fun deviceRow(s: Usage.Snapshot): View {
        val name = s.devices.firstOrNull { it.id == device }?.label ?: getString(R.string.usage_all_devices)
        val row = SettingRowFactory.build(this, R.drawable.ic_devices, R.string.usage_device, 0,
            SettingRowFactory.buildChevron(this), value = SettingRowFactory.buildValue(this, name)) {
            BottomSheetDialog(this).title(getString(R.string.usage_device)).items(
                listOf(BottomSheetDialog.Item(getString(R.string.usage_all_devices), device == null) { pick(null) }) +
                    s.devices.map { d -> BottomSheetDialog.Item(d.label, d.id == device) { pick(d.id) } }
            ).show()
        }
        return SettingRowFactory.card(this).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8f) }
            addView(row)
        }
    }

    private fun pick(id: String?) {
        device = id
        bubbleAt = -1
        snapshot?.let { days = UsageStats.byDay(it.spans, it.summaries, zone, device) }
        render()
    }

    private fun emptyState(): View = SettingRowFactory.card(this).apply {
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(12f) }
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24f), dp(32f), dp(24f), dp(32f))
        addView(ImageView(this@UsageActivity).apply {
            setImageDrawable(ThemeRes.tint(this@UsageActivity, R.drawable.ic_usage, ThemeRes.palette(this@UsageActivity).disabled))
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f)).apply { bottomMargin = dp(16f) }
        })
        addView(text(getString(R.string.usage_empty_title), 17f, bold = true).apply { gravity = Gravity.CENTER })
        addView(text(getString(R.string.usage_empty_body), 14f, secondary = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6f), 0, 0)
        })
    }

    private fun note(s: String) = text(s, 13f, secondary = true).apply { setPadding(dp(4f), dp(10f), dp(4f), 0) }

    private fun text(s: String, sp: Float, bold: Boolean = false, secondary: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        typeface = if (bold) ThemeRes.medium(this@UsageActivity) else ThemeRes.regular(this@UsageActivity)
        setTextColor(ThemeRes.color(this@UsageActivity, if (secondary) R.attr.appColorTextSecondary else R.attr.appColorTextPrimary))
    }

    private fun dp(v: Float) = ThemeRes.dp(this, v)

    companion object {
        /** A device id: the statistics open filtered to it. */
        const val EXTRA_DEVICE = "device"
        private const val TAB_DAY = 0
        private const val TAB_WEEK = 1
        private const val REFRESH_MS = 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val STATE_TAB = "tab"
        private const val STATE_DEVICE = "device"
        private const val STATE_DAY = "day"
        private const val STATE_WEEK = "week"
        private const val STATE_MONTH = "month"
    }
}
