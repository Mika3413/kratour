package com.kratour.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.kratour.core.BonusType
import com.kratour.core.ItemType
import kotlin.math.cos
import kotlin.math.sin

/** Palette et dessins vectoriels (aucune image externe : tout est dessiné par le jeu). */
object Art {
    val TEAM_COLORS = intArrayOf(
        Color.rgb(58, 128, 232),  // Bleus (joueur)
        Color.rgb(222, 64, 58),   // Rouges
        Color.rgb(64, 178, 82),   // Verts
        Color.rgb(232, 178, 40),  // Ors
    )
    val TEAM_DARK = intArrayOf(
        Color.rgb(28, 64, 140),
        Color.rgb(130, 30, 28),
        Color.rgb(30, 100, 44),
        Color.rgb(140, 100, 14),
    )
    val TEAM_LIGHT = intArrayOf(
        Color.rgb(170, 205, 255),
        Color.rgb(255, 180, 170),
        Color.rgb(176, 236, 180),
        Color.rgb(255, 230, 160),
    )

    val UI_BG = Color.argb(215, 22, 26, 38)
    val UI_BG_LIGHT = Color.argb(230, 44, 52, 72)
    val UI_BORDER = Color.argb(255, 120, 136, 170)
    val UI_TEXT = Color.rgb(240, 240, 232)
    val UI_DIM = Color.rgb(160, 168, 184)
    val UI_ACCENT = Color.rgb(255, 196, 64)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    fun fill(c: Int): Paint { p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = c; return p }
    fun stroke(c: Int, w: Float): Paint {
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.color = c; p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; return p
    }

    fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    fun shade(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255),
    )

    // ------------------------------------------------------------------ Icônes d'objets

    /** Dessine l'icône d'un objet centrée en (cx, cy), dans un carré de côté [s]. */
    fun icon(c: Canvas, type: ItemType, cx: Float, cy: Float, s: Float) {
        val h = s / 2f
        val lw = s * 0.09f
        when (type) {
            ItemType.SWORD -> {
                c.drawLine(cx - h * 0.55f, cy + h * 0.55f, cx + h * 0.7f, cy - h * 0.7f, stroke(Color.rgb(220, 228, 236), lw * 1.5f))
                c.drawLine(cx - h * 0.55f, cy + h * 0.55f, cx + h * 0.7f, cy - h * 0.7f, stroke(Color.WHITE, lw * 0.4f))
                c.drawLine(cx - h * 0.55f, cy + h * 0.05f, cx - h * 0.05f, cy + h * 0.55f, stroke(Color.rgb(200, 160, 60), lw * 1.2f))
                c.drawLine(cx - h * 0.55f, cy + h * 0.55f, cx - h * 0.8f, cy + h * 0.8f, stroke(Color.rgb(110, 70, 40), lw * 1.4f))
            }
            ItemType.MACE -> {
                c.drawLine(cx - h * 0.7f, cy + h * 0.7f, cx + h * 0.15f, cy - h * 0.15f, stroke(Color.rgb(120, 80, 45), lw * 1.4f))
                val bx = cx + h * 0.3f; val by = cy - h * 0.3f
                for (k in 0 until 8) {
                    val a = k * Math.PI.toFloat() / 4f
                    c.drawLine(bx, by, bx + cos(a) * h * 0.55f, by + sin(a) * h * 0.55f, stroke(Color.rgb(150, 150, 160), lw))
                }
                c.drawCircle(bx, by, h * 0.38f, fill(Color.rgb(110, 112, 124)))
                c.drawCircle(bx - h * 0.1f, by - h * 0.1f, h * 0.12f, fill(Color.rgb(190, 190, 200)))
            }
            ItemType.SPEAR -> {
                c.drawLine(cx - h * 0.85f, cy + h * 0.85f, cx + h * 0.45f, cy - h * 0.45f, stroke(Color.rgb(140, 95, 50), lw * 1.1f))
                path.reset()
                path.moveTo(cx + h * 0.9f, cy - h * 0.9f)
                path.lineTo(cx + h * 0.28f, cy - h * 0.55f)
                path.lineTo(cx + h * 0.55f, cy - h * 0.28f)
                path.close()
                c.drawPath(path, fill(Color.rgb(215, 222, 232)))
            }
            ItemType.HAMMER -> {
                c.drawLine(cx - h * 0.6f, cy + h * 0.75f, cx + h * 0.2f, cy - h * 0.3f, stroke(Color.rgb(120, 80, 45), lw * 1.4f))
                c.save(); c.rotate(37f, cx + h * 0.2f, cy - h * 0.3f)
                rect.set(cx - h * 0.35f, cy - h * 0.62f, cx + h * 0.75f, cy + h * 0.02f)
                c.drawRoundRect(rect, h * 0.08f, h * 0.08f, fill(Color.rgb(96, 100, 116)))
                rect.set(cx - h * 0.35f, cy - h * 0.62f, cx + h * 0.75f, cy - h * 0.42f)
                c.drawRect(rect, fill(Color.rgb(160, 166, 182)))
                c.restore()
            }
            ItemType.CROSSBOW -> {
                c.drawLine(cx - h * 0.7f, cy + h * 0.7f, cx + h * 0.4f, cy - h * 0.4f, stroke(Color.rgb(120, 80, 45), lw * 1.4f))
                rect.set(cx - h * 0.35f, cy - h * 0.95f, cx + h * 0.95f, cy + h * 0.35f)
                c.drawArc(rect, 135f + 50f, 170f, false, stroke(Color.rgb(90, 60, 35), lw * 1.2f))
                c.drawLine(cx - h * 0.3f, cy - h * 0.82f, cx + h * 0.82f, cy + h * 0.3f, stroke(Color.rgb(230, 230, 210), lw * 0.4f))
                c.drawLine(cx - h * 0.1f, cy + h * 0.1f, cx + h * 0.75f, cy - h * 0.75f, stroke(Color.rgb(200, 200, 210), lw * 0.6f))
            }
            ItemType.BOMB -> {
                c.drawCircle(cx - h * 0.1f, cy + h * 0.12f, h * 0.62f, fill(Color.rgb(36, 36, 44)))
                c.drawCircle(cx - h * 0.3f, cy - h * 0.1f, h * 0.18f, fill(Color.rgb(110, 110, 130)))
                c.drawLine(cx + h * 0.3f, cy - h * 0.35f, cx + h * 0.6f, cy - h * 0.7f, stroke(Color.rgb(170, 140, 90), lw))
                c.drawCircle(cx + h * 0.65f, cy - h * 0.75f, h * 0.16f, fill(Color.rgb(255, 180, 40)))
                c.drawCircle(cx + h * 0.65f, cy - h * 0.75f, h * 0.07f, fill(Color.rgb(255, 250, 200)))
            }
            ItemType.SHIELD -> {
                path.reset()
                path.moveTo(cx, cy - h * 0.85f)
                path.lineTo(cx + h * 0.72f, cy - h * 0.6f)
                path.quadTo(cx + h * 0.7f, cy + h * 0.45f, cx, cy + h * 0.9f)
                path.quadTo(cx - h * 0.7f, cy + h * 0.45f, cx - h * 0.72f, cy - h * 0.6f)
                path.close()
                c.drawPath(path, fill(Color.rgb(150, 105, 60)))
                c.drawPath(path, stroke(Color.rgb(205, 210, 220), lw))
                c.drawLine(cx, cy - h * 0.7f, cx, cy + h * 0.7f, stroke(Color.rgb(205, 210, 220), lw * 0.7f))
                c.drawLine(cx - h * 0.55f, cy - h * 0.15f, cx + h * 0.55f, cy - h * 0.15f, stroke(Color.rgb(205, 210, 220), lw * 0.7f))
            }
            ItemType.ARMOR -> {
                path.reset()
                path.moveTo(cx - h * 0.35f, cy - h * 0.8f)
                path.lineTo(cx - h * 0.85f, cy - h * 0.5f)
                path.lineTo(cx - h * 0.65f, cy - h * 0.05f)
                path.lineTo(cx - h * 0.5f, cy - h * 0.15f)
                path.lineTo(cx - h * 0.5f, cy + h * 0.8f)
                path.lineTo(cx + h * 0.5f, cy + h * 0.8f)
                path.lineTo(cx + h * 0.5f, cy - h * 0.15f)
                path.lineTo(cx + h * 0.65f, cy - h * 0.05f)
                path.lineTo(cx + h * 0.85f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.35f, cy - h * 0.8f)
                path.quadTo(cx, cy - h * 0.45f, cx - h * 0.35f, cy - h * 0.8f)
                path.close()
                c.drawPath(path, fill(Color.rgb(150, 158, 176)))
                c.drawPath(path, stroke(Color.rgb(70, 76, 92), lw * 0.7f))
                c.drawLine(cx - h * 0.4f, cy + h * 0.2f, cx + h * 0.4f, cy + h * 0.2f, stroke(Color.rgb(90, 96, 112), lw * 0.6f))
                c.drawLine(cx - h * 0.4f, cy + h * 0.5f, cx + h * 0.4f, cy + h * 0.5f, stroke(Color.rgb(90, 96, 112), lw * 0.6f))
            }
            ItemType.BUBBLE -> {
                c.drawCircle(cx, cy, h * 0.82f, fill(Color.argb(110, 120, 210, 255)))
                c.drawCircle(cx, cy, h * 0.82f, stroke(Color.argb(230, 170, 230, 255), lw * 0.8f))
                c.drawCircle(cx - h * 0.3f, cy - h * 0.32f, h * 0.18f, fill(Color.argb(220, 255, 255, 255)))
            }
            ItemType.REPAIR_KIT -> {
                rect.set(cx - h * 0.8f, cy - h * 0.35f, cx + h * 0.8f, cy + h * 0.75f)
                c.drawRoundRect(rect, h * 0.12f, h * 0.12f, fill(Color.rgb(205, 70, 50)))
                rect.set(cx - h * 0.3f, cy - h * 0.7f, cx + h * 0.3f, cy - h * 0.3f)
                c.drawRoundRect(rect, h * 0.1f, h * 0.1f, stroke(Color.rgb(80, 80, 90), lw))
                c.drawLine(cx - h * 0.35f, cy + h * 0.2f, cx + h * 0.35f, cy + h * 0.2f, stroke(Color.WHITE, lw * 1.3f))
                c.drawLine(cx, cy - h * 0.12f, cx, cy + h * 0.52f, stroke(Color.WHITE, lw * 1.3f))
            }
            ItemType.BARRICADE -> {
                val wood = Color.rgb(150, 100, 55)
                c.drawLine(cx - h * 0.8f, cy + h * 0.7f, cx + h * 0.8f, cy - h * 0.5f, stroke(wood, lw * 1.8f))
                c.drawLine(cx - h * 0.8f, cy - h * 0.5f, cx + h * 0.8f, cy + h * 0.7f, stroke(wood, lw * 1.8f))
                c.drawLine(cx - h * 0.9f, cy + h * 0.1f, cx + h * 0.9f, cy + h * 0.1f, stroke(Color.rgb(120, 80, 40), lw * 1.6f))
                c.drawLine(cx - h * 0.8f, cy - h * 0.5f, cx - h * 0.9f, cy - h * 0.8f, stroke(Color.rgb(200, 160, 110), lw))
                c.drawLine(cx + h * 0.8f, cy - h * 0.5f, cx + h * 0.9f, cy - h * 0.8f, stroke(Color.rgb(200, 160, 110), lw))
            }
            ItemType.HEAL -> {
                path.reset()
                path.moveTo(cx, cy + h * 0.8f)
                path.cubicTo(cx - h * 1.1f, cy, cx - h * 0.6f, cy - h * 0.95f, cx, cy - h * 0.4f)
                path.cubicTo(cx + h * 0.6f, cy - h * 0.95f, cx + h * 1.1f, cy, cx, cy + h * 0.8f)
                path.close()
                c.drawPath(path, fill(Color.rgb(235, 60, 90)))
                c.drawCircle(cx - h * 0.35f, cy - h * 0.3f, h * 0.13f, fill(Color.argb(200, 255, 220, 230)))
            }
            ItemType.SPEED -> {
                path.reset()
                path.moveTo(cx + h * 0.2f, cy - h * 0.95f)
                path.lineTo(cx - h * 0.55f, cy + h * 0.12f)
                path.lineTo(cx - h * 0.02f, cy + h * 0.12f)
                path.lineTo(cx - h * 0.25f, cy + h * 0.95f)
                path.lineTo(cx + h * 0.6f, cy - h * 0.2f)
                path.lineTo(cx + h * 0.05f, cy - h * 0.2f)
                path.close()
                c.drawPath(path, fill(Color.rgb(255, 220, 50)))
                c.drawPath(path, stroke(Color.rgb(200, 140, 20), lw * 0.5f))
            }
            ItemType.FORCE -> {
                path.reset()
                path.moveTo(cx, cy - h * 0.95f)
                path.quadTo(cx + h * 0.9f, cy - h * 0.1f, cx + h * 0.5f, cy + h * 0.6f)
                path.quadTo(cx, cy + h * 1.0f, cx - h * 0.5f, cy + h * 0.6f)
                path.quadTo(cx - h * 0.9f, cy - h * 0.1f, cx - h * 0.2f, cy - h * 0.45f)
                path.quadTo(cx - h * 0.1f, cy - h * 0.1f, cx, cy - h * 0.95f)
                path.close()
                c.drawPath(path, fill(Color.rgb(240, 90, 30)))
                c.drawCircle(cx, cy + h * 0.35f, h * 0.32f, fill(Color.rgb(255, 210, 60)))
            }
            ItemType.SHOCKWAVE -> {
                c.drawCircle(cx, cy, h * 0.18f, fill(Color.rgb(250, 250, 255)))
                c.drawCircle(cx, cy, h * 0.45f, stroke(Color.rgb(140, 200, 255), lw))
                c.drawCircle(cx, cy, h * 0.78f, stroke(Color.argb(170, 140, 200, 255), lw * 0.8f))
            }
            ItemType.FREEZE -> {
                val ice = Color.rgb(170, 230, 255)
                for (k in 0 until 3) {
                    val a = k * Math.PI.toFloat() / 3f
                    val dx = cos(a) * h * 0.85f; val dy = sin(a) * h * 0.85f
                    c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, stroke(ice, lw))
                }
                c.drawCircle(cx, cy, h * 0.16f, fill(Color.WHITE))
            }
            ItemType.INVISIBILITY -> {
                path.reset()
                path.moveTo(cx - h * 0.6f, cy + h * 0.8f)
                path.lineTo(cx - h * 0.6f, cy - h * 0.1f)
                path.quadTo(cx - h * 0.6f, cy - h * 0.85f, cx, cy - h * 0.85f)
                path.quadTo(cx + h * 0.6f, cy - h * 0.85f, cx + h * 0.6f, cy - h * 0.1f)
                path.lineTo(cx + h * 0.6f, cy + h * 0.8f)
                path.lineTo(cx + h * 0.3f, cy + h * 0.55f)
                path.lineTo(cx, cy + h * 0.8f)
                path.lineTo(cx - h * 0.3f, cy + h * 0.55f)
                path.close()
                c.drawPath(path, fill(Color.argb(150, 220, 220, 255)))
                c.drawPath(path, stroke(Color.argb(220, 255, 255, 255), lw * 0.6f))
                c.drawCircle(cx - h * 0.22f, cy - h * 0.25f, h * 0.1f, fill(Color.rgb(40, 40, 70)))
                c.drawCircle(cx + h * 0.22f, cy - h * 0.25f, h * 0.1f, fill(Color.rgb(40, 40, 70)))
            }
        }
    }

    fun bonusIcon(c: Canvas, type: BonusType, cx: Float, cy: Float, s: Float) {
        val h = s / 2f
        when (type) {
            BonusType.POTION -> {
                c.drawCircle(cx, cy + h * 0.2f, h * 0.55f, fill(Color.rgb(230, 60, 90)))
                rect.set(cx - h * 0.18f, cy - h * 0.75f, cx + h * 0.18f, cy - h * 0.2f)
                c.drawRect(rect, fill(Color.rgb(220, 230, 240)))
                rect.set(cx - h * 0.22f, cy - h * 0.9f, cx + h * 0.22f, cy - h * 0.7f)
                c.drawRect(rect, fill(Color.rgb(150, 100, 60)))
                c.drawCircle(cx - h * 0.2f, cy + h * 0.05f, h * 0.13f, fill(Color.argb(200, 255, 230, 235)))
            }
            BonusType.RECHARGE -> {
                rect.set(cx - h * 0.7f, cy - h * 0.5f, cx + h * 0.7f, cy + h * 0.6f)
                c.drawRect(rect, fill(Color.rgb(170, 120, 60)))
                c.drawRect(rect, stroke(Color.rgb(100, 65, 30), s * 0.06f))
                c.drawLine(rect.left, rect.top, rect.right, rect.bottom, stroke(Color.rgb(100, 65, 30), s * 0.05f))
                c.drawCircle(cx, cy, h * 0.28f, fill(Color.rgb(255, 210, 60)))
            }
            BonusType.HASTE -> {
                path.reset()
                path.moveTo(cx - h * 0.7f, cy + h * 0.8f)
                path.quadTo(cx - h * 0.4f, cy - h * 0.3f, cx + h * 0.7f, cy - h * 0.85f)
                path.quadTo(cx + h * 0.4f, cy + h * 0.3f, cx - h * 0.7f, cy + h * 0.8f)
                c.drawPath(path, fill(Color.rgb(120, 230, 160)))
                c.drawLine(cx - h * 0.7f, cy + h * 0.8f, cx + h * 0.5f, cy - h * 0.6f, stroke(Color.rgb(40, 140, 80), s * 0.05f))
            }
        }
    }
}
