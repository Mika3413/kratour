package com.kratour.game

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.kratour.core.GameConfig
import com.kratour.core.MapId

/** Préférences persistantes (mode, niveau, son). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("kratour", Context.MODE_PRIVATE)
    var level: Int
        get() = sp.getInt("level", 4)
        set(v) { sp.edit().putInt("level", v.coerceIn(1, 10)).apply() }
    var mapId: MapId
        get() = runCatching { MapId.valueOf(sp.getString("map", MapId.DUEL.name)!!) }.getOrDefault(MapId.DUEL)
        set(v) { sp.edit().putString("map", v.name).apply() }
    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) { sp.edit().putBoolean("sound", v).apply() }
}

/** Services partagés entre les écrans. */
interface GameApp {
    val ui: Ui
    val sound: SoundFx
    val prefs: Prefs
    fun startGame(config: GameConfig)
    fun showMenu()
    fun showHelp()
    fun toggleSound()
}

/**
 * Vue unique du jeu : boucle d'animation sur le thread UI (rendu accéléré matériellement),
 * simulation à pas fixe, et aiguillage du toucher vers l'écran courant.
 */
class GameView(context: Context) : View(context), GameApp {
    override val ui = Ui()
    override val prefs = Prefs(context)
    override val sound = SoundFx(context).also { it.enabled = prefs.sound }
    private var screen: Screen = MenuScreen(this)
    private var lastFrame = 0L
    private var running = true

    init {
        isFocusable = true
        keepScreenOn = true
    }

    private fun setScreen(s: Screen) {
        screen.dispose()
        screen = s
        if (width > 0) s.resize(width, height) else if (ui.w > 1f) s.resize(ui.w.toInt(), ui.h.toInt())
        invalidate()
    }

    override fun startGame(config: GameConfig) = setScreen(GameScreen(this, config))
    override fun showMenu() = setScreen(MenuScreen(this))
    override fun showHelp() = setScreen(HelpScreen(this))
    override fun toggleSound() {
        sound.enabled = !sound.enabled
        prefs.sound = sound.enabled
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ui.resize(w, h)
        screen.resize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
        lastFrame = now
        screen.update(dt)
        screen.draw(canvas)
        if (running) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        screen.onTouch(event)
        return true
    }

    fun onBackPressedInGame(): Boolean = screen.onBack()

    fun pause() {
        running = false
        screen.onPause()
    }

    fun resume() {
        running = true
        lastFrame = 0L
        invalidate()
    }

    fun release() {
        screen.dispose()
        sound.release()
    }

    // ------------------------------------------------------------------ Outils de test (captures)

    fun layoutForTest(w: Int, h: Int) { ui.resize(w, h); screen.resize(w, h) }

    fun drawFrame(c: Canvas) = screen.draw(c)

    /** Avance l'écran courant de [seconds] secondes simulées. */
    fun stepForTest(seconds: Float) {
        var t = 0f
        while (t < seconds) { screen.update(0.05f); t += 0.05f }
    }
}
