package com.spizganed.quickbuds.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.spizganed.quickbuds.usage.DayUsage

/**
 * Usage per day as a bar chart: one bar per day, hours on a "nice" scale with at most four grid lines.
 * Drawn in the theme's tokens like every other view here (accent bars, the dot matrix in the Nothing
 * style), so light, dark and custom presets need nothing of their own.
 *
 * A tap selects the nearest bar: it turns full accent, a bubble shows [tooltip]'s lines, and
 * [onSelect] fires. A day without usage keeps a short stub, so a zero day reads as a day, not a gap.
 */
class UsageChartView(context: Context) : View(context) {

    /** The bars, oldest first. */
    var bars: List<DayUsage> = emptyList()
        set(v) { field = v; if (selected >= v.size) selected = -1; requestLayout(); invalidate() }

    /** One x label per bar; "" leaves that bar unlabelled (31 labels do not fit). */
    var labels: List<String> = emptyList()
        set(v) { field = v; invalidate() }

    /** The selected bar, -1 for none. */
    var selected = -1
        set(v) { field = v; invalidate() }

    /** Marked with a dot under its label (today). */
    var marked = -1
        set(v) { field = v; invalidate() }

    /** The bubble's lines for a bar; none = no bubble. */
    var tooltip: ((Int) -> List<String>)? = null

    /** A duration for the y axis, e.g. "2h". */
    var axisLabel: (Long) -> String = { "${it / HOUR}h" }

    var onSelect: ((Int) -> Unit)? = null

    /** The bubble shows only after a tap (or [showBubble]), not for a selection set from code. */
    var bubble = false
        private set

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()
    private val p = ThemeRes.palette(context)
    private val nothing = ThemeRes.nothing(context)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.outline; strokeWidth = if (nothing) DotArt.pitchPx(context) else dp(1f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.textSecondary; textSize = dp(11f); typeface = ThemeRes.regular(context)
    }
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.card }
    private val tipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent; style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val tipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.text; textSize = dp(12.5f); typeface = ThemeRes.medium(context)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
    private val rect = RectF()

    // Plot area. The left edge leaves room for the widest y label (measured in onDraw: "30 min" is wider than "2h").
    private var left = dp(40f)
    private val right get() = width - dp(8f)
    private val top get() = dp(12f)
    private val bottom get() = height - dp(28f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(220f).toInt())
    }

    /** The y scale's step and top: the step is the smallest "nice" one giving at most four lines. */
    private fun scale(): Pair<Long, Long> {
        val max = bars.maxOfOrNull { it.totalMs } ?: 0L
        val step = STEPS.firstOrNull { it * 4 >= max } ?: (((max / 4) / HOUR) + 1) * HOUR
        val lines = if (max == 0L) 1 else ((max + step - 1) / step).coerceAtLeast(1)
        return step to step * lines
    }

    private fun slot() = if (bars.isEmpty()) 0f else (right - left) / bars.size

    private fun y(ms: Long, topMs: Long) = bottom - (bottom - top) * ms / topMs.toFloat()

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val (step, topMs) = scale()
        axisPaint.textAlign = Paint.Align.RIGHT
        var widest = 0f
        var v = 0L
        while (v <= topMs) { widest = maxOf(widest, axisPaint.measureText(axisLabel(v))); v += step }
        left = widest + dp(10f)
        v = 0L
        while (v <= topMs) {
            canvas.drawText(axisLabel(v), left - dp(6f), y(v, topMs) + dp(4f), axisPaint)
            v += step
        }
        if (nothing) DotArt.draw(context, canvas, width, height) { shapes(it, step, topMs) } else shapes(canvas, step, topMs)

        axisPaint.textAlign = Paint.Align.CENTER
        val slot = slot()
        for (i in bars.indices) {
            val label = labels.getOrNull(i).orEmpty()
            val cx = left + slot * (i + 0.5f)
            if (label.isNotEmpty()) {
                axisPaint.color = if (i == selected) p.accent else p.textSecondary
                canvas.drawText(label, cx, bottom + dp(16f), axisPaint)
            }
            if (i == marked) canvas.drawCircle(cx, bottom + dp(23f), dp(2f), markPaint)
        }
        axisPaint.color = p.textSecondary
        if (bubble) drawBubble(canvas, topMs)
    }

    private fun shapes(canvas: Canvas, step: Long, topMs: Long) {
        var v = 0L
        while (v <= topMs) {
            val gy = y(v, topMs)
            canvas.drawLine(left, gy, right, gy, gridPaint)
            v += step
        }
        val slot = slot()
        val barW = (slot * 0.6f).coerceAtMost(dp(28f))
        val radius = if (nothing) 0f else minOf(dp(4f), barW / 2)
        for ((i, b) in bars.withIndex()) {
            val cx = left + slot * (i + 0.5f)
            val stub = b.totalMs == 0L
            val by = if (stub) bottom - dp(3f) else minOf(y(b.totalMs, topMs), bottom - dp(3f))
            barPaint.color = when {
                stub -> p.disabled
                selected < 0 || i == selected -> p.accent
                else -> Palette.withAlpha(p.accent, 0.45f)
            }
            rect.set(cx - barW / 2, by, cx + barW / 2, bottom)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }

    private fun drawBubble(canvas: Canvas, topMs: Long) {
        val i = selected
        val lines = tooltip?.invoke(i)?.takeIf { it.isNotEmpty() } ?: return
        if (i !in bars.indices) return
        val pad = dp(8f)
        val lineH = dp(17f)
        val w = lines.maxOf { tipText.measureText(it) } + pad * 2
        val h = lineH * lines.size + pad * 2 - dp(4f)
        val cx = left + slot() * (i + 0.5f)
        val barTop = y(bars[i].totalMs, topMs)
        val x0 = (cx - w / 2).coerceIn(0f, width - w)
        // Above the bar when it fits, else beside its top.
        val y0 = (barTop - h - dp(6f)).coerceAtLeast(0f)
        rect.set(x0, y0, x0 + w, y0 + h)
        canvas.drawRoundRect(rect, dp(10f), dp(10f), tipBg)
        canvas.drawRoundRect(rect, dp(10f), dp(10f), tipStroke)
        tipText.textAlign = Paint.Align.LEFT
        lines.forEachIndexed { n, s -> canvas.drawText(s, x0 + pad, y0 + pad + lineH * n + dp(9f), tipText) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bars.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val slot = slot()
                if (slot <= 0f) return true
                val i = ((e.x - left) / slot).toInt().coerceIn(0, bars.size - 1)
                bubble = !(bubble && i == selected)
                selected = i
                performClick()
                Haptics.commit(this)
                onSelect?.invoke(i)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Shows the bubble of bar [i]: the screen restores it after its minute refresh redraws the chart. */
    fun showBubble(i: Int) {
        if (i !in bars.indices) return
        selected = i
        bubble = true
        invalidate()
    }

    companion object {
        private const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        /** 15 min to 12 h; past that (several devices on one day), whole hours. */
        private val STEPS = listOf(15 * MINUTE, 30 * MINUTE, HOUR, 2 * HOUR, 3 * HOUR, 4 * HOUR, 6 * HOUR, 8 * HOUR, 12 * HOUR)
    }
}
