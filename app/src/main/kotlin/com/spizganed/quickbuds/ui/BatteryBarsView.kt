package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Times as horizontal bars, the longest full width: the name on the left and the time on the right above each bar.
 * For "a full charge lasts": left, right, and with the case. Theme tokens only, the dot matrix in the Nothing style.
 */
class BatteryBarsView(context: Context) : View(context) {

    data class Bar(val name: String, val ms: Long, val text: String)

    fun setBars(list: List<Bar>, animate: Boolean = true) {
        bars = list
        requestLayout()
        anim?.cancel()
        if (!animate) { shown = 1f; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private var bars: List<Bar> = emptyList()
    private var shown = 0f
    private var anim: ValueAnimator? = null

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()
    private val p = ThemeRes.palette(context)
    private val nothing = ThemeRes.nothing(context)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.outline }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.text; textSize = dp(13f); typeface = ThemeRes.regular(context)
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.textSecondary; textSize = dp(13f); textAlign = Paint.Align.RIGHT; typeface = ThemeRes.regular(context)
    }
    private val box = RectF()

    private val rowH get() = dp(46f)
    private val barH get() = dp(10f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rowH * bars.size + dp(6f)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val max = bars.maxOfOrNull { it.ms }?.takeIf { it > 0 } ?: return
        val left = dp(4f)
        val right = width - dp(4f)
        val draw: (Canvas) -> Unit = { c ->
            bars.forEachIndexed { i, b ->
                val y = rowH * i + dp(26f)
                box.set(left, y, right, y + barH)
                c.drawRoundRect(box, barH / 2, barH / 2, track)
                box.right = left + maxOf((right - left) * b.ms / max * shown, barH)
                c.drawRoundRect(box, barH / 2, barH / 2, fill)
            }
        }
        if (nothing) DotArt.draw(context, canvas, width, height) { draw(it) } else draw(canvas)
        bars.forEachIndexed { i, b ->
            val base = rowH * i + dp(18f)
            canvas.drawText(b.name, left, base, name)
            canvas.drawText(b.text, right, base, value)
        }
    }
}
