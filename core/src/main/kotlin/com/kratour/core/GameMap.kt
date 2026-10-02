package com.kratour.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class Terrain(val walkable: Boolean, val tall: Boolean = false) {
    GRASS(true),
    DIRT(true),
    SAND(true),
    BRIDGE(true),
    WATER(false),
    ROCK(false, tall = true),
    TREE(false, tall = true),
    /** Case du fort : seuls les ennemis du propriétaire peuvent y entrer (et gagnent alors). */
    FORT(true),
}

/** Description statique d'un fort et de son équipe. */
class FortInfo(
    val team: Int,
    /** Coin haut-gauche (x, y) du fort 2x2. */
    val x: Int,
    val y: Int,
    /** Case d'apparition des nouvelles créatures. */
    val spawnX: Int,
    val spawnY: Int,
) {
    val centerX: Float get() = x + 0.5f
    val centerY: Float get() = y + 0.5f
    fun contains(tx: Int, ty: Int) = tx in x..x + 1 && ty in y..y + 1
    fun tiles(): List<Pos> = listOf(Pos(x, y), Pos(x + 1, y), Pos(x, y + 1), Pos(x + 1, y + 1))
}

data class Pos(val x: Int, val y: Int) {
    fun cheb(o: Pos) = max(abs(x - o.x), abs(y - o.y))
    fun dist(o: Pos): Float {
        val dx = (x - o.x).toFloat(); val dy = (y - o.y).toFloat()
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Grille de la carte. Le terrain de base est statique ; les briques, barricades
 * et la case "fort" sont des couches dynamiques gérées ici.
 */
class GameMap(val width: Int, val height: Int, val name: String) {
    val terrain = Array(width * height) { Terrain.GRASS }

    /** PV des briques (0 = pas de brique / détruite). */
    val brickHp = FloatArray(width * height)
    /** Équipe propriétaire de la brique (ou de la ruine), -1 sinon. */
    val brickOwner = IntArray(width * height) { -1 }
    /** Équipe propriétaire de la case fort, -1 sinon. */
    val fortOwner = IntArray(width * height) { -1 }

    val forts = ArrayList<FortInfo>()
    val bonuses = ArrayList<BonusSpot>()

    /** Incrémenté à chaque changement de praticabilité (brique cassée, barricade...). */
    var version = 0

    fun idx(x: Int, y: Int) = y * width + x
    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun terrainAt(x: Int, y: Int) = terrain[idx(x, y)]
    fun hasBrick(x: Int, y: Int) = inside(x, y) && brickHp[idx(x, y)] > 0f
    /** Ancienne brique détruite (gravats) : reconstructible. */
    fun isRubble(x: Int, y: Int) = inside(x, y) && brickOwner[idx(x, y)] >= 0 && brickHp[idx(x, y)] <= 0f

    fun set(x: Int, y: Int, t: Terrain) {
        if (inside(x, y)) terrain[idx(x, y)] = t
    }

    fun addBrick(x: Int, y: Int, team: Int) {
        if (!inside(x, y)) return
        val i = idx(x, y)
        terrain[i] = Terrain.DIRT
        brickHp[i] = Balance.BRICK_HP
        brickOwner[i] = team
    }

    /**
     * Case praticable pour une créature de [team] (hors créatures et barricades).
     * Une créature ne peut pas marcher dans son propre fort.
     */
    fun walkableStatic(x: Int, y: Int, team: Int): Boolean {
        if (!inside(x, y)) return false
        val i = idx(x, y)
        if (!terrain[i].walkable) return false
        if (brickHp[i] > 0f) return false
        val fo = fortOwner[i]
        if (fo >= 0 && fo == team) return false
        return true
    }

    fun fortOf(team: Int): FortInfo? = forts.firstOrNull { it.team == team }

    fun bricksOf(team: Int): List<Pos> {
        val out = ArrayList<Pos>()
        for (y in 0 until height) for (x in 0 until width) {
            if (brickOwner[idx(x, y)] == team) out.add(Pos(x, y))
        }
        return out
    }
}
