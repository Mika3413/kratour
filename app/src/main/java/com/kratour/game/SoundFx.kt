package com.kratour.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Effets sonores synthétisés au démarrage (aucun fichier audio externe) :
 * on génère de courts échantillons PCM, écrits en WAV dans le cache, joués via SoundPool.
 */
class SoundFx(context: Context) {
    enum class S { SELECT, ORDER, HIT, SWING, BLOCK, SHOOT, THROW, EXPLODE, DEATH, BRICK, BREAK, PICKUP, SPAWN, OFFER, POWER, WIN, LOSE, CLICK, FREEZE, ELIMINATED }

    private val rate = 22050
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
    private val ids = IntArray(S.values().size)
    private val lastPlayed = LongArray(S.values().size)
    var enabled = true

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        for (s in S.values()) {
            val f = File(dir, "${s.name.lowercase()}_v2.wav")
            if (!f.exists()) writeWav(f, synth(s))
            ids[s.ordinal] = pool.load(f.absolutePath, 1)
        }
    }

    fun play(s: S, volume: Float = 1f, pitch: Float = 1f) {
        if (!enabled || volume <= 0.02f) return
        val now = System.currentTimeMillis()
        if (now - lastPlayed[s.ordinal] < 55) return
        lastPlayed[s.ordinal] = now
        val v = volume.coerceIn(0f, 1f)
        pool.play(ids[s.ordinal], v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun release() = pool.release()

    // ------------------------------------------------------------------ Synthèse

    private fun synth(s: S): ShortArray {
        val rnd = Random(s.ordinal * 977)
        return when (s) {
            S.SELECT -> tone(0.07f) { t -> sq(t, 880f) * env(t, 0.07f, 30f) * 0.25f + sin2(t, 1320f) * env(t, 0.07f, 40f) * 0.2f }
            S.CLICK -> tone(0.04f) { t -> sin2(t, 1000f) * env(t, 0.04f, 60f) * 0.35f }
            S.ORDER -> tone(0.09f) { t -> sin2(t, 520f + t * 2000f) * env(t, 0.09f, 25f) * 0.4f }
            S.HIT -> tone(0.16f) { t -> (rnd.nextFloat() * 2 - 1) * env(t, 0.16f, 28f) * 0.55f + sin2(t, 140f - t * 300f) * env(t, 0.16f, 18f) * 0.5f }
            S.SWING -> tone(0.12f) { t -> (rnd.nextFloat() * 2 - 1) * sin(PI.toFloat() * t / 0.12f) * 0.18f }
            S.BLOCK -> tone(0.22f) { t -> (sin2(t, 1650f) * 0.4f + sin2(t, 2480f) * 0.25f) * env(t, 0.22f, 16f) + (rnd.nextFloat() * 2 - 1) * env(t, 0.03f, 90f) * 0.3f }
            S.SHOOT -> tone(0.16f) { t -> sin2(t, 300f - t * 800f) * env(t, 0.16f, 20f) * 0.35f + (rnd.nextFloat() * 2 - 1) * env(t, 0.05f, 50f) * 0.3f }
            S.THROW -> tone(0.2f) { t -> sin2(t, 200f + t * 900f) * env(t, 0.2f, 12f) * 0.3f }
            S.EXPLODE -> {
                var lp = 0f
                tone(0.7f) { t -> lp += ((rnd.nextFloat() * 2 - 1) - lp) * 0.12f; lp * 2.6f * env(t, 0.7f, 5f) + sin2(t, 60f) * env(t, 0.7f, 6f) * 0.5f }
            }
            S.DEATH -> tone(0.45f) { t -> sq(t, 420f - t * 700f) * env(t, 0.45f, 6f) * 0.22f }
            S.BRICK -> {
                var lp = 0f
                tone(0.12f) { t -> lp += ((rnd.nextFloat() * 2 - 1) - lp) * 0.3f; lp * env(t, 0.12f, 30f) * 1.2f + sin2(t, 90f) * env(t, 0.12f, 30f) * 0.4f }
            }
            S.BREAK -> {
                var lp = 0f
                tone(0.5f) { t -> lp += ((rnd.nextFloat() * 2 - 1) - lp) * 0.2f; lp * env(t, 0.5f, 7f) * 1.8f * (if ((t * 30).toInt() % 3 == 0) 1f else 0.5f) }
            }
            S.PICKUP -> tone(0.22f) { t -> val f = if (t < 0.07f) 660f else if (t < 0.14f) 880f else 1100f; sin2(t, f) * env(t, 0.22f, 8f) * 0.35f }
            S.SPAWN -> tone(0.4f) { t -> sin2(t, 300f + t * 900f) * sin(PI.toFloat() * t / 0.4f) * 0.35f }
            S.OFFER -> tone(0.45f) { t -> val f = if (t < 0.15f) 784f else 1046f; (sin2(t, f) * 0.35f + sin2(t, f * 2) * 0.1f) * env(t, 0.45f, 5f) }
            S.POWER -> tone(0.4f) { t -> (sin2(t, 500f + 400f * sin(t * 40f)) * 0.3f) * env(t, 0.4f, 5f) }
            S.FREEZE -> tone(0.4f) { t -> (sin2(t, 2000f - t * 2000f) * 0.25f + (rnd.nextFloat() * 2 - 1) * 0.08f) * env(t, 0.4f, 6f) }
            S.WIN -> tone(1.1f) { t ->
                val notes = floatArrayOf(523f, 659f, 784f, 1046f)
                val i = (t / 0.22f).toInt().coerceAtMost(3)
                (sin2(t, notes[i]) * 0.3f + sq(t, notes[i] / 2) * 0.06f) * env(t - i * 0.22f, 0.4f, 3f)
            }
            S.LOSE -> tone(1.1f) { t ->
                val notes = floatArrayOf(392f, 330f, 262f, 196f)
                val i = (t / 0.25f).toInt().coerceAtMost(3)
                (sin2(t, notes[i]) * 0.3f + sq(t, notes[i] / 2) * 0.05f) * env(t - i * 0.25f, 0.5f, 3f)
            }
            S.ELIMINATED -> tone(0.7f) { t -> (sq(t, 220f) * 0.15f + sin2(t, 110f) * 0.3f) * env(t, 0.7f, 4f) }
        }
    }

    private fun sin2(t: Float, f: Float) = sin(2f * PI.toFloat() * f * t)
    private fun sq(t: Float, f: Float) = if (sin2(t, f) >= 0f) 1f else -1f
    private fun env(t: Float, len: Float, k: Float): Float {
        if (t < 0f) return 0f
        val attack = (t / 0.004f).coerceAtMost(1f)
        return attack * exp(-k * t) * (1f - (t / len).coerceIn(0f, 1f) * 0.2f)
    }

    private inline fun tone(seconds: Float, f: (Float) -> Float): ShortArray {
        val n = (seconds * rate).toInt()
        return ShortArray(n) { i -> (f(i.toFloat() / rate).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val out = ByteArray(44 + dataLen)
        fun i32(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(out, 0); i32(4, 36 + dataLen)
        "WAVE".toByteArray().copyInto(out, 8); "fmt ".toByteArray().copyInto(out, 12)
        i32(16, 16); i16(20, 1); i16(22, 1); i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16)
        "data".toByteArray().copyInto(out, 36); i32(40, dataLen)
        for (i in pcm.indices) i16(44 + i * 2, pcm[i].toInt())
        FileOutputStream(f).use { it.write(out) }
    }
}
