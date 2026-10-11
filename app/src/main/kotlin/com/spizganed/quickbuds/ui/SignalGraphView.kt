package com.spizganed.quickbuds.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.proximity.FindSample
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The live signal, last [windowMs]: the filtered level as a line, the raw readings as dots when [showRaw]. A gap of
 * more than [gapMs] between readings breaks the line: silence is not drawn as a signal. Real readings only; with too
 * few the view says so ([emptyText]). The vertical range follows the data, in [unit] (dBm, or the connection level).
 */
class SignalGraphView(context: Context) : View(context) {

    var samples: List<FindSample> = emptyList()
        set(v) { field = v; invalidate() }
    var nowMs = 0L
    var showRaw = false
        set(v) { field = v; invalidate() }
    var unit = "dBm"
    var emptyText = ""

    private val windowMs = 30_000L
    private val gapMs = 1_500L
    /** The filtered line: the accent, or the colour that reads on an accent background. */
    var lineColor = ThemeRes.color(context, R.attr.appColorAccent)
        set(v) { field = v; invalidate() }
    /** Labels and raw dots. */
    var inkColor = ThemeRes.color(context, R.attr.appColorTextSecondary)
        set(v) { field = v; invalidate() }
    private val grid = ThemeRes.color(context, R.attr.appColorOutline)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ThemeRes.dp(context, 11f).toFloat() }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ThemeRes.dp(context, 120f))
    }

    override fun onDraw(canvas: Canvas) {
        val labelW = text.measureText("-100 $unit") + ThemeRes.dp(context, 6f)
        val left = labelW
        val right = width.toFloat() - ThemeRes.dp(context, 4f)
        val top = ThemeRes.dp(context, 6f).toFloat()
        val bottom = height.toFloat() - ThemeRes.dp(context, 6f)
        text.color = inkColor
        val pts = samples.filter { nowMs - it.atMs <= windowMs }
        if (pts.size < 2) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyText, width / 2f, height / 2f, text)
            text.textAlign = Paint.Align.LEFT
            return
        }
        // Range: the readings, padded, in steps of 10, at least 20 tall.
        val lo0 = pts.minOf { minOf(it.filtered, if (showRaw) it.raw.toDouble() else it.filtered) }
        val hi0 = pts.maxOf { maxOf(it.filtered, if (showRaw) it.raw.toDouble() else it.filtered) }
        var lo = floor((lo0 - 3) / 10) * 10
        var hi = ceil((hi0 + 3) / 10) * 10
        if (hi - lo < 20) { val mid = (hi + lo) / 2; lo = mid - 10; hi = mid + 10 }
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun x(t: Long) = (right - (nowMs - t).toFloat() / windowMs * (right - left))

        line.color = grid
        line.strokeWidth = ThemeRes.dp(context, 1f).toFloat()
        for (v in listOf(lo, (lo + hi) / 2, hi)) {
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, line)
            canvas.drawText(String.format(Locale.getDefault(), "%d %s", v.toInt(), unit), 0f, yy + text.textSize / 3, text)
        }

        if (showRaw) {
            dot.color = inkColor
            val r = ThemeRes.dp(context, 1.6f).toFloat()
            for (p in pts) canvas.drawCircle(x(p.atMs), y(p.raw.toDouble()), r, dot)
        }
        line.color = lineColor
        line.strokeWidth = ThemeRes.dp(context, 2.5f).toFloat()
        path.reset()
        var prev: FindSample? = null
        for (p in pts) {
            if (prev == null || p.atMs - prev.atMs > gapMs) path.moveTo(x(p.atMs), y(p.filtered)) else path.lineTo(x(p.atMs), y(p.filtered))
            prev = p
        }
        canvas.drawPath(path, line)
    }
}
