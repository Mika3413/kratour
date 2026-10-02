package com.kratour.core

/** Configuration d'une partie. */
class GameConfig(
    val mapId: MapId,
    /** Niveau de chaque IA (1 à 10). */
    val aiLevel: Int,
    val seed: Long = System.nanoTime(),
    /** Équipe humaine, ou -1 pour une partie entièrement jouée par l'IA (tests). */
    val humanTeam: Int = 0,
    /** Niveaux individuels optionnels (sinon [aiLevel]). */
    val levels: List<Int>? = null,
)

object TeamNames {
    val names = listOf("Bleus", "Rouges", "Verts", "Ors")
}

/** Une partie : le monde + les IA. Avance par pas fixes. */
class GameSession(val config: GameConfig) {
    val world = World(config.mapId, TeamNames.names.take(config.mapId.teams), config.humanTeam, config.seed)
    val ais: List<AiController> = world.teams.filter { it.id != config.humanTeam }.map {
        AiController(it.id, config.levels?.get(it.id) ?: config.aiLevel, config.seed * 31 + it.id)
    }
    private val views = ais.associate { it.team to TeamView(world, it.team) }
    val humanView: TeamView? = if (config.humanTeam >= 0) TeamView(world, config.humanTeam) else null
    private var acc = 0f
    var paused = false

    /** Avance la simulation de [dt] secondes réelles (pas fixes, rattrapage borné). */
    fun advance(dt: Float) {
        if (paused || world.over) return
        acc += dt.coerceAtMost(0.25f)
        while (acc >= Balance.TICK) {
            acc -= Balance.TICK
            tick()
        }
    }

    fun tick() {
        if (world.over) return
        for (ai in ais) ai.update(views.getValue(ai.team), Balance.TICK)
        world.step()
    }
}
