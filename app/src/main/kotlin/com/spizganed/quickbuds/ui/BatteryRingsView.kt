package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Battery levels as rings side by side (Left, Case, Right): the arc is the level from 12 o'clock, the percent in the
 * middle, the name and one line under each ring (time left, charges left, charging). Drawn like [UsageRingView]:
 * theme tokens only, the dot matrix in the Nothing style.
 */
class BatteryRingsView(context: Context) : View(context) {

    /** One ring. [level]: 0..100, or -1 when not known (an empty ring and "—"). */
    data class Ring(val name: String, val level: Int, val line: String)

    /** Sets the rings. [animate]: they sweep out from empty (the screen opens); else they just update. */
    fun setRings(list: List<Ring>, animate: Boolean = true) {
        rings = list
        anim?.cancel()
        if (!animate) { shown = 1f; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private var rings: List<Ring> = emptyList()
    private var shown = 0f
    private var anim: ValueAnimator? = null

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()
    private val p = ThemeRes.palette(context)
    private val nothing = ThemeRes.nothing(context)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = p.outline; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = p.accent; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.text; textAlign = Paint.Align.CENTER; textSize = dp(20f); typeface = ThemeRes.headline(context)
    }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.text; textAlign = Paint.Align.CENTER; textSize = dp(13f); typeface = ThemeRes.regular(context)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.textSecondary; textAlign = Paint.Align.CENTER; textSize = dp(11.5f); typeface = ThemeRes.regular(context)
    }
    private val box = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(158f).toInt())
    }

    /** The ring's box for column [i] of [n]. */
    private fun place(i: Int, n: Int): Float {
        val col = width / n.toFloat()
        val size = minOf(col - dp(20f), dp(104f))
        val stroke = size * 0.11f
        val cx = col * i + col / 2
        val top = dp(10f)
        box.set(cx - size / 2 + stroke / 2, top + stroke / 2, cx + size / 2 - stroke / 2, top + size - stroke / 2)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        return cx
    }

    override fun onDraw(canvas: Canvas) {
        val n = rings.size
        if (n == 0) return
        val draw: (Canvas) -> Unit = { c ->
            rings.forEachIndexed { i, r ->
                place(i, n)
                c.drawOval(box, track)
                if (r.level > 0) c.drawArc(box, -90f, maxOf(360f * r.level / 100f * shown, 1.5f), false, arc)
            }
        }
        if (nothing) DotArt.draw(context, canvas, width, height) { draw(it) } else draw(canvas)
        rings.forEachIndexed { i, r ->
            val cx = place(i, n)
            canvas.drawText(if (r.level >= 0) "${r.level}%" else "—", cx, box.centerY() + dp(7f), big)
            val under = box.bottom + track.strokeWidth / 2
            canvas.drawText(r.name, cx, under + dp(20f), name)
            canvas.drawText(r.line, cx, under + dp(38f), small)
        }
    }
}
