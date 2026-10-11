package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * One day's usage as a ring, drawn like the battery rings: the whole circle is 24 hours, the accent arc is the
 * time used, from 12 o'clock. The total sits in the middle, the share of the day under it. Theme tokens only,
 * and the dot matrix in the Nothing style, as every other view here.
 */
class UsageRingView(context: Context) : View(context) {

    /** Sets the day's usage. [animate]: the arc sweeps out from empty (a new day shown); else it just updates. */
    fun setUsed(ms: Long, animate: Boolean) {
        anim?.cancel()
        if (!animate) { shown = fraction(ms); invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, fraction(ms)).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** The total, e.g. "3h 42m". */
    var label: String = ""
        set(v) { field = v; invalidate() }
    /** Under the total, e.g. "15% of 24 h". */
    var sublabel: String = ""
        set(v) { field = v; invalidate() }

    private var shown = 0f
    private var anim: ValueAnimator? = null

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()
    private val p = ThemeRes.palette(context)
    private val nothing = ThemeRes.nothing(context)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = p.outline; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = p.accent; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.text; textAlign = Paint.Align.CENTER; textSize = dp(30f); typeface = ThemeRes.headline(context)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.textSecondary; textAlign = Paint.Align.CENTER; textSize = dp(13f); typeface = ThemeRes.regular(context)
    }
    private val hours = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.textSecondary; textAlign = Paint.Align.CENTER; textSize = dp(10.5f); typeface = ThemeRes.regular(context)
    }
    private val box = RectF()

    private fun fraction(ms: Long) = (ms.toFloat() / DAY_MS).coerceIn(0f, 1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(250f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width.toFloat(), height.toFloat()) - dp(36f)
        val stroke = size * 0.085f
        val cx = width / 2f
        val cy = height / 2f
        box.set(cx - size / 2 + stroke / 2, cy - size / 2 + stroke / 2, cx + size / 2 - stroke / 2, cy + size / 2 - stroke / 2)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        if (nothing) DotArt.draw(context, canvas, width, height) { ring(it) } else ring(canvas)

        // 0, 6, 12, 18 h around the outside, like a clock face for the day.
        val r = box.width() / 2 + stroke / 2 + dp(10f)
        for ((i, h) in listOf("0", "6", "12", "18").withIndex()) {
            val a = Math.toRadians(-90.0 + i * 90.0)
            canvas.drawText(h, cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat() + dp(4f), hours)
        }

        canvas.drawText(label, cx, cy + dp(6f), big)
        canvas.drawText(sublabel, cx, cy + dp(30f), small)
    }

    private fun ring(canvas: Canvas) {
        canvas.drawOval(box, track)
        if (shown > 0f) {
            // A sliver of use still shows as a dot: a day with 2 minutes is not a day with none.
            val sweep = maxOf(360f * shown, 1.5f)
            canvas.drawArc(box, -90f, sweep, false, arc)
        }
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60_000L
    }
}
