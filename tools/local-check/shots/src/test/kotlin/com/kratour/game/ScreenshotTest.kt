package com.kratour.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import org.robolectric.RuntimeEnvironment
import com.kratour.core.GameConfig
import com.kratour.core.MapId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xhdpi")
class ScreenshotTest {
    private val w = 2340
    private val h = 1080

    private fun save(view: GameView, name: String) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.drawFrame(Canvas(bmp))
        val dir = File(System.getProperty("shots.dir") ?: "out/shots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(view: GameView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.onTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun screens() {
        val view = GameView(RuntimeEnvironment.getApplication())
        view.layoutForTest(w, h)
        save(view, "01_menu")
        view.showHelp(); save(view, "02_help")
        view.startGame(GameConfig(MapId.DUEL, 5, seed = 3))
        view.stepForTest(1f); save(view, "03_duel_start")
        view.stepForTest(30f); save(view, "04_duel_30s")
        view.stepForTest(150f); save(view, "05_duel_180s")
        tap(view, 720f, 980f); view.stepForTest(0.1f); save(view, "05b_selected")
        tap(view, 2217f, 957f); view.stepForTest(0.1f); save(view, "05c_bag")
        view.startGame(GameConfig(MapId.MELEE, 6, seed = 5))
        view.stepForTest(200f); save(view, "06_melee_200s")
    }
}
