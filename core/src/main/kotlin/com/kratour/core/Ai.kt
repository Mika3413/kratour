package com.kratour.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Paramètres de qualité de décision. Le niveau ne change JAMAIS les règles :
 * uniquement la vitesse de réaction, l'attention et la finesse des choix.
 */
class AiParams(val level: Int) {
    private val t = (level - 1) / 9f
    /** Intervalle entre deux réflexions (s). */
    val thinkInterval = 2.8f - 2.45f * t
    /** Nombre maximum d'ordres changés par réflexion (multitâche). */
    val ordersPerThink = (1 + 6 * t).roundToInt()
    /** Probabilité d'une mauvaise décision. */
    val blunder = 0.42f * (1 - t) * (1 - t)
    /** Délai avant de choisir un objet proposé / distribuer le sac (s). */
    val offerDelay = 14f - 12.5f * t
    val bagDelay = 22f - 20f * t
    val smartChoices = level >= 4
    val compositionAware = level >= 7
    val threatRadius = 3.5f + 0.75f * level
    val focusFire = level >= 6
    val retreat = level >= 7
    val loot = level >= 3
    val lootRadius = 3f + level * 0.7f
    val kite = level >= 8
    val diversion = level >= 9
    val thirdParty = level >= 8
    val smartPowers = level >= 5
    val useRepair = level >= 4
    val useBarricade = level >= 6
    val keepGuard = level >= 5
    /** Taille minimale du groupe d'attaque (niveaux bas : attaques au compte-gouttes). */
    val groupSize = when {
        level <= 2 -> 1
        level <= 4 -> 2
        level <= 6 -> 3
        else -> 3
    }
    val dynamicAttack = level >= 7
    /** Probabilité de remarquer qu'un fort ennemi est ouvert. */
    val noticeOpenFort = 0.35f + 0.65f * t
}

/** Joueur artificiel : n'utilise que [TeamView]. */
class AiController(val team: Int, val level: Int, seed: Long) {
    val p = AiParams(level)
    private val rng = Random(seed)
    private var thinkTimer = 1f + rng.nextFloat() * 1.5f
    private var offerSeenAt = -1f
    private var offerSeenId = -1
    private var bagSeenAt = -1f
    private var targetTeam = -1
    private var lastAttackLaunch = 0f
    private var attacking = false
    private var diversionId = 0
    private var guardId = 0

    /** Dernière tâche donnée à chaque créature (pour ne pas répéter les ordres). */
    private val tasks = HashMap<Int, String>()

    // Caches de champs de distance (recalculés quand le terrain change)
    private var fieldVersion = -1
    private val openField = HashMap<Int, FloatArray>()
    private val siegeField = HashMap<Int, FloatArray>()

    fun update(v: TeamView, dt: Float) {
        if (v.eliminated) return
        thinkTimer -= dt
        if (thinkTimer > 0f) return
        thinkTimer = p.thinkInterval * (0.8f + rng.nextFloat() * 0.4f)
        if (fieldVersion != v.map.version) { fieldVersion = v.map.version; openField.clear(); siegeField.clear() }
        handleOffer(v)
        handleBag(v)
        plan(v)
    }

    // ================================================================= Choix d'objets

    private fun handleOffer(v: TeamView) {
        val o = v.pendingOffer ?: return
        if (o.id != offerSeenId) { offerSeenId = o.id; offerSeenAt = v.time }
        if (v.time - offerSeenAt < p.offerDelay) return
        val pick = if (!p.smartChoices || rng.nextFloat() < p.blunder) rng.nextInt(2)
        else {
            val a = itemValue(v, o.options[0]) + if (p.compositionAware) 0f else rng.nextFloat() * 1.2f
            val b = itemValue(v, o.options[1]) + if (p.compositionAware) 0f else rng.nextFloat() * 1.2f
            if (a >= b) 0 else 1
        }
        v.chooseOffer(o.id, pick)
    }

    private fun baseValue(t: ItemType?): Float = when (t) {
        null -> 0f
        ItemType.SWORD -> 3.2f
        ItemType.MACE -> 3.2f
        ItemType.SPEAR -> 3.0f
        ItemType.HAMMER -> 2.6f
        ItemType.CROSSBOW -> 3.3f
        ItemType.BOMB -> 2.4f
        ItemType.SHIELD -> 2.6f
        ItemType.ARMOR -> 3.0f
        ItemType.BUBBLE -> 2.2f
        ItemType.REPAIR_KIT -> 1.4f
        ItemType.BARRICADE -> 1.3f
        ItemType.HEAL -> 2.6f
        ItemType.SPEED -> 2.0f
        ItemType.FORCE -> 2.3f
        ItemType.SHOCKWAVE -> 2.1f
        ItemType.FREEZE -> 2.3f
        ItemType.INVISIBILITY -> 2.0f
    }

    private fun teamItems(v: TeamView): List<ItemType> {
        val out = ArrayList<ItemType>()
        for (c in v.mine()) { c.weapon?.let { out.add(it.type) }; c.defense?.let { out.add(it.type) }; c.power?.let { out.add(it.type) } }
        for (i in v.bag) out.add(i.type)
        return out
    }

    private fun itemValue(v: TeamView, t: ItemType): Float {
        var value = baseValue(t)
        if (!p.compositionAware) return value
        val have = teamItems(v)
        val siege = have.count { it == ItemType.HAMMER || it == ItemType.BOMB }
        when (t) {
            ItemType.HAMMER, ItemType.BOMB -> if (siege == 0) value += 2.2f else if (siege >= 2) value -= 1.2f
            ItemType.CROSSBOW -> if (have.count { it == ItemType.CROSSBOW } == 0) value += 0.6f
            ItemType.REPAIR_KIT -> if (ownDamage(v) > 0.2f) value += 2.5f
            ItemType.BARRICADE -> if (ownFortOpen(v)) value += 1.5f
            ItemType.HEAL -> value += 0.15f * v.mine().count { it.hp < 70f }
            else -> Unit
        }
        val weapons = v.mine().count { it.weapon != null }
        if (t.category == Category.WEAPON && weapons < v.mine().size) value += 0.3f
        // Diversification : un objet déjà en double vaut un peu moins.
        value -= 0.35f * have.count { it == t }
        return value
    }

    private fun handleBag(v: TeamView) {
        if (v.bag.isEmpty()) { bagSeenAt = -1f; return }
        if (bagSeenAt < 0f) bagSeenAt = v.time
        if (v.time - bagSeenAt < p.bagDelay) return
        val mine = v.mine()
        if (mine.isEmpty()) return
        // Niveaux bas : un objet à la fois ; niveaux hauts : tout le sac.
        val count = if (level >= 6) v.bag.size else 1
        repeat(count) {
            if (v.bag.isEmpty()) return
            var bestScore = 0.05f
            var bestIdx = -1
            var bestC: Creature? = null
            for ((i, item) in v.bag.withIndex()) for (c in v.mine()) {
                val s = assignScore(v, c, item)
                if (s > bestScore) { bestScore = s; bestIdx = i; bestC = c }
            }
            if (bestIdx < 0 || bestC == null) return
            if (rng.nextFloat() < p.blunder) {
                val c = mine[rng.nextInt(mine.size)]
                v.assignFromBag(rng.nextInt(v.bag.size), c.id)
            } else {
                v.assignFromBag(bestIdx, bestC.id)
            }
        }
        bagSeenAt = if (v.bag.isEmpty()) -1f else v.time
    }

    private fun assignScore(v: TeamView, c: Creature, item: Item): Float {
        val cur = c.itemIn(item.category)
        var s = baseValue(item.type) - baseValue(cur?.type) * 1.1f
        if (cur != null && cur.type.usesCharges) s += (1f - cur.charges.toFloat() / cur.type.maxCharges) * baseValue(cur.type) * 0.7f
        if (!p.smartChoices) return s
        val fort = v.map.fortOf(team) ?: return s
        val distFort = Pos(c.x, c.y).dist(Pos(fort.x, fort.y))
        s += c.hp / 200f
        val carried = listOf(c.weapon, c.defense, c.power).count { it != null && it.category != item.category }
        when (item.category) {
            Category.DEFENSE -> s += if (c.weapon != null) 0.5f else -0.2f
            Category.POWER -> s -= 0.4f * carried
            Category.WEAPON -> s -= 0.15f * carried
        }
        when (item.type) {
            ItemType.REPAIR_KIT -> s += if (distFort < 8f) 0.8f else -0.5f
            ItemType.CROSSBOW -> if (c.defense == null) s += 0.2f
            ItemType.SHIELD, ItemType.ARMOR -> if (c.weapon != null && c.weapon?.type?.weapon?.ranged != true) s += 0.4f
            ItemType.HAMMER, ItemType.BOMB -> if (c.defense != null) s += 0.3f
            else -> Unit
        }
        return s
    }

    // ================================================================= Analyse

    /** Distance (pour un ennemi) jusqu'au fort de [fortTeam], en ne traversant aucune brique. */
    private fun openFieldFor(v: TeamView, fortTeam: Int): FloatArray = openField.getOrPut(fortTeam) {
        val fort = v.map.fortOf(fortTeam)!!
        val other = (fortTeam + 1) % v.teamCount
        v.pathfinder.distanceField(fort.tiles(), { x, y -> v.passable(other, x, y) && v.map.fortOwner[v.map.idx(x, y)] != other })
    }

    /** Distance vers le fort de [fortTeam] en traversant les briques à un coût proportionnel à leurs PV. */
    private fun siegeFieldFor(v: TeamView, fortTeam: Int): FloatArray = siegeField.getOrPut(fortTeam) {
        val fort = v.map.fortOf(fortTeam)!!
        val map = v.map
        v.pathfinder.distanceField(
            fort.tiles(),
            { x, y -> v.passable(team, x, y) || map.fortOwner[map.idx(x, y)] == fortTeam },
            { x, y ->
                val i = map.idx(x, y)
                when {
                    map.brickHp[i] > 0f && map.brickOwner[i] == fortTeam -> 2f + map.brickHp[i] / 22f
                    v.barricadeAt(x, y) != null -> 2f + v.barricadeAt(x, y)!!.hp / 25f
                    else -> Float.POSITIVE_INFINITY
                }
            },
        )
    }

    private fun ownFortOpen(v: TeamView): Boolean {
        val f = openFieldFor(v, team)
        val fort = v.map.fortOf(team)!!
        return f[v.map.idx(fort.spawnX, fort.spawnY)].isFinite()
    }

    private fun ownDamage(v: TeamView): Float {
        val bricks = v.map.bricksOf(team)
        if (bricks.isEmpty()) return 0f
        var missing = 0f
        for (b in bricks) missing += 1f - v.map.brickHp[v.map.idx(b.x, b.y)] / Balance.BRICK_HP
        return missing / bricks.size
    }

    private fun strength(hp: Float, w: ItemType?, d: ItemType?): Float =
        hp / 100f * (1f + baseValue(w) * 0.22f + baseValue(d) * 0.15f)

    private fun strength(c: Creature) = strength(c.hp, c.weapon?.type, c.defense?.type)
    private fun strength(e: EnemyInfo) = strength(e.hp, e.weapon, e.defense)

    // ================================================================= Plan

    private class Want(val c: Creature, val prio: Int, val sig: String, val cmd: () -> Command?)

    private fun plan(v: TeamView) {
        val mine = v.mine()
        if (mine.isEmpty()) return
        val enemies = v.enemies()
        val map = v.map
        val myFort = map.fortOf(team)!!
        val myOpen = openFieldFor(v, team)
        val wants = HashMap<Int, Want>()
        fun want(c: Creature, prio: Int, sig: String, cmd: () -> Command?) {
            val cur = wants[c.id]
            if (cur == null || cur.prio < prio) wants[c.id] = Want(c, prio, sig, cmd)
        }
        val free = mine.toMutableSet()

        // --- 1. Victoire : un fort ennemi est accessible ?
        for (t in 0 until v.teamCount) {
            if (t == team || v.isEliminated(t)) continue
            val f = openFieldFor(v, t)
            val fort = map.fortOf(t)!!
            val runners = mine.filter { f[map.idx(it.x, it.y)].isFinite() }
                .sortedBy { f[map.idx(it.x, it.y)] }
            if (runners.isEmpty() || rng.nextFloat() > p.noticeOpenFort) continue
            for (r in runners.take(if (level >= 6) 2 else 1)) {
                val dist = f[map.idx(r.x, r.y)]
                if (dist > 30f && level < 8) continue
                val tile = fort.tiles().minByOrNull { it.dist(r.pos()) }!!
                want(r, 100, "win$t") { Command.MoveTo(r.id, tile.x, tile.y) }
                free.remove(r)
                if (r.power?.type == ItemType.SPEED && dist > 4f) v.issue(Command.UsePower(r.id))
                if (r.power?.type == ItemType.INVISIBILITY && enemies.any { it.pos().dist(r.pos()) < 5f } && level >= 7) v.issue(Command.UsePower(r.id))
            }
        }

        // --- 2. Défense du fort
        val threats = enemies.filter { e ->
            val d = myOpen[map.idx(e.x, e.y)]
            val near = Pos(e.x, e.y).dist(Pos(myFort.x, myFort.y)) <= p.threatRadius
            (d.isFinite() && d < p.threatRadius + 6f) || near
        }.sortedBy { Pos(it.x, it.y).dist(Pos(myFort.x, myFort.y)) }
        if (threats.isNotEmpty() && rng.nextFloat() >= p.blunder) {
            for (e in threats) {
                val open = myOpen[map.idx(e.x, e.y)].isFinite()
                val need = when {
                    open -> 3
                    level >= 7 -> 2
                    else -> 1
                }
                val defenders = free.sortedBy { it.pos().dist(e.pos()) }.take(need)
                for (d in defenders) {
                    if (d.pos().dist(e.pos()) > 18f && !open) continue
                    val fz = d.power
                    if (fz?.type == ItemType.FREEZE && p.smartPowers && e.pos().dist(Pos(myFort.x, myFort.y)) < 5f &&
                        d.pos().dist(e.pos()) <= Balance.FREEZE_RANGE + 2
                    ) {
                        want(d, 85, "frz${e.id}") { Command.UsePower(d.id, e.id) }
                    } else {
                        want(d, 80, "def${e.id}") { Command.AttackCreature(d.id, e.id) }
                    }
                    free.remove(d)
                }
            }
        }

        // --- 3. Retraite des blessés
        if (p.retreat) for (c in free.toList()) {
            if (c.hp >= 30f || c.power?.type == ItemType.HEAL) continue
            val close = enemies.count { it.pos().cheb(c.pos()) <= 2 }
            if (close == 0) continue
            val potion = map.bonuses.firstOrNull { it.available && it.type == BonusType.POTION && Pos(it.x, it.y).dist(c.pos()) < 8f }
            val dest = if (potion != null) Pos(potion.x, potion.y) else Pos(myFort.spawnX, myFort.spawnY)
            want(c, 70, "ret${dest.x},${dest.y}") { Command.MoveTo(c.id, dest.x, dest.y) }
            free.remove(c)
        }

        // --- 4. Réparation / barricades
        if (p.useRepair) for (c in free.toList()) {
            if (c.defense?.type != ItemType.REPAIR_KIT) continue
            val tgt = v.repairTarget(myFort.x, myFort.y) ?: continue
            if (enemies.any { it.pos().dist(tgt) < 4f }) continue
            want(c, 60, "rep") { Command.Repair(c.id, tgt.x, tgt.y) }
            free.remove(c)
        }
        if (p.useBarricade) for (c in free.toList()) {
            if (c.defense?.type != ItemType.BARRICADE) continue
            // Boucher la brèche la plus proche de notre fort.
            val breach = map.bricksOf(team).filter { map.isRubble(it.x, it.y) && v.canPlaceBarricade(it.x, it.y) }
                .minByOrNull { it.dist(c.pos()) } ?: continue
            want(c, 58, "bar${breach.x},${breach.y}") { Command.Barricade(c.id, breach.x, breach.y) }
            free.remove(c)
        }

        // --- 5. Butin au sol
        if (p.loot) {
            for (gi in v.groundItems().sortedByDescending { baseValue(it.item.type) }) {
                if (enemies.any { it.pos().dist(Pos(gi.x, gi.y)) < (if (level >= 7) 5f else 3.5f) }) continue
                val best = free.filter { c ->
                    val cur = c.itemIn(gi.item.category)
                    (cur == null || baseValue(gi.item.type) > baseValue(cur.type) + 0.4f) &&
                        c.pos().dist(Pos(gi.x, gi.y)) <= p.lootRadius
                }.minByOrNull { it.pos().dist(Pos(gi.x, gi.y)) } ?: continue
                want(best, 50, "loot${gi.id}") { Command.MoveTo(best.id, gi.x, gi.y) }
                free.remove(best)
            }
        }

        // --- 6. Garde du fort
        if (p.keepGuard && mine.size >= 4) {
            val g = mine.firstOrNull { it.id == guardId && it in free }
                ?: free.filter { it.weapon?.type?.weapon?.ranged == true || it.weapon == null }.minByOrNull { it.pos().dist(Pos(myFort.spawnX, myFort.spawnY)) }
                ?: free.minByOrNull { it.pos().dist(Pos(myFort.spawnX, myFort.spawnY)) }
            if (g != null) {
                guardId = g.id
                free.remove(g)
                if (g.pos().dist(Pos(myFort.spawnX, myFort.spawnY)) > 3f) {
                    want(g, 20, "guard") { Command.MoveTo(g.id, myFort.spawnX, myFort.spawnY) }
                }
            }
        } else guardId = 0

        // --- 7. Attaque
        planAttack(v, free, enemies) { c, prio, sig, cmd -> want(c, prio, sig, cmd) }

        // --- 8. Pouvoirs
        for (c in mine) usePowers(v, c, enemies)

        // --- Émission des ordres (attention limitée)
        val ordered = wants.values.sortedByDescending { it.prio }
        var issued = 0
        for (w in ordered) {
            if (issued >= p.ordersPerThink) break
            val c = w.c
            if (!c.alive) continue
            val prev = tasks[c.id]
            if (prev == w.sig && !(c.order is Order.Idle && !w.sig.startsWith("guard"))) continue
            if (prev == w.sig && w.sig.startsWith("guard")) continue
            val cmd = w.cmd() ?: continue
            var finalCmd = cmd
            if (rng.nextFloat() < p.blunder * 0.5f && w.prio < 100) {
                // Erreur de jugement : ordre approximatif
                finalCmd = Command.MoveTo(c.id, (c.x + rng.nextInt(-4, 5)).coerceIn(0, map.width - 1), (c.y + rng.nextInt(-4, 5)).coerceIn(0, map.height - 1))
            }
            if (v.issue(finalCmd)) { tasks[c.id] = w.sig; issued++ } else tasks.remove(c.id)
        }
        tasks.keys.retainAll(mine.map { it.id }.toSet())
    }

    private fun chooseTarget(v: TeamView, enemies: List<EnemyInfo>): Int {
        val map = v.map
        val myFort = map.fortOf(team)!!
        var best = -1
        var bestScore = -1e9f
        var curScore = -1e9f
        for (t in 0 until v.teamCount) {
            if (t == team || v.isEliminated(t)) continue
            val fort = map.fortOf(t)!!
            var s = -Pos(myFort.x, myFort.y).dist(Pos(fort.x, fort.y)) / 8f
            // Fort abîmé = cible intéressante
            val bricks = map.bricksOf(t)
            val dmg = bricks.sumOf { (1f - map.brickHp[map.idx(it.x, it.y)] / Balance.BRICK_HP).toDouble() }.toFloat()
            s += dmg * 0.6f
            // Défenseurs visibles autour du fort
            val defenders = enemies.filter { it.team == t && it.pos().dist(Pos(fort.x, fort.y)) < 8f }
            s -= defenders.sumOf { strength(it).toDouble() }.toFloat() * 0.9f
            if (p.thirdParty) {
                // Équipe occupée ailleurs (ses créatures loin de chez elle ou en combat contre d'autres)
                val away = enemies.count { it.team == t && it.pos().dist(Pos(fort.x, fort.y)) > 12f }
                s += away * 0.5f
                val fighting = enemies.count { e -> e.team == t && enemies.any { o -> o.team != t && o.pos().cheb(e.pos()) <= 2 } }
                s += fighting * 0.4f
            }
            if (s > bestScore) { bestScore = s; best = t }
            if (t == targetTeam) curScore = s
        }
        // Hystérésis : on ne change pas de cible pour un rien.
        if (targetTeam >= 0 && !v.isEliminated(targetTeam) && curScore > bestScore - 1.5f) return targetTeam
        return best
    }

    private fun planAttack(
        v: TeamView,
        free: MutableSet<Creature>,
        enemies: List<EnemyInfo>,
        want: (Creature, Int, String, () -> Command?) -> Unit,
    ) {
        if (free.isEmpty()) return
        val map = v.map
        val myFort = map.fortOf(team)!!
        targetTeam = if (v.teamCount == 2) (1 - team) else chooseTarget(v, enemies)
        if (targetTeam < 0) return
        val tFort = map.fortOf(targetTeam)!!
        val siege = siegeFieldFor(v, targetTeam)

        // Décision de lancer l'assaut
        val group = free.toList()
        val myPower = group.sumOf { strength(it).toDouble() }.toFloat()
        val defPower = enemies.filter { it.team == targetTeam && it.pos().dist(Pos(tFort.x, tFort.y)) < 10f }
            .sumOf { strength(it).toDouble() }.toFloat()
        val allEnemyPower = enemies.filter { it.team == targetTeam }.sumOf { strength(it).toDouble() }.toFloat()
        val armed = group.count { it.weapon != null }
        if (!attacking) {
            val ready = when {
                level <= 2 -> group.size >= p.groupSize
                level <= 4 -> group.size >= p.groupSize && (armed >= 1 || group.size >= 5)
                !p.dynamicAttack -> group.size >= p.groupSize && armed >= 1
                else -> (group.size >= p.groupSize && armed >= 2 && myPower >= max(defPower * 1.2f, allEnemyPower * 0.8f) + 0.3f) ||
                    group.size >= 6 || (v.time - lastAttackLaunch > 150f && armed >= 1 && group.size >= 3)
            }
            if (ready) { attacking = true; lastAttackLaunch = v.time }
        } else if (group.isEmpty() || (level >= 5 && group.size <= 1 && armed == 0) ||
            (p.dynamicAttack && myPower < max(defPower, allEnemyPower * 0.6f) * 0.6f)
        ) {
            attacking = false
        }

        // Rapport de force local autour du groupe.
        val c0 = centroid(group)
        val localMine = group.filter { it.pos().dist(c0) <= 7f }.sumOf { strength(it).toDouble() }.toFloat()
        val localEnemy = enemies.filter { it.pos().dist(c0) <= 7f }.sumOf { strength(it).toDouble() }.toFloat()
        val losing = level >= 5 && localEnemy > 0.5f && localMine < localEnemy * (if (level >= 8) 0.75f else 0.6f)
        // Repli : le combat tourne mal, on préserve les créatures (niveaux 5+).
        if (attacking && losing) attacking = false
        val nearFocus = if (p.focusFire) enemies.filter { it.pos().dist(c0) <= 6f }
            .minByOrNull { it.hp / max(0.3f, 1f - it.pos().dist(c0) / 10f) } else null

        if (!attacking) {
            // Rassemblement près de notre fort, côté cible — mais on ne tourne pas le dos à un ennemi proche.
            val rally = rallyPoint(v, myFort, siege)
            for (c in group) {
                val close = enemies.filter { it.pos().dist(c.pos()) <= 4.5f }
                if (close.isNotEmpty() && !losing) {
                    val tgt = nearFocus?.takeIf { it.pos().dist(c.pos()) <= 6f } ?: close.minByOrNull { it.pos().dist(c.pos()) + it.hp / 60f }!!
                    want(c, 40, "atk${tgt.id}") { Command.AttackCreature(c.id, tgt.id) }
                    continue
                }
                if (c.pos().dist(rally) <= 2.5f) continue
                want(c, if (close.isNotEmpty()) 42 else 10, "rally${rally.x},${rally.y}") { Command.MoveTo(c.id, rally.x, rally.y) }
            }
            return
        }

        // Point de brèche : première brique sur le chemin le moins coûteux.
        val center = centroid(group)
        val lead = group.filter { siege[map.idx(it.x, it.y)].isFinite() }.minByOrNull { siege[map.idx(it.x, it.y)] }
        val breach = lead?.let { firstObstacle(v, siege, it.pos(), targetTeam) }
        val alt = if (p.diversion && group.size >= 4) secondBreach(v, targetTeam, breach) else null

        // Divertissement : une créature attaque ailleurs pour attirer les défenseurs.
        var diverter: Creature? = null
        if (alt != null) {
            diverter = group.firstOrNull { it.id == diversionId }
                ?: group.filter { it.weapon?.type != ItemType.HAMMER && it.weapon?.type != ItemType.BOMB }
                    .maxByOrNull { it.hp }
            diverter?.let { d ->
                diversionId = d.id
                want(d, 30, "div${alt.x},${alt.y}") {
                    if (d.weapon?.type == ItemType.BOMB) Command.Bomb(d.id, alt.x, alt.y) else Command.AttackTile(d.id, alt.x, alt.y)
                }
            }
        }

        val focus = nearFocus

        for (c in group) {
            if (c == diverter) continue
            val w = c.weapon?.type
            val isSiege = w == ItemType.HAMMER || w == ItemType.BOMB || w == ItemType.MACE
            val close = enemies.filter { it.pos().dist(c.pos()) <= 4.5f }
            // Kite à l'arbalète
            if (p.kite && w == ItemType.CROSSBOW) {
                val adj = close.firstOrNull { it.pos().cheb(c.pos()) <= 1 && it.weapon?.weapon?.ranged != true }
                if (adj != null) {
                    val dx = (c.x - adj.x).coerceIn(-1, 1) * 2; val dy = (c.y - adj.y).coerceIn(-1, 1) * 2
                    val nx = (c.x + dx).coerceIn(0, map.width - 1); val ny = (c.y + dy).coerceIn(0, map.height - 1)
                    if (v.passable(team, nx, ny)) {
                        want(c, 45, "kite$nx,$ny") { Command.MoveTo(c.id, nx, ny) }
                        continue
                    }
                }
            }
            if (close.isNotEmpty() && !(isSiege && breach != null && c.pos().cheb(breach) <= 1 && close.none { it.pos().cheb(c.pos()) <= 1 })) {
                val tgt = focus?.takeIf { it.pos().dist(c.pos()) <= 6f } ?: close.minByOrNull { it.pos().dist(c.pos()) + it.hp / 60f }!!
                want(c, 40, "atk${tgt.id}") { Command.AttackCreature(c.id, tgt.id) }
                continue
            }
            if (breach != null) {
                // Mains nues : on n'insiste pas sur les briques si d'autres s'en chargent.
                if (w == null && group.any { it.weapon != null }) {
                    val stand = standNear(v, breach, c)
                    want(c, 25, "esc${stand.x},${stand.y}") { Command.MoveTo(c.id, stand.x, stand.y) }
                } else {
                    val sig = "brk${breach.x},${breach.y}"
                    want(c, 25, sig) {
                        if (w == ItemType.BOMB) Command.Bomb(c.id, breach.x, breach.y) else Command.AttackTile(c.id, breach.x, breach.y)
                    }
                }
            }
        }
    }

    private fun rallyPoint(v: TeamView, myFort: FortInfo, siege: FloatArray): Pos {
        // Descend le champ de siège quelques cases depuis notre point d'apparition.
        var cur = Pos(myFort.spawnX, myFort.spawnY)
        repeat(3) {
            val n = descend(v, siege, cur, allowObstacle = false) ?: return cur
            cur = n
        }
        return cur
    }

    private fun centroid(cs: List<Creature>): Pos {
        val x = cs.sumOf { it.x } / cs.size; val y = cs.sumOf { it.y } / cs.size
        return Pos(x, y)
    }

    private fun descend(v: TeamView, field: FloatArray, from: Pos, allowObstacle: Boolean): Pos? {
        val map = v.map
        var best: Pos? = null
        var bestV = field[map.idx(from.x, from.y)]
        for (d in 0 until 8) {
            val nx = from.x + Pathfinder.DX[d]; val ny = from.y + Pathfinder.DY[d]
            if (!map.inside(nx, ny)) continue
            if (!allowObstacle && !v.passable(team, nx, ny)) continue
            val fv = field[map.idx(nx, ny)]
            if (fv < bestV) { bestV = fv; best = Pos(nx, ny) }
        }
        return best
    }

    /** Suit le champ de siège depuis [from] et retourne la première brique/barricade rencontrée. */
    private fun firstObstacle(v: TeamView, field: FloatArray, from: Pos, fortTeam: Int): Pos? {
        val map = v.map
        // Si le centre du groupe est sur une case hors champ (ex. rivière), on part d'une créature.
        var cur = from
        if (field[map.idx(cur.x, cur.y)].isInfinite()) return null
        repeat(200) {
            val n = descend(v, field, cur, allowObstacle = true) ?: return null
            if (v.isEnemyStructure(n.x, n.y)) return n
            if (map.fortOwner[map.idx(n.x, n.y)] == fortTeam) return null
            cur = n
        }
        return null
    }

    /** Une brique extérieure de l'autre côté du fort, pour une diversion. */
    private fun secondBreach(v: TeamView, fortTeam: Int, main: Pos?): Pos? {
        if (main == null) return null
        val map = v.map
        val fort = map.fortOf(fortTeam)!!
        return map.bricksOf(fortTeam)
            .filter { map.hasBrick(it.x, it.y) && max(abs(it.x - fort.centerX), abs(it.y - fort.centerY)) >= 2f }
            .filter { it.dist(main) >= 4f }
            .minByOrNull { it.dist(Pos(map.width / 2, map.height / 2)) }
    }

    private fun standNear(v: TeamView, p0: Pos, c: Creature): Pos {
        val map = v.map
        var best = c.pos()
        var bestD = Float.MAX_VALUE
        for (dy in -3..3) for (dx in -3..3) {
            val x = p0.x + dx; val y = p0.y + dy
            if (!v.passable(team, x, y)) continue
            val d = abs(Pos(x, y).dist(p0) - 2.5f) + Pos(x, y).dist(c.pos()) * 0.1f
            if (d < bestD) { bestD = d; best = Pos(x, y) }
        }
        return if (map.inside(best.x, best.y)) best else c.pos()
    }

    // ================================================================= Pouvoirs

    private fun usePowers(v: TeamView, c: Creature, enemies: List<EnemyInfo>) {
        val pw = c.power ?: return
        if (c.isDisabled) return
        if (!p.smartPowers) {
            // Niveaux faibles : usage approximatif.
            val chance = if (level <= 2) 0.03f else 0.06f
            val urgent = pw.type == ItemType.HEAL && c.hp < 30f && level >= 3
            if (urgent || rng.nextFloat() < chance) {
                val tgt = enemies.minByOrNull { it.pos().dist(c.pos()) }
                v.issue(Command.UsePower(c.id, tgt?.id ?: 0))
            }
            return
        }
        val near2 = enemies.count { it.pos().dist(c.pos()) <= 2.2f }
        val near5 = enemies.filter { it.pos().dist(c.pos()) <= 5f }
        val inFight = near2 > 0
        val o = c.order
        val use = when (pw.type) {
            ItemType.HEAL -> c.hp < 45f
            ItemType.SHOCKWAVE -> near2 >= 2 || (near2 >= 1 && c.hp < 40f)
            ItemType.FORCE -> (inFight && c.weapon != null) ||
                (o is Order.AttackStructure && c.weaponStats.structureMult >= 0.6f && c.pos().cheb(Pos(o.x, o.y)) <= 1)
            ItemType.SPEED -> (o is Order.Move && c.pos().dist(Pos(o.x, o.y)) > 8f && level >= 7) ||
                (c.hp < 35f && inFight)
            ItemType.INVISIBILITY -> (c.hp < 35f && inFight) ||
                (level >= 9 && o is Order.AttackStructure && c.pos().dist(Pos(o.x, o.y)) > 6f && near5.isEmpty())
            ItemType.FREEZE -> {
                val tgt = near5.maxByOrNull { strength(it) }
                if (tgt != null && (inFight || level >= 8) && !tgt.frozen) {
                    v.issue(Command.UsePower(c.id, tgt.id)); tasks.remove(c.id)
                }
                false
            }
            else -> false
        }
        if (use) v.issue(Command.UsePower(c.id))
    }
}
