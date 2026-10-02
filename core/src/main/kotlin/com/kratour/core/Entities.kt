package com.kratour.core

/** Ordre courant d'une créature (donné par le joueur ou l'IA, via les mêmes commandes). */
sealed class Order {
    object Idle : Order()
    /** Aller sur une case (et ramasser ce qui s'y trouve à l'arrivée). */
    class Move(val x: Int, val y: Int) : Order()
    class Attack(val targetId: Int) : Order()
    /** Attaquer une brique ou une barricade ennemie. */
    class AttackStructure(val x: Int, val y: Int) : Order()
    /** Réparer les briques de son fort autour d'un point. */
    class Repair(val x: Int, val y: Int) : Order()
    class PlaceBarricade(val x: Int, val y: Int) : Order()
    class CastFreeze(val targetId: Int) : Order()
    /** Lancer une bombe sur une case. */
    class ThrowBomb(val x: Int, val y: Int) : Order()
}

class Creature(val id: Int, val team: Int, var x: Int, var y: Int) {
    var hp = Balance.MAX_HP
    var alive = true

    var weapon: Item? = null
    var defense: Item? = null
    var power: Item? = null

    // Déplacement case par case
    var tx = x
    var ty = y
    var moveT = 0f
    var moveDur = Balance.STEP_TIME
    val isMoving: Boolean get() = tx != x || ty != y
    var path: List<Pos> = emptyList()
    var pathIdx = 0
    var pathGoalKey = Long.MIN_VALUE
    var repathTimer = 0f
    var blockedTimer = 0f
    var noPathTimer = 0f

    // Orientation (pour le bouclier et l'affichage)
    var fdx = 1
    var fdy = 0

    var order: Order = Order.Idle
    /** Cible automatique (riposte / garde) — prioritaire sur l'ordre sauf déplacement explicite. */
    var autoTargetId = 0
    var anchorX = x
    var anchorY = y
    var scanTimer = 0f

    // Combat
    var attackCooldown = 0f
    var lastAttackerId = 0
    var lastHitTime = -100f
    var actionTimer = 0f // réparation / pose de barricade en cours

    // Effets temporaires
    var speedTimer = 0f
    var forceTimer = 0f
    var invisTimer = 0f
    var frozenTimer = 0f
    var stunTimer = 0f

    var kills = 0

    val weaponStats: WeaponStats get() = weapon?.type?.weapon ?: Balance.FISTS
    val isInvisible: Boolean get() = invisTimer > 0f
    val isDisabled: Boolean get() = frozenTimer > 0f || stunTimer > 0f

    /** Position affichée (interpolée pendant un pas). */
    val px: Float get() = x + (tx - x) * moveT
    val py: Float get() = y + (ty - y) * moveT

    fun pos() = Pos(x, y)

    fun itemIn(c: Category): Item? = when (c) {
        Category.WEAPON -> weapon
        Category.DEFENSE -> defense
        Category.POWER -> power
    }

    fun setItem(c: Category, item: Item?) {
        when (c) {
            Category.WEAPON -> weapon = item
            Category.DEFENSE -> defense = item
            Category.POWER -> power = item
        }
    }
}

class Team(val id: Int, val name: String, val human: Boolean) {
    var eliminated = false
    var eliminatedAt = -1f
    var eliminatedBy = -1
    val bag = ArrayList<Item>()
    var pendingOffer: Offer? = null
    var kills = 0
    var deaths = 0
    var spawned = 0
}

/** Proposition d'équipement : identique pour toutes les équipes. */
class Offer(val id: Int, val category: Category, val options: List<ItemType>)

class Barricade(val team: Int, val x: Int, val y: Int) {
    var hp = Balance.BARRICADE_HP
    var timeLeft = Balance.BARRICADE_DURATION
}

enum class ProjectileKind { BOLT, BOMB }

class Projectile(
    val kind: ProjectileKind,
    val team: Int,
    val ownerId: Int,
    val sx: Float, val sy: Float,
    var ex: Float, var ey: Float,
    val targetId: Int,
    val damage: Float,
    val structureX: Int = -1,
    val structureY: Int = -1,
    val duration: Float,
) {
    var t = 0f
    val px: Float get() = sx + (ex - sx) * (t / duration).coerceIn(0f, 1f)
    val py: Float get() = sy + (ey - sy) * (t / duration).coerceIn(0f, 1f)
}

enum class EventType {
    HIT, BLOCK, ABSORB, SHOOT, THROW, EXPLODE, DEATH, SPAWN, PICKUP, DROP,
    BRICK_HIT, BRICK_BREAK, BRICK_REPAIR, BARRICADE_UP, BARRICADE_DOWN,
    POWER, FREEZE, SHOCKWAVE, OFFER, TEAM_ELIMINATED, VICTORY, FIRST_BLOOD, BONUS,
    ITEM_BROKEN, SWING,
}

class GameEvent(
    val type: EventType,
    val x: Float,
    val y: Float,
    val team: Int = -1,
    val value: Float = 0f,
    val text: String = "",
)

/** Commandes : la seule façon (joueur ou IA) d'agir sur le monde. */
sealed class Command {
    abstract val creatureId: Int
    class MoveTo(override val creatureId: Int, val x: Int, val y: Int) : Command()
    class AttackCreature(override val creatureId: Int, val targetId: Int) : Command()
    class AttackTile(override val creatureId: Int, val x: Int, val y: Int) : Command()
    class UsePower(override val creatureId: Int, val targetId: Int = 0) : Command()
    class Repair(override val creatureId: Int, val x: Int, val y: Int) : Command()
    class Barricade(override val creatureId: Int, val x: Int, val y: Int) : Command()
    class Bomb(override val creatureId: Int, val x: Int, val y: Int) : Command()
    class Stop(override val creatureId: Int) : Command()
}
