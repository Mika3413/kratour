package com.kratour.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parties complètes IA contre IA : vérifie que les parties se terminent,
 * mesure leur durée et que les niveaux élevés battent les niveaux faibles.
 */
class AiSimulationTest {

    class Result(val winner: Int, val minutes: Float)

    private fun play(map: MapId, levels: List<Int>, seed: Long, maxMinutes: Float = 40f): Result {
        val s = GameSession(GameConfig(map, 5, seed, humanTeam = -1, levels = levels))
        val maxTicks = (maxMinutes * 60f / Balance.TICK).toInt()
        var i = 0
        while (!s.world.over && i < maxTicks) { s.tick(); s.world.events.clear(); i++ }
        return Result(s.world.winner, s.world.time / 60f)
    }

    @Test
    fun duelGamesFinish() {
        val res = (1..6).map { play(MapId.DUEL, listOf(6, 6), it.toLong()) }
        res.forEach { println("duel 6v6 -> winner ${it.winner} in %.1f min".format(it.minutes)) }
        assertTrue(res.count { it.winner >= 0 } >= 5)
    }

    @Test
    fun strongAiBeatsWeakAi() {
        var strongWins = 0
        val n = 6
        for (s in 1..n) {
            // On alterne les côtés pour neutraliser tout biais de position.
            val strongTeam = s % 2
            val levels = if (strongTeam == 0) listOf(9, 2) else listOf(2, 9)
            val r = play(MapId.DUEL, levels, 100L + s)
            println("9 vs 2 (fort=$strongTeam) -> winner ${r.winner} in %.1f min".format(r.minutes))
            if (r.winner == strongTeam) strongWins++
        }
        assertTrue("niveau 9 doit gagner presque toujours ($strongWins/$n)", strongWins >= n - 1)
    }

    @Test
    fun level10BeatsLevel6() {
        var wins = 0
        val n = 6
        for (s in 1..n) {
            val strongTeam = s % 2
            val levels = if (strongTeam == 0) listOf(10, 6) else listOf(6, 10)
            val r = play(MapId.DUEL, levels, 300L + s)
            println("10 vs 6 (fort=$strongTeam) -> winner ${r.winner} in %.1f min".format(r.minutes))
            if (r.winner == strongTeam) wins++
        }
        assertTrue("niveau 10 doit dominer le niveau 6 ($wins/$n)", wins >= n / 2 + 1)
    }

    @Test
    fun fourTeamGamesFinish() {
        val res = (1..3).map { play(MapId.MELEE, listOf(7, 7, 7, 7), 500L + it) }
        res.forEach { println("melee -> winner ${it.winner} in %.1f min".format(it.minutes)) }
        assertTrue(res.count { it.winner >= 0 } >= 2)
    }
}
