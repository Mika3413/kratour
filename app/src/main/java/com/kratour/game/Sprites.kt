package com.kratour.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.random.Random

/** Image pré-rendue avec un point d'ancrage (centre de la case au sol). */
class Sprite(val bmp: Bitmap, val ax: Float, val ay: Float, val res: Float) {
    private val dst = RectF()
    private val src = Rect(0, 0, bmp.width, bmp.height)

    fun draw(c: Canvas, sx: Float, sy: Float, zoom: Float, paint: Paint?) {
        val k = zoom / res
        dst.set(sx - ax * k, sy - ay * k, sx + (bmp.width - ax) * k, sy + (bmp.height - ay) * k)
        c.drawBitmap(bmp, src, dst, paint)
    }
}

/** Fabrique des sprites du décor et des structures (dessinés par le code). */
object Sprites {
    private const val RES = 2f
    private val TW = IsoCamera.TW
    private val TH = IsoCamera.TH

    private fun make(w: Float, h: Float, ax: Float, ay: Float, draw: (Canvas) -> Unit): Sprite {
        val bmp = Bitmap.createBitmap((w * RES).toInt(), (h * RES).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(RES, RES)
        c.translate(ax, ay)
        draw(c)
        return Sprite(bmp, ax * RES, ay * RES, RES)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun fill(c: Int) = paint.apply { reset(); isAntiAlias = true; style = Paint.Style.FILL; color = c }
    private fun stroke(c: Int, w: Float) = paint.apply {
        reset(); isAntiAlias = true; style = Paint.Style.STROKE; color = c; strokeWidth = w; strokeJoin = Paint.Join.ROUND
    }

    /** Boîte isométrique d'une case, de hauteur [h], réduite d'un facteur [inset]. */
    fun box(c: Canvas, h: Float, top: Int, left: Int, right: Int, inset: Float = 0.94f, outline: Int = 0x55000000) {
        val hw = TW / 2f * inset; val hh = TH / 2f * inset
        val p = Path()
        // gauche
        p.moveTo(-hw, -h); p.lineTo(0f, hh - h); p.lineTo(0f, hh); p.lineTo(-hw, 0f); p.close()
        c.drawPath(p, fill(left))
        p.reset(); p.moveTo(0f, hh - h); p.lineTo(hw, -h); p.lineTo(hw, 0f); p.lineTo(0f, hh); p.close()
        c.drawPath(p, fill(right))
        p.reset(); p.moveTo(0f, -hh - h); p.lineTo(hw, -h); p.lineTo(0f, hh - h); p.lineTo(-hw, -h); p.close()
        c.drawPath(p, fill(top))
        p.reset()
        p.moveTo(0f, -hh - h); p.lineTo(hw, -h); p.lineTo(hw, 0f); p.lineTo(0f, hh); p.lineTo(-hw, 0f); p.lineTo(-hw, -h); p.close()
        c.drawPath(p, stroke(outline, 1f))
    }

    // ------------------------------------------------------------------ Décor

    fun rock(seed: Int): Sprite = make(TW, TH + 40f, TW / 2f, 40f + TH / 2f) { c ->
        val r = Random(seed)
        c.drawOval(RectF(-26f, -6f, 26f, 12f), fill(0x33000000))
        val base = Color.rgb(128 + r.nextInt(16), 126 + r.nextInt(14), 132 + r.nextInt(14))
        val p = Path()
        p.moveTo(-24f, 4f); p.lineTo(-20f, -16f); p.lineTo(-8f, -30f - r.nextInt(6)); p.lineTo(8f, -32f + r.nextInt(6))
        p.lineTo(22f, -14f); p.lineTo(25f, 4f); p.quadTo(0f, 14f, -24f, 4f); p.close()
        c.drawPath(p, fill(base))
        val hl = Path()
        hl.moveTo(-20f, -16f); hl.lineTo(-8f, -30f); hl.lineTo(8f, -31f); hl.lineTo(2f, -12f); hl.lineTo(-14f, -6f); hl.close()
        c.drawPath(hl, fill(Art.shade(base, 1.22f)))
        val sh = Path()
        sh.moveTo(2f, -12f); sh.lineTo(22f, -14f); sh.lineTo(25f, 4f); sh.quadTo(10f, 10f, 0f, 9f); sh.close()
        c.drawPath(sh, fill(Art.shade(base, 0.78f)))
        c.drawPath(p, stroke(Art.shade(base, 0.5f), 1.2f))
        c.drawLine(-6f, -20f, 4f, -8f, stroke(Art.shade(base, 0.6f), 1f))
    }

    fun tree(seed: Int): Sprite = make(TW + 10f, TH + 74f, TW / 2f + 5f, 74f + TH / 2f) { c ->
        val r = Random(seed)
        c.drawOval(RectF(-24f, -5f, 24f, 10f), fill(0x38000000))
        c.drawRect(RectF(-4f, -26f, 4f, 3f), fill(Color.rgb(110, 72, 40)))
        c.drawRect(RectF(1f, -26f, 4f, 3f), fill(Color.rgb(84, 54, 30)))
        val g = Color.rgb(46 + r.nextInt(20), 120 + r.nextInt(30), 52 + r.nextInt(16))
        val blobs = listOf(Triple(-12f, -34f, 15f), Triple(12f, -36f, 15f), Triple(0f, -50f, 17f), Triple(0f, -30f, 15f))
        for ((x, y, rad) in blobs) c.drawCircle(x, y + 3f, rad, fill(Art.shade(g, 0.62f)))
        for ((x, y, rad) in blobs) c.drawCircle(x, y, rad, fill(g))
        c.drawCircle(-6f, -55f, 8f, fill(Art.shade(g, 1.25f)))
        c.drawCircle(-15f, -38f, 6f, fill(Art.shade(g, 1.18f)))
    }

    // ------------------------------------------------------------------ Structures

    const val BRICK_H = 17f

    /** Brique d'enceinte. [damage] : 0 intacte, 1 fissurée, 2 très abîmée. */
    fun brick(team: Int, damage: Int): Sprite = make(TW, TH + BRICK_H + 4f, TW / 2f, BRICK_H + 4f + TH / 2f) { c ->
        val stone = mix(Color.rgb(170, 160, 146), Art.TEAM_COLORS[team], 0.18f)
        box(c, BRICK_H, Art.shade(stone, 1.15f), Art.shade(stone, 0.92f), Art.shade(stone, 0.72f))
        // Joints de briques
        val joint = stroke(Art.shade(stone, 0.55f), 0.8f)
        val hw = TW / 2f * 0.94f; val hh = TH / 2f * 0.94f
        for (k in 1..2) {
            val y = -BRICK_H * k / 3f
            c.drawLine(-hw, y, 0f, hh + y, joint)
            c.drawLine(0f, hh + y, hw, y, joint)
        }
        c.drawLine(-hw / 2f, hh / 2f - BRICK_H / 3f, -hw / 2f, hh / 2f, joint)
        c.drawLine(hw / 2f, hh / 2f - BRICK_H * 2 / 3f, hw / 2f, hh / 2f - BRICK_H / 3f, joint)
        // Liseré d'équipe
        val p = Path()
        p.moveTo(0f, -hh - BRICK_H); p.lineTo(hw, -BRICK_H); p.lineTo(0f, hh - BRICK_H); p.lineTo(-hw, -BRICK_H); p.close()
        c.drawPath(p, stroke(Art.TEAM_COLORS[team], 2f))
        if (damage >= 1) {
            val crack = stroke(Color.argb(200, 40, 30, 20), 1.4f)
            c.drawLine(-14f, -BRICK_H + 2f, -8f, -12f, crack); c.drawLine(-8f, -12f, -12f, -2f, crack)
            c.drawLine(10f, -BRICK_H + 4f, 14f, -14f, crack)
        }
        if (damage >= 2) {
            val crack = stroke(Color.argb(230, 30, 20, 10), 1.8f)
            c.drawLine(4f, -BRICK_H - 6f, 0f, -8f, crack); c.drawLine(0f, -8f, 6f, 6f, crack)
            c.drawLine(-22f, -16f, -16f, -6f, crack)
            c.drawCircle(-4f, -BRICK_H - 2f, 3f, fill(Color.argb(140, 20, 10, 0)))
        }
    }

    fun rubble(team: Int): Sprite = make(TW, TH + 10f, TW / 2f, 10f + TH / 2f) { c ->
        val stone = mix(Color.rgb(150, 140, 128), Art.TEAM_COLORS[team], 0.15f)
        val r = Random(team * 7 + 3)
        repeat(7) {
            val x = r.nextInt(-20, 20).toFloat(); val y = r.nextInt(-7, 8).toFloat()
            val s = r.nextInt(3, 7).toFloat()
            c.drawOval(RectF(x - s, y - s * 0.6f, x + s, y + s * 0.6f), fill(Art.shade(stone, 0.75f + r.nextFloat() * 0.4f)))
        }
    }

    fun barricade(team: Int): Sprite = make(TW, TH + 34f, TW / 2f, 34f + TH / 2f) { c ->
        val wood = Color.rgb(150, 100, 56)
        c.drawOval(RectF(-24f, -5f, 24f, 10f), fill(0x33000000))
        for (k in -2..2) {
            val x = k * 9f; val y = k * 3f
            c.drawLine(x - 4f, y + 4f, x + 3f, y - 26f, stroke(Art.shade(wood, 0.6f), 6f))
            c.drawLine(x - 4f, y + 4f, x + 3f, y - 26f, stroke(wood, 4f))
        }
        c.drawLine(-22f, -12f, 22f, 2f, stroke(Art.shade(wood, 0.75f), 5f))
        c.drawLine(-22f, -4f, 22f, -18f, stroke(Art.shade(wood, 0.85f), 5f))
        c.drawCircle(0f, -8f, 4f, fill(Art.TEAM_COLORS[team]))
    }

    /** Fort 2x2 : ancré au centre des 4 cases. */
    fun fort(team: Int, ruined: Boolean): Sprite = make(TW * 2f, TH * 2f + 110f, TW, 110f + TH) { c ->
        val col = Art.TEAM_COLORS[team]
        val stone = Color.rgb(196, 188, 172)
        val hw = TW * 0.86f; val hh = TH * 0.86f
        val wallH = if (ruined) 22f else 46f
        c.drawOval(RectF(-hw - 6f, -hh + 4f, hw + 6f, hh + 10f), fill(0x40000000))
        val p = Path()
        p.moveTo(-hw, -wallH); p.lineTo(0f, hh - wallH); p.lineTo(0f, hh); p.lineTo(-hw, 0f); p.close()
        c.drawPath(p, fill(Art.shade(stone, 0.9f)))
        p.reset(); p.moveTo(0f, hh - wallH); p.lineTo(hw, -wallH); p.lineTo(hw, 0f); p.lineTo(0f, hh); p.close()
        c.drawPath(p, fill(Art.shade(stone, 0.72f)))
        p.reset(); p.moveTo(0f, -hh - wallH); p.lineTo(hw, -wallH); p.lineTo(0f, hh - wallH); p.lineTo(-hw, -wallH); p.close()
        c.drawPath(p, fill(Art.shade(stone, 1.1f)))
        // créneaux
        if (!ruined) {
            for (k in 0..4) {
                val t = k / 4f
                val x1 = -hw + hw * t; val y1 = -wallH + hh * t
                c.drawRect(RectF(x1 - 4f, y1 - 8f, x1 + 4f, y1), fill(Art.shade(stone, 0.95f)))
                val x2 = hw * t; val y2 = hh - wallH - hh * t
                c.drawRect(RectF(x2 - 4f, y2 - 8f, x2 + 4f, y2), fill(Art.shade(stone, 0.78f)))
            }
            // Toit pyramidal de couleur d'équipe
            val rh = 50f
            val tx = 0f; val ty = -wallH - rh
            val rw = hw * 0.62f; val rhh = hh * 0.62f
            val cyR = -wallH - 4f
            p.reset(); p.moveTo(-rw, cyR); p.lineTo(0f, cyR + rhh); p.lineTo(tx, ty); p.close()
            c.drawPath(p, fill(col))
            p.reset(); p.moveTo(0f, cyR + rhh); p.lineTo(rw, cyR); p.lineTo(tx, ty); p.close()
            c.drawPath(p, fill(Art.shade(col, 0.7f)))
            // Drapeau
            c.drawLine(tx, ty, tx, ty - 26f, stroke(Color.rgb(80, 60, 40), 2f))
            p.reset(); p.moveTo(tx, ty - 26f); p.lineTo(tx + 20f, ty - 20f); p.lineTo(tx, ty - 14f); p.close()
            c.drawPath(p, fill(col))
            // Porte (face avant gauche)
            p.reset()
            p.moveTo(-hw * 0.55f, hh * 0.45f - 4f)
            p.lineTo(-hw * 0.3f, hh * 0.7f - 4f)
            p.lineTo(-hw * 0.3f, hh * 0.7f - 26f)
            p.quadTo(-hw * 0.42f, hh * 0.58f - 36f, -hw * 0.55f, hh * 0.45f - 26f)
            p.close()
            c.drawPath(p, fill(Color.rgb(50, 36, 28)))
            // Blason
            c.drawCircle(hw * 0.45f, hh * 0.45f - wallH * 0.55f, 7f, fill(col))
            c.drawCircle(hw * 0.45f, hh * 0.45f - wallH * 0.55f, 7f, stroke(Color.WHITE, 1.5f))
        } else {
            val r = Random(team)
            repeat(10) {
                val x = r.nextInt(-40, 40).toFloat(); val y = r.nextInt(-14, 14).toFloat() - wallH
                c.drawCircle(x, y, r.nextInt(3, 7).toFloat(), fill(Art.shade(stone, 0.6f + r.nextFloat() * 0.3f)))
            }
            c.drawLine(-10f, -wallH - 4f, 6f, -wallH - 30f, stroke(Color.rgb(60, 50, 40), 2f))
            c.drawLine(6f, -wallH - 30f, 20f, -wallH - 26f, stroke(Color.rgb(90, 90, 90), 3f))
        }
    }

    fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )
}
