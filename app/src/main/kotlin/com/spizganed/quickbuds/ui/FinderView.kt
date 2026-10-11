package com.spizganed.quickbuds.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.proximity.DirectionFinder
import com.spizganed.quickbuds.proximity.DistanceBand
import com.spizganed.quickbuds.proximity.ProximityLevel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * The Find nearby dial, in two modes (the screen's two tabs):
 *  - [Mode.RADAR]: rings at 1, 5, 10, 20 and 30 m ([DistanceBand.RINGS]), the current band's ring in the accent, and a
 *    dot at the estimated distance [metres]. The dot sits in a direction only when the turn found one ([bearing] and
 *    the compass [heading]); else the whole ring at that distance lights up: no direction is invented.
 *  - [Mode.ARROW]: twelve sectors that fill as the user turns ([covered]), and the arrow once a direction is found.
 * Very close, both show the same pulse on the screen's accent fill ("Here").
 * World-fixed where it matters: sector 0 and bearings are from north, drawn relative to the phone's [heading].
 */
class FinderView(context: Context) : View(context) {

    enum class Mode { ARROW, RADAR }

    var mode = Mode.RADAR
        set(v) { if (field != v) { field = v; invalidate() } }
    var level = ProximityLevel.SEARCHING
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Estimated distance; null without one. */
    var metres: Double? = null
        set(v) { field = v; invalidate() }
    var band: DistanceBand? = null
        set(v) { field = v; invalidate() }
    /** The sectors the turn has covered ([DirectionFinder.covered]). */
    var covered: BooleanArray = BooleanArray(12)
        set(v) { field = v; invalidate() }
    /** Where the phone points, degrees from north; null without a compass. */
    var heading: Double? = null
        set(v) { field = v; invalidate() }
    /** Where the device is, degrees from north; null until a turn found it. */
    var bearing: Double? = null
        set(v) { field = v; invalidate() }
    /** Colour on the accent background of the very-close stage. */
    var hereColor = 0xFFFFFFFF.toInt()

    private val accent = ThemeRes.color(context, R.attr.appColorAccent)
    private val track = ThemeRes.color(context, R.attr.appColorOutline)
    private val ink = ThemeRes.color(context, R.attr.appColorTextSecondary)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; strokeJoin = Paint.Join.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ThemeRes.dp(context, 10f).toFloat(); textAlign = Paint.Align.CENTER }
    private val dashed = DashPathEffect(floatArrayOf(ThemeRes.dp(context, 4f).toFloat(), ThemeRes.dp(context, 4f).toFloat()), 0f)
    private val box = RectF()
    private val arrow = Path()
    /** The arrow's drawn angle, eased toward the target so it turns smoothly. */
    private var shownAngle = 0.0
    /** The dot's drawn radius, eased toward the distance. */
    private var shownRadius = -1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.86f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.9f
        if (level == ProximityLevel.VERY_CLOSE) return drawHere(canvas, cx, cy, r)
        if (mode == Mode.RADAR) drawRadar(canvas, cx, cy, r) else drawArrow(canvas, cx, cy, r)
    }

    /** Ring radius for [m] metres: logarithmic, so 1 m and 30 m both have room. */
    private fun radiusOf(m: Double, r: Float): Float = (r * (ln(1 + m) / ln(31.0))).toFloat().coerceIn(r * 0.08f, r)

    private fun drawRadar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        ring.strokeWidth = ThemeRes.dp(context, 1.5f).toFloat()
        label.color = ink
        val lit = band
        for ((i, m) in DistanceBand.RINGS.withIndex()) {
            val current = lit != null && lit.ordinal == i
            ring.color = if (current) accent else track
            ring.strokeWidth = ThemeRes.dp(context, if (current) 3f else 1.5f).toFloat()
            val rr = radiusOf(m, r)
            canvas.drawCircle(cx, cy, rr, ring)
            canvas.drawText(String.format(Locale.getDefault(), "%d m", m.toInt()), cx, cy - rr + label.textSize * 1.1f, label)
        }
        // The phone at the centre; a tick for the way it points.
        fill.color = ink
        canvas.drawCircle(cx, cy, r * 0.04f, fill)

        val m = metres ?: run {
            if (level == ProximityLevel.SEARCHING || level == ProximityLevel.CALIBRATING) breathe(canvas, cx, cy, r)
            return
        }
        val target = radiusOf(m, r)
        shownRadius = if (shownRadius < 0) target else shownRadius + (target - shownRadius) * 0.2f
        if (abs(target - shownRadius) > 0.5f) postInvalidateOnAnimation()
        val b = bearing
        val h = heading
        if (b != null && h != null) {
            // A direction from the turn: the dot sits there, relative to where the phone points (up).
            val a = Math.toRadians(DirectionFinder.turn(h, b) - 90)
            fill.color = accent
            canvas.drawCircle(cx + shownRadius * cos(a).toFloat(), cy + shownRadius * sin(a).toFloat(), r * 0.07f, fill)
        } else {
            // Distance only: the whole ring at that distance, dashed, so no direction is suggested.
            ring.color = accent
            ring.strokeWidth = ThemeRes.dp(context, 6f).toFloat()
            ring.alpha = 140
            ring.pathEffect = dashed
            canvas.drawCircle(cx, cy, shownRadius, ring)
            ring.pathEffect = null
            ring.alpha = 255
        }
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val h = heading ?: 0.0
        val n = covered.size
        val sweep = 360f / n
        ring.strokeWidth = r * 0.07f
        box.set(cx - r * 0.92f, cy - r * 0.92f, cx + r * 0.92f, cy + r * 0.92f)
        for (i in 0 until n) {
            ring.color = if (covered[i]) accent else track
            // Sector i is centred on i * sweep degrees from north; screen angle 0 is "up" (-90 in Android arcs).
            val centre = (i * sweep - h).toFloat() - 90f
            canvas.drawArc(box, centre - sweep * 0.36f, sweep * 0.72f, false, ring)
        }
        val b = bearing
        val rr = r * 0.92f
        if (b == null || heading == null) {
            // No direction yet: a mark for "this way is where the phone points", nothing more.
            fill.color = ink
            arrow.reset()
            arrow.moveTo(cx, cy - rr * 0.62f)
            arrow.lineTo(cx + rr * 0.08f, cy - rr * 0.46f)
            arrow.lineTo(cx - rr * 0.08f, cy - rr * 0.46f)
            arrow.close()
            canvas.drawPath(arrow, fill)
            canvas.drawCircle(cx, cy, rr * 0.1f, fill)
            return
        }
        val target = DirectionFinder.turn(0.0, b - h)
        val step = DirectionFinder.turn(shownAngle, target)
        shownAngle = DirectionFinder.normalize(shownAngle + step * 0.25)
        if (abs(step) > 0.5) postInvalidateOnAnimation()
        canvas.save()
        canvas.rotate(shownAngle.toFloat(), cx, cy)
        fill.color = accent
        arrow.reset()
        arrow.moveTo(cx, cy - rr * 0.62f)
        arrow.lineTo(cx + rr * 0.36f, cy - rr * 0.12f)
        arrow.lineTo(cx + rr * 0.13f, cy - rr * 0.12f)
        arrow.lineTo(cx + rr * 0.13f, cy + rr * 0.52f)
        arrow.lineTo(cx - rr * 0.13f, cy + rr * 0.52f)
        arrow.lineTo(cx - rr * 0.13f, cy - rr * 0.12f)
        arrow.lineTo(cx - rr * 0.36f, cy - rr * 0.12f)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.restore()
    }

    /** Searching: a soft breathing dot, the same every time, so the screen is not frozen. */
    private fun breathe(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val phase = (SystemClock.uptimeMillis() % BREATH_MS) / BREATH_MS.toFloat()
        fill.color = ink
        fill.alpha = (90 + 80 * abs(phase * 2 - 1)).toInt()
        canvas.drawCircle(cx, cy, r * 0.1f, fill)
        fill.alpha = 255
        postInvalidateOnAnimation()
    }

    /** Three rings growing out of a disc: right here. */
    private fun drawHere(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val phase = (SystemClock.uptimeMillis() % PULSE_MS) / PULSE_MS.toFloat()
        ring.color = hereColor
        ring.strokeWidth = r * 0.035f
        ring.pathEffect = null
        for (k in 0 until 3) {
            val p = (phase + k / 3f) % 1f
            ring.alpha = ((1f - p) * 200).toInt()
            canvas.drawCircle(cx, cy, r * (0.34f + 0.66f * p), ring)
        }
        ring.alpha = 255
        fill.color = hereColor
        canvas.drawCircle(cx, cy, r * 0.3f, fill)
        postInvalidateOnAnimation()
    }

    private companion object {
        const val PULSE_MS = 1_800L
        const val BREATH_MS = 2_400L
    }
}
