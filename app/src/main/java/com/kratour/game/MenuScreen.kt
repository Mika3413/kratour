package com.kratour.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import com.kratour.core.GameConfig
import com.kratour.core.MapId
import kotlin.math.abs
import kotlin.math.sin

/** Menu principal : mode, difficulté de l'IA, aide, son. */
class MenuScreen(private val app: GameApp) : Screen {
    private val ui = app.ui
    private var time = 0f
    private val bg = Paint()

    private val duelBtn = RectF()
    private val meleeBtn = RectF()
    private val minusBtn = RectF()
    private val plusBtn = RectF()
    private val playBtn = RectF()
    private val helpBtn = RectF()
    private val soundBtn = RectF()
    private val panel = RectF()
    private val levelBar = RectF()

    companion object {
        val LEVEL_NAMES = arrayOf(
            "", "Très mauvaise", "Maladroite", "Débutante", "Apprentie", "Compétente",
            "Solide", "Tacticienne", "Experte", "Excellente", "Redoutable",
        )
    }

    override fun resize(w: Int, h: Int) {
        val u = ui.u
        val W = w.toFloat(); val H = h.toFloat()
        bg.shader = LinearGradient(0f, 0f, 0f, H, Color.rgb(40, 70, 110), Color.rgb(24, 36, 58), Shader.TileMode.CLAMP)
        val px = W * 0.52f
        panel.set(px, 18 * u, W - 24 * u, H - 14 * u)
        val cx = panel.centerX()
        val bw = panel.width() - 32 * u
        var y = panel.top + 40 * u
        duelBtn.set(panel.left + 16 * u, y, cx - 4 * u, y + 46 * u)
        meleeBtn.set(cx + 4 * u, y, panel.right - 16 * u, y + 46 * u)
        y += 100 * u
        minusBtn.set(panel.left + 16 * u, y, panel.left + 16 * u + 46 * u, y + 46 * u)
        plusBtn.set(panel.right - 16 * u - 46 * u, y, panel.right - 16 * u, y + 46 * u)
        levelBar.set(minusBtn.right + 10 * u, y, plusBtn.left - 10 * u, y + 46 * u)
        y += 60 * u
        playBtn.set(cx - bw / 2, y, cx + bw / 2, y + 50 * u)
        y += 58 * u
        helpBtn.set(panel.left + 16 * u, y, cx - 4 * u, y + 40 * u)
        soundBtn.set(cx + 4 * u, y, panel.right - 16 * u, y + 40 * u)
    }

    override fun update(dt: Float) { time += dt }

    override fun draw(c: Canvas) {
        val u = ui.u
        c.drawRect(0f, 0f, ui.w, ui.h, bg)
        // Île décorative
        val ix = ui.w * 0.26f; val iy = ui.h * 0.66f
        val path = android.graphics.Path()
        path.moveTo(ix - 150 * u, iy); path.lineTo(ix, iy - 60 * u); path.lineTo(ix + 150 * u, iy); path.lineTo(ix, iy + 60 * u); path.close()
        c.drawPath(path, Art.fill(Color.rgb(96, 160, 76)))
        path.reset(); path.moveTo(ix - 150 * u, iy); path.lineTo(ix, iy + 60 * u); path.lineTo(ix, iy + 80 * u); path.lineTo(ix - 150 * u, iy + 20 * u); path.close()
        c.drawPath(path, Art.fill(Color.rgb(120, 86, 56)))
        path.reset(); path.moveTo(ix, iy + 60 * u); path.lineTo(ix + 150 * u, iy); path.lineTo(ix + 150 * u, iy + 20 * u); path.lineTo(ix, iy + 80 * u); path.close()
        c.drawPath(path, Art.fill(Color.rgb(92, 64, 42)))
        // Kratons qui sautillent
        for (k in 0..3) {
            val x = ix + (k - 1.5f) * 62 * u
            val y = iy - 8 * u - abs(sin(time * 3f + k * 1.3f)) * 14 * u
            c.drawOval(RectF(x - 18 * u, iy - 2 * u, x + 18 * u, iy + 8 * u), Art.fill(0x40000000))
            ui.kraton(c, x, y - 16 * u, 40 * u, k, sin(time + k) * 2f)
        }
        ui.text(c, "KRATOUR", ix, ui.h * 0.26f, 54f, Art.UI_ACCENT, Paint.Align.CENTER, shadow = true)
        ui.text(c, "La Baston des Kratons", ix, ui.h * 0.26f + 28 * u, 16f, Art.UI_TEXT, Paint.Align.CENTER, shadow = true)
        ui.text(c, "Tactique · Posé · Équitable", ix, ui.h * 0.26f + 50 * u, 12f, Art.UI_DIM, Paint.Align.CENTER, bold = false)

        ui.panel(c, panel)
        ui.text(c, "Mode de jeu", panel.left + 16 * u, panel.top + 30 * u, 14f, Art.UI_DIM)
        ui.button(c, duelBtn, "Duel 1 contre 1", selected = app.prefs.mapId == MapId.DUEL, size = 13f)
        ui.button(c, meleeBtn, "Mêlée à 4", selected = app.prefs.mapId == MapId.MELEE, size = 13f)
        val sub = if (app.prefs.mapId == MapId.DUEL) "Vous contre 1 IA · deux forts, trois ponts"
        else "Vous contre 3 IA indépendantes (elles ne sont pas alliées)"
        ui.text(c, sub, panel.left + 16 * u, duelBtn.bottom + 18 * u, 10.5f, Art.UI_DIM, bold = false)

        ui.text(c, "Niveau de l'IA", panel.left + 16 * u, minusBtn.top - 10 * u, 14f, Art.UI_DIM)
        ui.button(c, minusBtn, "−", enabled = app.prefs.level > 1, size = 22f)
        ui.button(c, plusBtn, "+", enabled = app.prefs.level < 10, size = 22f)
        ui.panel(c, levelBar, Art.UI_BG_LIGHT)
        val lvl = app.prefs.level
        ui.text(c, "$lvl — ${LEVEL_NAMES[lvl]}", levelBar.centerX(), levelBar.centerY() + 2 * u, 14f, Art.UI_TEXT, Paint.Align.CENTER)
        val segW = (levelBar.width() - 16 * u) / 10f
        for (i in 1..10) {
            val x = levelBar.left + 8 * u + (i - 1) * segW
            val r = RectF(x + 1 * u, levelBar.bottom - 10 * u, x + segW - 1 * u, levelBar.bottom - 5 * u)
            c.drawRect(r, Art.fill(if (i <= lvl) Sprites.mix(Color.rgb(90, 220, 90), Color.rgb(240, 70, 60), (i - 1) / 9f) else Color.argb(90, 255, 255, 255)))
        }
        ui.button(c, playBtn, "JOUER", accent = true, size = 20f)
        ui.button(c, helpBtn, "Comment jouer", size = 12f)
        ui.button(c, soundBtn, if (app.sound.enabled) "Son : activé" else "Son : coupé", size = 12f)
        ui.text(c, "L'IA ne triche jamais : même vue, mêmes objets, mêmes règles.", panel.centerX(), panel.bottom - 8 * u, 9.5f, Art.UI_DIM, Paint.Align.CENTER, bold = false)
    }

    override fun onTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_UP) return
        val x = e.x; val y = e.y
        val p = app.prefs
        when {
            duelBtn.contains(x, y) -> { p.mapId = MapId.DUEL; click() }
            meleeBtn.contains(x, y) -> { p.mapId = MapId.MELEE; click() }
            minusBtn.contains(x, y) && p.level > 1 -> { p.level--; click() }
            plusBtn.contains(x, y) && p.level < 10 -> { p.level++; click() }
            playBtn.contains(x, y) -> { click(); app.startGame(GameConfig(p.mapId, p.level)) }
            helpBtn.contains(x, y) -> { click(); app.showHelp() }
            soundBtn.contains(x, y) -> { app.toggleSound(); click() }
        }
    }

    private fun click() = app.sound.play(SoundFx.S.CLICK)

    override fun onBack(): Boolean = false
}
