package com.kratour.core

/** Outil de diagnostic : déroule une partie IA contre IA et journalise les événements clés. */
object DebugGame {
    @JvmStatic
    fun main(args: Array<String>) {
        val seed = args.getOrNull(0)?.toLong() ?: 6L
        val l0 = args.getOrNull(1)?.toInt() ?: 6
        val l1 = args.getOrNull(2)?.toInt() ?: 6
        val map = if (args.getOrNull(3) == "melee") MapId.MELEE else MapId.DUEL
        val levels = if (map == MapId.MELEE) listOf(l0, l1, l1, l1) else listOf(l0, l1)
        val s = GameSession(GameConfig(map, 5, seed, humanTeam = -1, levels = levels))
        val w = s.world
        var nextReport = 0f
        while (!w.over && w.time < 40 * 60) {
            s.tick()
            for (e in w.events) when (e.type) {
                EventType.BRICK_BREAK, EventType.TEAM_ELIMINATED, EventType.VICTORY, EventType.DEATH, EventType.FIRST_BLOOD, EventType.BARRICADE_UP ->
                    println("%6.1fs %-16s team=%d at (%.0f,%.0f) %s".format(w.time, e.type, e.team, e.x, e.y, e.text))
                else -> Unit
            }
            w.events.clear()
            if (w.time >= nextReport) {
                nextReport += 30f
                for (t in w.teams) {
                    val cs = w.aliveOf(t.id)
                    println("   [%5.0fs] team %d alive=%d bag=%s eq=%s".format(w.time, t.id, cs.size, t.bag, cs.map { "${it.weapon}/${it.defense}/${it.power}@${it.x},${it.y}:${it.hp.toInt()}" }))
                }
            }
        }
        println("winner=${w.winner} time=%.1f min".format(w.time / 60))
    }
}
