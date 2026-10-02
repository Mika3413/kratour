package com.kratour.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTest {

    private fun dump(m: GameMap): String {
        val sb = StringBuilder()
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                val i = m.idx(x, y)
                val ch = when {
                    m.fortOwner[i] >= 0 -> ('A' + m.fortOwner[i])
                    m.brickHp[i] > 0 -> '#'
                    m.bonuses.any { it.x == x && it.y == y } -> '+'
                    m.forts.any { it.spawnX == x && it.spawnY == y } -> 's'
                    else -> when (m.terrain[i]) {
                        Terrain.GRASS -> '.'
                        Terrain.DIRT -> ','
                        Terrain.SAND -> ':'
                        Terrain.BRIDGE -> '='
                        Terrain.WATER -> '~'
                        Terrain.ROCK -> 'R'
                        Terrain.TREE -> 'T'
                        Terrain.FORT -> 'F'
                    }
                }
                sb.append(ch)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    @Test
    fun duelIsPointSymmetric() {
        val m = Maps.build(MapId.DUEL)
        println(dump(m))
        val n = m.width
        for (y in 0 until n) for (x in 0 until n) {
            val a = m.idx(x, y); val b = m.idx(n - 1 - x, n - 1 - y)
            assertEquals("terrain $x,$y", m.terrain[a], m.terrain[b])
            assertEquals(m.brickHp[a], m.brickHp[b], 0.01f)
        }
        assertEquals(2, m.forts.size)
        assertEquals(m.bricksOf(0).size, m.bricksOf(1).size)
    }

    @Test
    fun meleeIsRotationSymmetric() {
        val m = Maps.build(MapId.MELEE)
        println(dump(m))
        val n = m.width
        for (y in 0 until n) for (x in 0 until n) {
            val a = m.idx(x, y); val b = m.idx(n - 1 - y, x)
            assertEquals("terrain $x,$y", m.terrain[a], m.terrain[b])
        }
        assertEquals(4, m.forts.size)
        val counts = (0..3).map { m.bricksOf(it).size }.toSet()
        assertEquals(1, counts.size)
    }

    @Test
    fun everyFortIsReachableOnceBricksAreGone() {
        for (id in MapId.values()) {
            val m = Maps.build(id)
            val pf = Pathfinder(m)
            for (a in m.forts) for (b in m.forts) {
                if (a === b) continue
                // Distance en traversant les briques (coût) : doit être finie.
                val f = pf.distanceField(b.tiles(), { x, y -> m.walkableStatic(x, y, a.team) }, { x, y -> if (m.hasBrick(x, y)) 5f else Float.POSITIVE_INFINITY })
                val d = f[m.idx(a.spawnX, a.spawnY)]
                assertTrue("${id}: ${a.team} -> ${b.team}", d.isFinite())
                // Mais impossible sans casser de brique.
                val closed = pf.distanceField(b.tiles(), { x, y -> m.walkableStatic(x, y, a.team) })
                assertTrue(closed[m.idx(a.spawnX, a.spawnY)].isInfinite())
            }
            for (f in m.forts) assertTrue(m.walkableStatic(f.spawnX, f.spawnY, f.team))
        }
    }

    @Test
    fun duelHasSeveralIndependentRoutes() {
        // Bloquer un seul pont ne doit pas couper les deux camps.
        val m = Maps.build(MapId.DUEL)
        val pf = Pathfinder(m)
        val a = m.forts[0]; val b = m.forts[1]
        val bridges = HashSet<Pos>()
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.terrainAt(x, y) == Terrain.BRIDGE) bridges.add(Pos(x, y))
        // On regroupe les ponts par composante (x+y)
        val groups = bridges.groupBy { (it.x + it.y) / 8 }
        assertTrue("au moins 3 ponts", groups.size >= 3)
        for ((_, g) in groups) {
            val blocked = g.toSet()
            val f = pf.distanceField(listOf(Pos(b.spawnX, b.spawnY)), { x, y -> m.walkableStatic(x, y, a.team) && Pos(x, y) !in blocked })
            assertTrue(f[m.idx(a.spawnX, a.spawnY)].isFinite())
        }
    }
}
