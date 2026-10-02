package com.kratour.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A* 8 directions sur la grille (pas de coupe de coin) et champs de distance.
 */
class Pathfinder(private val map: GameMap) {
    private val n = map.width * map.height
    private val g = FloatArray(n)
    private val came = IntArray(n)
    private val stamp = IntArray(n)
    private val closed = IntArray(n)
    private var cur = 0
    private val heap = IntArray(n * 8 + 16)
    private val heapF = FloatArray(n * 8 + 16)
    private var heapSize = 0

    companion object {
        val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        const val SQRT2 = 1.4142135f
    }

    private fun push(node: Int, f: Float) {
        var i = heapSize++
        heap[i] = node; heapF[i] = f
        while (i > 0) {
            val p = (i - 1) / 2
            if (heapF[p] <= heapF[i]) break
            swap(i, p); i = p
        }
    }

    private fun pop(): Int {
        val top = heap[0]
        heapSize--
        if (heapSize > 0) {
            heap[0] = heap[heapSize]; heapF[0] = heapF[heapSize]
            var i = 0
            while (true) {
                val l = i * 2 + 1; val r = l + 1
                var s = i
                if (l < heapSize && heapF[l] < heapF[s]) s = l
                if (r < heapSize && heapF[r] < heapF[s]) s = r
                if (s == i) break
                swap(i, s); i = s
            }
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val t = heap[a]; heap[a] = heap[b]; heap[b] = t
        val f = heapF[a]; heapF[a] = heapF[b]; heapF[b] = f
    }

    /**
     * Cherche un chemin de (sx,sy) vers l'une des cases [goals].
     * @param passable praticabilité d'une case
     * @param extraCost coût additionnel d'une case (créatures, danger...)
     * @return liste de cases (sans la case de départ), vide si déjà arrivé, null si impossible.
     */
    fun find(
        sx: Int, sy: Int,
        goals: Collection<Pos>,
        passable: (Int, Int) -> Boolean,
        extraCost: ((Int, Int) -> Float)? = null,
        maxExpand: Int = 4000,
    ): List<Pos>? {
        if (goals.isEmpty()) return null
        val start = map.idx(sx, sy)
        val goalSet = HashSet<Int>(goals.size * 2)
        for (gp in goals) if (map.inside(gp.x, gp.y)) goalSet.add(map.idx(gp.x, gp.y))
        if (start in goalSet) return emptyList()
        cur++
        heapSize = 0
        g[start] = 0f; came[start] = -1; stamp[start] = cur
        push(start, h(sx, sy, goals))
        var expanded = 0
        while (heapSize > 0) {
            val node = pop()
            if (closed[node] == cur) continue
            closed[node] = cur
            if (node in goalSet) return rebuild(node, start)
            if (++expanded > maxExpand) return null
            val x = node % map.width; val y = node / map.width
            for (d in 0 until 8) {
                val nx = x + DX[d]; val ny = y + DY[d]
                if (!map.inside(nx, ny)) continue
                val ni = map.idx(nx, ny)
                if (closed[ni] == cur) continue
                if (!passable(nx, ny)) continue
                if (d >= 4 && (!passable(x + DX[d], y) || !passable(x, y + DY[d]))) continue
                var cost = if (d >= 4) SQRT2 else 1f
                if (extraCost != null) cost += extraCost(nx, ny)
                val ng = g[node] + cost
                if (stamp[ni] != cur || ng < g[ni]) {
                    stamp[ni] = cur; g[ni] = ng; came[ni] = node
                    push(ni, ng + h(nx, ny, goals))
                }
            }
        }
        return null
    }

    private fun h(x: Int, y: Int, goals: Collection<Pos>): Float {
        if (goals.size > 40) return 0f // grand ensemble de buts : Dijkstra pur
        var best = Float.MAX_VALUE
        for (gp in goals) {
            val dx = abs(gp.x - x); val dy = abs(gp.y - y)
            val v = max(dx, dy) + (SQRT2 - 1f) * min(dx, dy)
            if (v < best) best = v
        }
        return best
    }

    private fun rebuild(end: Int, start: Int): List<Pos> {
        val out = ArrayList<Pos>()
        var c = end
        while (c != start && c >= 0) {
            out.add(Pos(c % map.width, c / map.width)); c = came[c]
        }
        out.reverse()
        return out
    }

    /**
     * Champ de distance (Dijkstra 8 dir) depuis les [sources]. Les cases impraticables valent +inf.
     * [blockedCost] permet de traverser des cases "chères" (ex. briques) au lieu de les interdire.
     */
    fun distanceField(
        sources: Collection<Pos>,
        passable: (Int, Int) -> Boolean,
        blockedCost: ((Int, Int) -> Float)? = null,
    ): FloatArray {
        val dist = FloatArray(n) { Float.POSITIVE_INFINITY }
        heapSize = 0
        for (s in sources) {
            if (!map.inside(s.x, s.y)) continue
            val i = map.idx(s.x, s.y); dist[i] = 0f; push(i, 0f)
        }
        while (heapSize > 0) {
            val f = heapF[0]
            val node = pop()
            if (f > dist[node]) continue
            val x = node % map.width; val y = node / map.width
            for (d in 0 until 8) {
                val nx = x + DX[d]; val ny = y + DY[d]
                if (!map.inside(nx, ny)) continue
                val pass = passable(nx, ny)
                val bc = if (!pass) blockedCost?.invoke(nx, ny) ?: continue else 0f
                if (bc.isInfinite()) continue
                if (d >= 4 && (!passable(x + DX[d], y) || !passable(x, y + DY[d]))) continue
                val ni = map.idx(nx, ny)
                val nd = f + (if (d >= 4) SQRT2 else 1f) + bc
                if (nd < dist[ni]) { dist[ni] = nd; push(ni, nd) }
            }
        }
        return dist
    }
}
