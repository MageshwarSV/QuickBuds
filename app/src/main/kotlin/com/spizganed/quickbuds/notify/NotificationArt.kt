package com.spizganed.quickbuds.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * The pictures in QuickBuds' notifications, drawn as bitmaps (a notification layout takes no custom views).
 * Kept small: RemoteViews bitmaps count against the system's memory limit for notifications.
 */
object NotificationArt {

    /** [BOTH]: the pair as one, for earbuds that report one level (any brand through standard Bluetooth). */
    enum class Part { LEFT, RIGHT, CASE, BOTH, NECKBAND, HEADPHONES, SPEAKER }

    /** Battery colours: green, amber at 20% and under, red at 10% and under. */
    fun levelColor(level: Int): Int = when {
        level <= 10 -> 0xFFFF453A.toInt()
        level <= 20 -> 0xFFFFB020.toInt()
        else -> 0xFF34C759.toInt()
    }

    /**
     * One battery ring, as the system's own earbud card draws it: a dark disc, a coloured arc for the level
     * from 12 o'clock, the bud or case glyph in white, and a badge at the top (a battery, or a bolt while charging).
     */
    fun batteryRing(context: Context, part: Part, level: Int, charging: Boolean, sizeDp: Float): Bitmap {
        val px = ThemeRes.dp(context, sizeDp).coerceAtLeast(8)
        val s = px.toFloat()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = s * 0.075f
        val badge = s * 0.15f
        // The ring leaves room above for the badge's top half.
        val box = RectF(stroke / 2 + badge * 0.3f, stroke / 2 + badge * 0.3f, s - stroke / 2 - badge * 0.3f, s - stroke / 2 - badge * 0.3f)

        p.style = Paint.Style.FILL
        p.color = 0xFF2A2A2D.toInt()
        c.drawOval(box, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFF4A4A4E.toInt()
        c.drawOval(box, p)
        if (level in 1..100) {
            p.color = levelColor(level)
            c.drawArc(box, -90f, 360f * level / 100f, false, p)
        }

        val cx = box.centerX()
        val cy = box.centerY()
        val inner = box.width() - stroke * 2
        if (part == Part.NECKBAND || part == Part.HEADPHONES || part == Part.SPEAKER) {
            val glyph = context.getDrawable(when (part) {
                Part.NECKBAND -> R.drawable.ic_kind_neckband
                Part.HEADPHONES -> R.drawable.ic_kind_headphones
                else -> R.drawable.ic_kind_speaker
            })!!.mutate()
            glyph.setTint(Color.WHITE)
            val g = inner * 0.5f
            glyph.setBounds((cx - g / 2).toInt(), (cy - g / 2).toInt(), (cx + g / 2).toInt(), (cy + g / 2).toInt())
            glyph.draw(c)
        } else if (part == Part.BOTH) {
            // Two buds side by side, as tall as one bud's glyph would be.
            val h = inner * 0.46f
            val w = h * 176f / 272f
            budPair(context, c, cx - w * 1.025f, cy - h / 2, h, Color.WHITE)
        } else {
            val glyph = context.getDrawable(when (part) {
                Part.LEFT -> R.drawable.ic_bud_left
                Part.RIGHT -> R.drawable.ic_bud_right
                else -> R.drawable.ic_case
            })!!.mutate()
            glyph.setTint(Color.WHITE)
            val ratio = if (part == Part.CASE) 496f / 400f else 176f / 272f
            val boxH = inner * (if (part == Part.CASE) 0.42f else 0.56f)
            val boxW = inner * (if (part == Part.CASE) 0.6f else 0.4f)
            val (w, h) = if (ratio < boxW / boxH) boxH * ratio to boxH else boxW to boxW / ratio
            glyph.setBounds((cx - w / 2).toInt(), (cy - h / 2).toInt(), (cx + w / 2).toInt(), (cy + h / 2).toInt())
            glyph.draw(c)
        }

        if (level in 1..100) drawBadge(context, c, cx, box.top, badge, level, charging)
        return bmp
    }

    /** A dark disc on the ring's top edge holding a little battery (or a bolt while charging) in the level colour. */
    private fun drawBadge(context: Context, c: Canvas, cx: Float, cy: Float, r: Float, level: Int, charging: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF151517.toInt()
        c.drawCircle(cx, cy, r, p)
        val color = levelColor(level)
        if (charging) {
            val bolt = context.getDrawable(R.drawable.ic_bolt)!!.mutate()
            bolt.setTint(color)
            val b = r * 0.75f
            bolt.setBounds((cx - b).toInt(), (cy - b).toInt(), (cx + b).toInt(), (cy + b).toInt())
            bolt.draw(c)
            return
        }
        // A battery standing up: outline, cap, and a fill as tall as the level.
        val bw = r * 0.62f
        val bh = r * 1.1f
        val body = RectF(cx - bw / 2, cy - bh / 2 + r * 0.08f, cx + bw / 2, cy + bh / 2 + r * 0.08f)
        p.color = color
        p.style = Paint.Style.FILL
        c.drawRoundRect(cx - bw * 0.22f, body.top - r * 0.14f, cx + bw * 0.22f, body.top, r * 0.05f, r * 0.05f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.12f
        c.drawRoundRect(body, r * 0.12f, r * 0.12f, p)
        p.style = Paint.Style.FILL
        val inset = r * 0.14f
        val fillTop = body.bottom - inset - (body.height() - inset * 2) * level / 100f
        c.drawRect(body.left + inset, fillTop, body.right - inset, body.bottom - inset, p)
    }

    /** The loss warning's picture: both buds in a soft white disc, with signal waves fading out to the right. */
    fun lossArt(context: Context, sizeDp: Float): Bitmap {
        val px = ThemeRes.dp(context, sizeDp).coerceAtLeast(8)
        val s = px.toFloat()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33FFFFFF
        c.drawCircle(s / 2, s / 2, s / 2, p)
        budPair(context, c, s * 0.2f, s * 0.3f, s * 0.36f, Color.WHITE)
        // Three arcs, fainter the further out: the signal fading away.
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = s * 0.045f
        val alphas = intArrayOf(0xFF, 0xA0, 0x50)
        for (i in 0..2) {
            val r = s * (0.1f + i * 0.08f)
            p.color = (alphas[i] shl 24) or 0xFFFFFF
            c.drawArc(RectF(s * 0.6f - r, s * 0.5f - r, s * 0.6f + r, s * 0.5f + r), -45f, 90f, false, p)
        }
        return bmp
    }

    /**
     * The case reminder's picture: the case with the missing bud beside it, [outside] side, inside a dashed
     * amber circle (the one to fetch).
     */
    fun caseArt(context: Context, outsideLeft: Boolean, sizeDp: Float): Bitmap {
        val px = ThemeRes.dp(context, sizeDp).coerceAtLeast(8)
        val s = px.toFloat()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33FFFFFF
        c.drawCircle(s / 2, s / 2, s / 2, p)

        val case = context.getDrawable(R.drawable.ic_case)!!.mutate()
        case.setTint(Color.WHITE)
        val cw = s * 0.42f
        val ch = cw * 400f / 496f
        val caseX = if (outsideLeft) s * 0.6f else s * 0.4f
        case.setBounds((caseX - cw / 2).toInt(), (s * 0.55f - ch / 2).toInt(), (caseX + cw / 2).toInt(), (s * 0.55f + ch / 2).toInt())
        case.draw(c)

        val budX = if (outsideLeft) s * 0.24f else s * 0.76f
        val budY = s * 0.36f
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.03f
        p.color = 0xFFFFD54A.toInt()
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(s * 0.04f, s * 0.03f), 0f)
        c.drawCircle(budX, budY, s * 0.15f, p)
        val bud = context.getDrawable(if (outsideLeft) R.drawable.ic_bud_left else R.drawable.ic_bud_right)!!.mutate()
        bud.setTint(0xFFFFD54A.toInt())
        val bh = s * 0.2f
        val bw = bh * 176f / 272f
        bud.setBounds((budX - bw / 2).toInt(), (budY - bh / 2).toInt(), (budX + bw / 2).toInt(), (budY + bh / 2).toInt())
        bud.draw(c)
        return bmp
    }

    /** Left and right bud glyphs side by side, [h] tall, from ([x], [y]). */
    private fun budPair(context: Context, c: Canvas, x: Float, y: Float, h: Float, tint: Int) {
        val w = h * 176f / 272f
        for ((i, res) in listOf(R.drawable.ic_bud_left, R.drawable.ic_bud_right).withIndex()) {
            val d = context.getDrawable(res)!!.mutate()
            d.setTint(tint)
            val left = x + i * (w * 1.05f)
            d.setBounds(left.toInt(), y.toInt(), (left + w).toInt(), (y + h).toInt())
            d.draw(c)
        }
    }
}
