package com.kratour.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent

/** Un écran du jeu (menu, partie, aide...). */
interface Screen {
    fun resize(w: Int, h: Int) {}
    fun update(dt: Float)
    fun draw(c: Canvas)
    fun onTouch(e: MotionEvent)
    /** Retourne vrai si le bouton retour a été géré. */
    fun onBack(): Boolean
    fun onPause() {}
    fun dispose() {}
}

/** Petits outils de dessin d'interface, dimensionnés en unités [u] (le petit côté de l'écran = 360u). */
class Ui {
    var u = 1f
    var w = 1f
    var h = 1f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val normalTf = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val boldTf = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val r = RectF()

    fun resize(width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat()
        u = minOf(w, h) / 360f
    }

    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Art.UI_TEXT,
        align: Paint.Align = Paint.Align.LEFT, bold: Boolean = true, shadow: Boolean = false,
    ) {
        textPaint.textSize = size * u
        textPaint.textAlign = align
        textPaint.typeface = if (bold) boldTf else normalTf
        if (shadow) {
            textPaint.color = Color.argb(Color.alpha(color) * 3 / 4, 0, 0, 0)
            c.drawText(s, x + 1.2f * u, y + 1.2f * u, textPaint)
        }
        textPaint.color = color
        c.drawText(s, x, y, textPaint)
    }

    fun textWidth(s: String, size: Float, bold: Boolean = true): Float {
        textPaint.textSize = size * u
        textPaint.typeface = if (bold) boldTf else normalTf
        return textPaint.measureText(s)
    }

    /** Texte sur plusieurs lignes dans une largeur donnée ; retourne la hauteur utilisée. */
    fun wrap(c: Canvas?, s: String, x: Float, y: Float, maxW: Float, size: Float, color: Int = Art.UI_TEXT, bold: Boolean = false, lineGap: Float = 1.3f): Float {
        var line = StringBuilder()
        var yy = y
        val lh = size * u * lineGap
        for (para in s.split("\n")) {
            line = StringBuilder()
            for (word in para.split(" ")) {
                val test = if (line.isEmpty()) word else "$line $word"
                if (textWidth(test, size, bold) > maxW && line.isNotEmpty()) {
                    c?.let { text(it, line.toString(), x, yy, size, color, bold = bold) }
                    yy += lh
                    line = StringBuilder(word)
                } else line = StringBuilder(test)
            }
            c?.let { text(it, line.toString(), x, yy, size, color, bold = bold) }
            yy += lh
        }
        return yy - y
    }

    fun panel(c: Canvas, rect: RectF, color: Int = Art.UI_BG, border: Int = Art.UI_BORDER, radius: Float = 8f) {
        c.drawRoundRect(rect, radius * u, radius * u, Art.fill(color))
        if (border != 0) c.drawRoundRect(rect, radius * u, radius * u, Art.stroke(Art.withAlpha(border, 160), 1.2f * u))
    }

    fun button(c: Canvas, rect: RectF, label: String, enabled: Boolean = true, accent: Boolean = false, selected: Boolean = false, size: Float = 15f) {
        val bg = when {
            !enabled -> Color.argb(170, 50, 52, 60)
            selected -> Color.argb(240, 70, 120, 200)
            accent -> Color.argb(245, 230, 150, 40)
            else -> Art.UI_BG_LIGHT
        }
        r.set(rect); r.offset(0f, 2.5f * u)
        c.drawRoundRect(r, 9f * u, 9f * u, Art.fill(Color.argb(120, 0, 0, 0)))
        c.drawRoundRect(rect, 9f * u, 9f * u, Art.fill(bg))
        c.drawRoundRect(rect, 9f * u, 9f * u, Art.stroke(if (selected || accent) Color.argb(255, 255, 235, 180) else Art.UI_BORDER, 1.5f * u))
        text(c, label, rect.centerX(), rect.centerY() + size * u * 0.36f, size, if (enabled) (if (accent) Color.rgb(40, 24, 0) else Art.UI_TEXT) else Art.UI_DIM, Paint.Align.CENTER)
    }

    /** Dessine une petite créature (portraits, menus). */
    fun kraton(c: Canvas, cx: Float, cy: Float, size: Float, team: Int, eyesDx: Float = 0f) {
        val col = Art.TEAM_COLORS[team]; val dark = Art.TEAM_DARK[team]; val light = Art.TEAM_LIGHT[team]
        val s = size / 26f
        r.set(cx - 13f * s, cy - 13f * s, cx + 13f * s, cy + 13f * s)
        c.drawOval(r, Art.fill(col))
        r.set(cx - 9f * s, cy, cx + 9f * s, cy + 12f * s)
        c.drawOval(r, Art.fill(Art.withAlpha(light, 170)))
        r.set(cx - 13f * s, cy - 13f * s, cx + 13f * s, cy + 13f * s)
        c.drawOval(r, Art.stroke(dark, 1.5f * s))
        for (k in listOf(-1f, 1f)) {
            c.drawCircle(cx + k * 4.5f * s + eyesDx * s, cy - 2f * s, 3.6f * s, Art.fill(Color.WHITE))
            c.drawCircle(cx + k * 4.5f * s + eyesDx * 1.5f * s, cy - 1.5f * s, 1.8f * s, Art.fill(Color.rgb(20, 20, 30)))
        }
        c.drawCircle(cx + 1f * s, cy - 15f * s, 3.5f * s, Art.fill(light))
    }
}
