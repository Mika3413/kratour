package com.kratour.core

/**
 * Ce qu'une créature ennemie laisse voir : position, PV (barre de vie affichée)
 * et types d'objets portés (dessinés sur la créature). Pas les charges restantes,
 * pas les ordres, et rien du tout si elle est invisible.
 */
class EnemyInfo(
    val id: Int,
    val team: Int,
    val x: Int,
    val y: Int,
    val hp: Float,
    val weapon: ItemType?,
    val defense: ItemType?,
    val power: ItemType?,
    val frozen: Boolean,
) {
    fun pos() = Pos(x, y)
}

/**
 * Vue d'une équipe sur la partie : c'est la SEULE interface de l'IA avec le monde.
 * Elle expose exactement les informations dont dispose un joueur humain de cette équipe
 * (pas de créatures invisibles ennemies, pas de sacs ni de choix des autres équipes).
 */
class TeamView(private val world: World, val team: Int) {
    val time get() = world.time
    val map: GameMap get() = world.map
    val pathfinder: Pathfinder get() = world.pathfinder
    val spawnTimer get() = world.spawnTimer
    val offerTimer get() = world.offerTimer
    val teamCount get() = world.teams.size

    val bag: List<Item> get() = world.teams[team].bag
    val pendingOffer: Offer? get() = world.teams[team].pendingOffer
    val eliminated: Boolean get() = world.teams[team].eliminated
    fun isEliminated(t: Int) = world.teams[t].eliminated

    /** Nos créatures (informations complètes). */
    fun mine(): List<Creature> = world.creatures.filter { it.alive && it.team == team }

    /** Ennemis visibles uniquement. */
    fun enemies(): List<EnemyInfo> = world.creatures
        .filter { it.alive && it.team != team && !it.isInvisible }
        .map { EnemyInfo(it.id, it.team, it.x, it.y, it.hp, it.weapon?.type, it.defense?.type, it.power?.type, it.frozenTimer > 0f) }

    fun groundItems(): List<GroundItem> = world.groundItems
    fun barricadeAt(x: Int, y: Int) = world.barricadeAt(x, y)
    fun passable(t: Int, x: Int, y: Int) = world.passable(t, x, y)
    fun isEnemyStructure(x: Int, y: Int) = world.isEnemyStructure(team, x, y)
    fun canPlaceBarricade(x: Int, y: Int) = world.canPlaceBarricade(x, y)
    fun repairTarget(x: Int, y: Int) = world.repairTarget(team, x, y)

    // Actions : exactement les mêmes que celles du joueur.
    fun issue(cmd: Command) = world.issue(team, cmd)
    fun chooseOffer(offerId: Int, option: Int) = world.chooseOffer(team, offerId, option)
    fun assignFromBag(index: Int, creatureId: Int) = world.assignFromBag(team, index, creatureId)
}
