package com.kratour.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

enum class MapId(val label: String, val teams: Int) {
    DUEL("La Rivière Fendue (1 contre 1)", 2),
    MELEE("Le Carrefour des Quatre (4 équipes)", 4),
}

/**
 * Construit les cartes. Chaque élément est placé via les transformations de symétrie
 * de la carte : chaque équipe a donc exactement le même terrain, les mêmes distances
 * et les mêmes opportunités.
 */
object Maps {

    fun build(id: MapId): GameMap = when (id) {
        MapId.DUEL -> duel()
        MapId.MELEE -> melee()
    }

    private class Builder(val map: GameMap, val transforms: List<(Int, Int) -> Pos>) {
        val n = map.width

        fun place(x: Int, y: Int, t: Terrain) {
            for (tr in transforms) { val p = tr(x, y); map.set(p.x, p.y, t) }
        }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, t: Terrain) {
            for (y in y0..y1) for (x in x0..x1) place(x, y, t)
        }

        fun bonus(x: Int, y: Int, type: BonusType) {
            for (tr in transforms) {
                val p = tr(x, y)
                if (map.bonuses.none { it.x == p.x && it.y == p.y }) map.bonuses.add(BonusSpot(p.x, p.y, type))
            }
        }

        /** Place un fort 2x2 (coin (fx,fy)) et sa double enceinte de briques, pour chaque symétrie. */
        fun fort(fx: Int, fy: Int, teamOfTransform: (Int) -> Int) {
            val cx = n / 2f; val cy = map.height / 2f
            transforms.forEachIndexed { k, tr ->
                val team = teamOfTransform(k)
                val corners = listOf(tr(fx, fy), tr(fx + 1, fy), tr(fx, fy + 1), tr(fx + 1, fy + 1))
                val minX = corners.minOf { it.x }; val minY = corners.minOf { it.y }
                // Sol de terre autour du fort
                for (y in minY - 4..minY + 5) for (x in minX - 4..minX + 5) {
                    if (map.inside(x, y) && map.terrainAt(x, y) == Terrain.GRASS) map.set(x, y, Terrain.DIRT)
                }
                for (y in minY - 2..minY + 3) for (x in minX - 2..minX + 3) {
                    if (!map.inside(x, y)) continue
                    val inFort = x in minX..minX + 1 && y in minY..minY + 1
                    if (inFort) {
                        map.set(x, y, Terrain.FORT)
                        map.fortOwner[map.idx(x, y)] = team
                    } else {
                        map.addBrick(x, y, team)
                    }
                }
                // Point d'apparition : en direction du centre de la carte, juste hors de l'enceinte.
                val fcx = minX + 0.5f; val fcy = minY + 0.5f
                val dx = sign(cx - fcx).toInt(); val dy = sign(cy - fcy).toInt()
                val sx = (fcx + dx * 4f).roundToInt().coerceIn(0, n - 1)
                val sy = (fcy + dy * 4f).roundToInt().coerceIn(0, map.height - 1)
                map.forts.add(FortInfo(team, minX, minY, sx, sy))
            }
            map.forts.sortBy { it.team }
        }
    }

    // ------------------------------------------------------------------ 1 contre 1
    private fun duel(): GameMap {
        val n = 34
        val map = GameMap(n, n, MapId.DUEL.label)
        val b = Builder(map, listOf({ x, y -> Pos(x, y) }, { x, y -> Pos(n - 1 - x, n - 1 - y) }))

        // Rivière sur la diagonale x = y, qui sépare les deux camps.
        for (y in 0 until n) for (x in 0 until n) if (abs(x - y) <= 1) map.set(x, y, Terrain.WATER)
        // Trois ponts : centre (large) et deux flancs.
        for (y in 0 until n) for (x in 0 until n) {
            if (abs(x - y) > 1) continue
            val s = x + y
            if (abs(s - 33) <= 3 || abs(s - 14) <= 2 || abs(s - 52) <= 2) map.set(x, y, Terrain.BRIDGE)
        }
        // Berges sableuses
        for (y in 0 until n) for (x in 0 until n) if (abs(x - y) == 2) map.set(x, y, Terrain.SAND)

        // Chemins de terre vers les ponts (camp 0 = bas-gauche de la grille, symétrique pour l'autre)
        for (i in 0..7) { b.place(9 + i, 23 - i, Terrain.DIRT); b.place(10 + i, 23 - i, Terrain.DIRT) }
        for (i in 0..14) { b.place(9 - (i / 3), 22 - i, Terrain.DIRT) }
        for (i in 0..12) { b.place(10 + i, 25 + (i / 4), Terrain.DIRT) }

        // Bosquets et rochers : couvrent les flancs et créent des couloirs.
        b.rect(13, 27, 14, 28, Terrain.TREE)
        b.rect(2, 16, 3, 17, Terrain.TREE)
        b.place(12, 18, Terrain.ROCK); b.place(13, 19, Terrain.ROCK); b.place(12, 19, Terrain.ROCK)
        b.place(19, 25, Terrain.ROCK); b.place(20, 25, Terrain.ROCK)
        b.place(5, 13, Terrain.ROCK); b.place(6, 13, Terrain.ROCK)
        b.place(17, 31, Terrain.TREE); b.place(18, 31, Terrain.TREE); b.place(18, 32, Terrain.TREE)
        b.place(1, 22, Terrain.TREE); b.place(1, 23, Terrain.TREE)
        b.place(23, 30, Terrain.ROCK); b.place(24, 31, Terrain.ROCK)
        b.place(8, 18, Terrain.TREE)
        b.place(15, 22, Terrain.TREE)

        b.fort(5, 27) { k -> k }

        // Bonus neutres
        b.bonus(4, 11, BonusType.POTION)      // flanc haut
        b.bonus(22, 29, BonusType.POTION)     // flanc bas
        b.bonus(14, 18, BonusType.RECHARGE)   // vers le pont central
        b.bonus(16, 18, BonusType.HASTE)      // tête de pont centrale
        return map
    }

    // ------------------------------------------------------------------ 4 équipes
    private fun melee(): GameMap {
        val n = 44
        val m = n - 1
        val map = GameMap(n, n, MapId.MELEE.label)
        val rot = listOf<(Int, Int) -> Pos>(
            { x, y -> Pos(x, y) },
            { x, y -> Pos(m - y, x) },
            { x, y -> Pos(m - x, m - y) },
            { x, y -> Pos(y, m - x) },
        )
        val b = Builder(map, rot)

        // Ruisseaux entre forts voisins (avec un pont chacun).
        for (y in 0..13) { b.place(21, y, Terrain.WATER); b.place(22, y, Terrain.WATER) }
        for (y in 6..8) { b.place(21, y, Terrain.BRIDGE); b.place(22, y, Terrain.BRIDGE) }
        b.place(20, 13, Terrain.SAND); b.place(23, 13, Terrain.SAND)
        for (y in 0..12) { b.place(20, y, Terrain.SAND) }

        // Place centrale : anneau de rochers avec 4 ouvertures diagonales.
        for (y in 17..26) for (x in 17..26) {
            val edge = x == 17 || x == 26 || y == 17 || y == 26
            if (edge) map.set(x, y, Terrain.ROCK)
        }
        for (k in 0..1) { b.place(17 + k, 17, Terrain.DIRT); b.place(17, 17 + k, Terrain.DIRT) }
        b.place(21, 26, Terrain.DIRT); b.place(22, 26, Terrain.DIRT) // ouvertures sur chaque face
        for (y in 18..25) for (x in 18..25) map.set(x, y, Terrain.DIRT)
        b.rect(21, 21, 22, 22, Terrain.WATER) // fontaine centrale (2x2 => reste symétrique)

        // Chemins vers le centre
        for (i in 0..9) { b.place(9 + i, 9 + i, Terrain.DIRT); b.place(10 + i, 9 + i, Terrain.DIRT) }
        for (i in 0..10) { b.place(10 + i, 7, Terrain.DIRT) }

        // Couverts dans chaque quadrant
        b.rect(3, 13, 4, 14, Terrain.TREE)
        b.rect(13, 3, 14, 4, Terrain.TREE)
        b.place(14, 11, Terrain.ROCK); b.place(11, 14, Terrain.ROCK)
        b.place(15, 12, Terrain.ROCK); b.place(12, 15, Terrain.ROCK)
        b.place(7, 18, Terrain.TREE); b.place(8, 19, Terrain.TREE)
        b.place(17, 1, Terrain.TREE); b.place(18, 2, Terrain.TREE)
        b.place(1, 9, Terrain.ROCK)

        // Forts : le joueur (équipe 0) est en bas de l'écran (bas-droite de la grille).
        b.fort(5, 5) { k -> (k + 2) % 4 }

        b.bonus(10, 18, BonusType.POTION)
        b.bonus(19, 11, BonusType.RECHARGE)
        b.bonus(20, 20, BonusType.HASTE)
        return map
    }
}
