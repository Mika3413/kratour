package com.kratour.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * État complet d'une partie et règles du jeu. Simulation à pas fixe ([Balance.TICK]).
 * Joueur et IA n'agissent qu'à travers [issue], [chooseOffer] et [assignFromBag].
 */
class World(val mapId: MapId, teamNames: List<String>, humanTeam: Int, seed: Long) {
    val map: GameMap = Maps.build(mapId)
    val teams: List<Team> = teamNames.mapIndexed { i, n -> Team(i, n, i == humanTeam) }
    val creatures = ArrayList<Creature>()
    val groundItems = ArrayList<GroundItem>()
    val barricades = ArrayList<Barricade>()
    val projectiles = ArrayList<Projectile>()
    val events = ArrayList<GameEvent>()
    val pathfinder = Pathfinder(map)

    private val rng = Random(seed)
    private val occ = IntArray(map.width * map.height)
    private val barricadeAt = arrayOfNulls<Barricade>(map.width * map.height)
    private var nextId = 1
    private var nextItemId = 1
    private var nextOfferId = 1

    var time = 0f
        private set
    var spawnTimer = Balance.SPAWN_INTERVAL
        private set
    var offerTimer = Balance.FIRST_OFFER_AT
        private set
    var nextOfferCategory = Category.WEAPON
        private set
    var firstBloodTeam = -1
        private set
    var winner = -1
        private set
    val over: Boolean get() = winner >= 0

    init {
        require(teamNames.size == mapId.teams)
        for (t in teams) repeat(Balance.START_CREATURES) { spawnCreature(t.id, initial = true) }
    }

    // =================================================================== Requêtes

    fun creature(id: Int): Creature? = if (id <= 0) null else creatures.firstOrNull { it.id == id && it.alive }
    fun aliveOf(team: Int) = creatures.filter { it.alive && it.team == team }
    fun countAlive(team: Int) = creatures.count { it.alive && it.team == team }
    fun barricadeAt(x: Int, y: Int): Barricade? = if (map.inside(x, y)) barricadeAt[map.idx(x, y)] else null
    fun occupant(x: Int, y: Int): Int = if (map.inside(x, y)) occ[map.idx(x, y)] else 0
    fun itemsAt(x: Int, y: Int) = groundItems.filter { it.x == x && it.y == y }
    fun activeTeams() = teams.filter { !it.eliminated }

    /** Une créature est visible par [team] si elle lui appartient ou n'est pas invisible. */
    fun visibleTo(c: Creature, team: Int) = c.alive && (c.team == team || !c.isInvisible)

    fun passable(team: Int, x: Int, y: Int): Boolean =
        map.walkableStatic(x, y, team) && barricadeAt[map.idx(x, y)] == null

    fun isEnemyStructure(team: Int, x: Int, y: Int): Boolean {
        if (!map.inside(x, y)) return false
        val i = map.idx(x, y)
        if (map.brickHp[i] > 0f && map.brickOwner[i] != team) return true
        val b = barricadeAt[i]
        return b != null && b.team != team
    }

    // =================================================================== Commandes

    fun issue(team: Int, cmd: Command): Boolean {
        if (over || teams[team].eliminated) return false
        val c = creature(cmd.creatureId) ?: return false
        if (c.team != team) return false
        when (cmd) {
            is Command.MoveTo -> {
                if (!map.inside(cmd.x, cmd.y)) return false
                setOrder(c, Order.Move(cmd.x, cmd.y))
            }
            is Command.AttackCreature -> {
                val t = creature(cmd.targetId) ?: return false
                if (t.team == team || !visibleTo(t, team)) return false
                setOrder(c, Order.Attack(t.id))
            }
            is Command.AttackTile -> {
                if (!isEnemyStructure(team, cmd.x, cmd.y)) return false
                if (c.weapon?.type == ItemType.BOMB) setOrder(c, Order.ThrowBomb(cmd.x, cmd.y))
                else setOrder(c, Order.AttackStructure(cmd.x, cmd.y))
            }
            is Command.Bomb -> {
                if (c.weapon?.type != ItemType.BOMB || !map.inside(cmd.x, cmd.y)) return false
                setOrder(c, Order.ThrowBomb(cmd.x, cmd.y))
            }
            is Command.UsePower -> return usePower(c, cmd.targetId)
            is Command.Repair -> {
                if (c.defense?.type != ItemType.REPAIR_KIT) return false
                setOrder(c, Order.Repair(cmd.x, cmd.y))
            }
            is Command.Barricade -> {
                if (c.defense?.type != ItemType.BARRICADE) return false
                if (!canPlaceBarricade(cmd.x, cmd.y)) return false
                setOrder(c, Order.PlaceBarricade(cmd.x, cmd.y))
            }
            is Command.Stop -> setOrder(c, Order.Idle)
        }
        return true
    }

    private fun setOrder(c: Creature, o: Order) {
        c.order = o
        c.autoTargetId = 0
        c.path = emptyList(); c.pathIdx = 0; c.pathGoalKey = Long.MIN_VALUE
        c.blockedTimer = 0f; c.noPathTimer = 0f; c.actionTimer = 0f
        if (o is Order.Idle) { c.anchorX = c.tx; c.anchorY = c.ty }
    }

    fun chooseOffer(team: Int, offerId: Int, option: Int): Boolean {
        val t = teams[team]
        val o = t.pendingOffer ?: return false
        if (o.id != offerId || option !in o.options.indices || t.eliminated) return false
        t.bag.add(Item(o.options[option]))
        t.pendingOffer = null
        return true
    }

    /** Donne un objet du sac commun à une créature. L'objet qu'elle portait dans cet emplacement retourne au sac. */
    fun assignFromBag(team: Int, bagIndex: Int, creatureId: Int): Boolean {
        val t = teams[team]
        if (t.eliminated || bagIndex !in t.bag.indices) return false
        val c = creature(creatureId) ?: return false
        if (c.team != team) return false
        val item = t.bag.removeAt(bagIndex)
        val old = c.itemIn(item.category)
        c.setItem(item.category, item)
        if (old != null) t.bag.add(old)
        events.add(GameEvent(EventType.PICKUP, c.px, c.py, team))
        return true
    }

    fun canPlaceBarricade(x: Int, y: Int): Boolean {
        if (!map.inside(x, y)) return false
        val i = map.idx(x, y)
        if (!map.terrain[i].walkable || map.terrain[i] == Terrain.FORT) return false
        if (map.brickHp[i] > 0f || barricadeAt[i] != null) return false
        if (groundItems.any { it.x == x && it.y == y }) return false
        return true
    }

    // =================================================================== Boucle

    fun step() {
        if (over) return
        val dt = Balance.TICK
        time += dt

        // Renforts
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnTimer += Balance.SPAWN_INTERVAL
            for (t in teams) if (!t.eliminated && countAlive(t.id) < Balance.MAX_ALIVE) spawnCreature(t.id, false)
        }

        // Choix d'équipement (mêmes options pour tout le monde)
        offerTimer -= dt
        if (offerTimer <= 0f) {
            offerTimer += Balance.OFFER_INTERVAL
            newOffer()
        }

        for (c in creatures) if (c.alive) updateCreature(c, dt)
        updateProjectiles(dt)
        updateBarricades(dt)
        for (b in map.bonuses) if (!b.available) {
            b.respawnTimer -= dt
            if (b.respawnTimer <= 0f) b.available = true
        }
        if (creatures.size > 64) creatures.removeAll { !it.alive }
    }

    private fun newOffer() {
        val pool = ItemType.ofCategory(nextOfferCategory)
        val a = pool[rng.nextInt(pool.size)]
        var b = pool[rng.nextInt(pool.size)]
        while (b == a) b = pool[rng.nextInt(pool.size)]
        val offer = Offer(nextOfferId++, nextOfferCategory, listOf(a, b))
        nextOfferCategory = nextOfferCategory.next()
        for (t in teams) {
            if (t.eliminated) continue
            // Une proposition non choisie n'est jamais perdue : la première option est prise.
            t.pendingOffer?.let { t.bag.add(Item(it.options[0])) }
            t.pendingOffer = offer
        }
        events.add(GameEvent(EventType.OFFER, 0f, 0f, -1, text = offer.category.label))
    }

    private fun spawnCreature(team: Int, initial: Boolean) {
        val fort = map.fortOf(team) ?: return
        val p = findFreeTileNear(fort.spawnX, fort.spawnY, team) ?: return
        val c = Creature(nextId++, team, p.x, p.y)
        val dx = sign(map.width / 2f - fort.centerX).toInt(); val dy = sign(map.height / 2f - fort.centerY).toInt()
        c.fdx = dx; c.fdy = dy
        creatures.add(c)
        occ[map.idx(p.x, p.y)] = c.id
        teams[team].spawned++
        if (!initial) events.add(GameEvent(EventType.SPAWN, p.x.toFloat(), p.y.toFloat(), team))
    }

    private fun findFreeTileNear(x: Int, y: Int, team: Int): Pos? {
        for (r in 0..6) {
            var best: Pos? = null
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                val nx = x + dx; val ny = y + dy
                if (!passable(team, nx, ny) || occ[map.idx(nx, ny)] != 0) continue
                if (map.fortOwner[map.idx(nx, ny)] >= 0) continue
                if (best == null) best = Pos(nx, ny)
            }
            if (best != null) return best
        }
        return null
    }

    // =================================================================== Créatures

    private fun updateCreature(c: Creature, dt: Float) {
        c.attackCooldown -= dt
        if (c.speedTimer > 0f) c.speedTimer -= dt
        if (c.forceTimer > 0f) c.forceTimer -= dt
        if (c.invisTimer > 0f) c.invisTimer -= dt
        if (c.frozenTimer > 0f) { c.frozenTimer -= dt; return }
        if (c.stunTimer > 0f) { c.stunTimer -= dt; if (!c.isMoving) return }

        if (c.isMoving) {
            advanceStep(c, dt)
            if (c.isMoving || c.stunTimer > 0f) return
        }
        if (!c.alive) return
        think(c, dt)
    }

    private fun advanceStep(c: Creature, dt: Float) {
        c.moveT += dt / c.moveDur
        if (c.moveT >= 1f) {
            occ[map.idx(c.x, c.y)] = 0
            c.x = c.tx; c.y = c.ty; c.moveT = 0f
            occ[map.idx(c.x, c.y)] = c.id
            onArrive(c)
        }
    }

    private fun onArrive(c: Creature) {
        val i = map.idx(c.x, c.y)
        // Entrée dans un fort ennemi = élimination immédiate de cette équipe.
        val fo = map.fortOwner[i]
        if (fo >= 0 && fo != c.team && !teams[fo].eliminated) {
            eliminate(fo, c.team, c)
            if (!c.alive) return
        }
        // Bonus neutres
        for (b in map.bonuses) if (b.available && b.x == c.x && b.y == c.y) tryBonus(c, b)
        // Ramassage : objets à la destination (échange) ou emplacements vides en passage.
        val atDestination = c.order is Order.Move && (c.order as Order.Move).let { it.x == c.x && it.y == c.y }
        pickup(c, swap = atDestination)
    }

    private fun tryBonus(c: Creature, b: BonusSpot) {
        val used = when (b.type) {
            BonusType.POTION -> if (c.hp < Balance.MAX_HP) { c.hp = min(Balance.MAX_HP, c.hp + Balance.POTION_HEAL); true } else false
            BonusType.HASTE -> { c.speedTimer = max(c.speedTimer, Balance.HASTE_DURATION); true }
            BonusType.RECHARGE -> {
                val w = c.weapon
                when (w?.type) {
                    ItemType.CROSSBOW -> if (w.charges < w.type.maxCharges) { w.charges = min(w.type.maxCharges, w.charges + Balance.RECHARGE_BOLTS); true } else false
                    ItemType.BOMB -> if (w.charges < w.type.maxCharges) { w.charges++; true } else false
                    else -> false
                }
            }
        }
        if (used) {
            b.available = false; b.respawnTimer = Balance.BONUS_RESPAWN
            events.add(GameEvent(EventType.BONUS, c.px, c.py, c.team, text = b.type.label))
        }
    }

    private fun pickup(c: Creature, swap: Boolean) {
        // Instantané : un objet lâché lors d'un échange n'est pas repris aussitôt.
        val here = groundItems.filter { it.x == c.x && it.y == c.y }
        if (here.isEmpty()) return
        for (gi in here) {
            val cat = gi.item.category
            val cur = c.itemIn(cat)
            if (cur != null && !swap) continue
            if (cur != null && cur.type == gi.item.type && !gi.item.type.usesCharges) continue
            groundItems.remove(gi)
            c.setItem(cat, gi.item)
            if (cur != null) groundItems.add(GroundItem(nextItemId++, cur, c.x, c.y))
            events.add(GameEvent(EventType.PICKUP, c.px, c.py, c.team, text = gi.item.type.label))
        }
    }

    private fun think(c: Creature, dt: Float) {
        val o = c.order
        // Un déplacement explicite est prioritaire (permet de battre en retraite).
        if (o is Order.Move) {
            if (c.x == o.x && c.y == o.y) { setOrder(c, Order.Idle); return }
            val goalKey = key(o.x, o.y)
            if (!followPath(c, dt, goalKey) { listOf(Pos(o.x, o.y)) }) {
                // Destination inaccessible : on s'approche au plus près.
                val alt = nearestReachable(c, o.x, o.y)
                if (alt == null || (alt.x == c.x && alt.y == c.y)) setOrder(c, Order.Idle)
                else c.order = Order.Move(alt.x, alt.y)
            }
            return
        }

        // Riposte / garde automatique
        val auto = creature(c.autoTargetId)
        if (auto != null && visibleTo(auto, c.team) && withinLeash(c, auto)) {
            if (fight(c, auto, dt)) return
            c.autoTargetId = 0
        } else if (c.autoTargetId != 0) {
            c.autoTargetId = 0
            c.path = emptyList(); c.pathGoalKey = Long.MIN_VALUE
        }

        when (o) {
            is Order.Attack -> {
                val t = creature(o.targetId)
                if (t == null || !visibleTo(t, c.team)) { setOrder(c, Order.Idle); return }
                if (!fight(c, t, dt)) setOrder(c, Order.Idle)
            }
            is Order.AttackStructure -> {
                if (!isEnemyStructure(c.team, o.x, o.y)) { setOrder(c, Order.Idle); return }
                if (!attackStructure(c, o.x, o.y, dt)) setOrder(c, Order.Idle)
            }
            is Order.ThrowBomb -> {
                if (c.weapon?.type != ItemType.BOMB) { setOrder(c, Order.Idle); return }
                if (!throwBomb(c, o.x, o.y, dt)) setOrder(c, Order.Idle)
            }
            is Order.Repair -> doRepair(c, o, dt)
            is Order.PlaceBarricade -> doBarricade(c, o, dt)
            is Order.CastFreeze -> {
                val t = creature(o.targetId)
                if (t == null || !visibleTo(t, c.team) || c.power?.type != ItemType.FREEZE) { setOrder(c, Order.Idle); return }
                if (Pos(c.x, c.y).dist(Pos(t.x, t.y)) <= Balance.FREEZE_RANGE) {
                    face(c, t.x, t.y)
                    t.frozenTimer = Balance.FREEZE_DURATION
                    c.power = null
                    events.add(GameEvent(EventType.FREEZE, t.px, t.py, c.team))
                    setOrder(c, Order.Idle)
                } else {
                    followPath(c, dt, key(t.x, t.y) xor 0x5A5A) { goalsWithin(c, t.x, t.y, Balance.FREEZE_RANGE, euclid = true) }
                }
            }
            is Order.Idle -> guard(c, dt)
            is Order.Move -> Unit
        }
    }

    private fun withinLeash(c: Creature, t: Creature): Boolean {
        val leash = Balance.GUARD_LEASH + c.weaponStats.range
        val o = c.order
        // En ordre d'attaque, la riposte reste proche ; à l'arrêt, on garde autour du point d'ancrage.
        val ax = if (o is Order.Idle) c.anchorX else c.x
        val ay = if (o is Order.Idle) c.anchorY else c.y
        return Pos(ax, ay).dist(Pos(t.x, t.y)) <= leash + 1.5f
    }

    /** Créature au repos : attaque les ennemis visibles qui s'approchent, puis revient à sa position. */
    private fun guard(c: Creature, dt: Float) {
        c.scanTimer -= dt
        if (c.scanTimer <= 0f) {
            c.scanTimer = 0.3f
            val range = max(c.weaponStats.range, 1f) + 1.5f
            val target = bestTargetAround(c, c.anchorX, c.anchorY, range + 1f)
            if (target != null) { c.autoTargetId = target.id; return }
        }
        if (c.x != c.anchorX || c.y != c.anchorY) {
            if (!followPath(c, dt, key(c.anchorX, c.anchorY)) { listOf(Pos(c.anchorX, c.anchorY)) }) {
                c.anchorX = c.x; c.anchorY = c.y
            }
        }
    }

    private fun bestTargetAround(c: Creature, ax: Int, ay: Int, radius: Float): Creature? {
        var best: Creature? = null
        var bestScore = Float.MAX_VALUE
        for (e in creatures) {
            if (!e.alive || e.team == c.team || !visibleTo(e, c.team)) continue
            val d = Pos(ax, ay).dist(Pos(e.x, e.y))
            if (d > radius) continue
            val inRange = inRange(c, e.x, e.y)
            val score = d + e.hp / 50f - if (inRange) 3f else 0f
            if (score < bestScore) { bestScore = score; best = e }
        }
        return best
    }

    private fun inRange(c: Creature, x: Int, y: Int): Boolean {
        val ws = c.weaponStats
        return if (ws.ranged) Pos(c.x, c.y).dist(Pos(x, y)) <= ws.range + 0.01f
        else max(abs(c.x - x), abs(c.y - y)) <= ws.range.toInt()
    }

    private fun fight(c: Creature, t: Creature, dt: Float): Boolean {
        val ws = c.weaponStats
        if (c.weapon?.type == ItemType.BOMB) return throwBomb(c, t.x, t.y, dt, t)
        if (inRange(c, t.x, t.y)) {
            c.path = emptyList(); c.pathGoalKey = Long.MIN_VALUE
            face(c, t.x, t.y)
            if (c.attackCooldown <= 0f) attackCreature(c, t, ws)
            return true
        }
        val gk = key(t.x, t.y) * 31 + ws.range.toInt()
        return followPath(c, dt, gk) { goalsWithin(c, t.x, t.y, ws.range, ws.ranged) }
    }

    private fun damageMult(c: Creature) = if (c.forceTimer > 0f) Balance.FORCE_MULT else 1f

    private fun attackCreature(c: Creature, t: Creature, ws: WeaponStats) {
        c.attackCooldown = ws.cooldown
        c.invisTimer = 0f
        val dmg = ws.damage * damageMult(c)
        if (ws.ranged) {
            val w = c.weapon!!
            w.charges--
            val d = Pos(c.x, c.y).dist(Pos(t.x, t.y))
            projectiles.add(
                Projectile(
                    ProjectileKind.BOLT, c.team, c.id, c.px, c.py, t.px, t.py, t.id, dmg,
                    duration = max(0.1f, d / Balance.PROJECTILE_SPEED),
                )
            )
            events.add(GameEvent(EventType.SHOOT, c.px, c.py, c.team))
            if (w.charges <= 0) breakWeapon(c)
        } else {
            events.add(GameEvent(EventType.SWING, c.px, c.py, c.team))
            hitCreature(t, dmg, c, c.x.toFloat(), c.y.toFloat(), canBlock = true)
            if (ws.knockback && t.alive) knockback(t, c.x, c.y, 1)
        }
    }

    private fun breakWeapon(c: Creature) {
        events.add(GameEvent(EventType.ITEM_BROKEN, c.px, c.py, c.team, text = c.weapon?.type?.label ?: ""))
        c.weapon = null
    }

    /**
     * Applique des dégâts à une créature, en tenant compte de sa défense.
     * Aucun tir ami : les dégâts d'une équipe ne touchent jamais ses propres membres.
     */
    fun hitCreature(t: Creature, raw: Float, attacker: Creature?, fromX: Float, fromY: Float, canBlock: Boolean) {
        if (!t.alive) return
        if (attacker != null && attacker.team == t.team) return
        var dmg = raw
        val def = t.defense
        when (def?.type) {
            ItemType.BUBBLE -> {
                def.charges--
                events.add(GameEvent(EventType.ABSORB, t.px, t.py, t.team))
                if (def.charges <= 0) { t.defense = null; events.add(GameEvent(EventType.ITEM_BROKEN, t.px, t.py, t.team, text = def.type.label)) }
                dmg = 0f
            }
            ItemType.SHIELD -> if (canBlock) {
                val vx = fromX - t.x; val vy = fromY - t.y
                val len = sqrt(vx * vx + vy * vy)
                val flen = sqrt((t.fdx * t.fdx + t.fdy * t.fdy).toFloat())
                if (len > 0f && flen > 0f) {
                    val dot = (vx * t.fdx + vy * t.fdy) / (len * flen)
                    if (dot > Balance.SHIELD_FRONT_DOT) {
                        def.charges--
                        dmg = 0f
                        events.add(GameEvent(EventType.BLOCK, t.px, t.py, t.team))
                        if (def.charges <= 0) { t.defense = null; events.add(GameEvent(EventType.ITEM_BROKEN, t.px, t.py, t.team, text = def.type.label)) }
                    }
                }
            }
            ItemType.ARMOR -> {
                val absorbed = dmg * Balance.ARMOR_REDUCTION
                dmg -= absorbed
                def.charges -= absorbed.roundToInt().coerceAtLeast(1)
                if (def.charges <= 0) { t.defense = null; events.add(GameEvent(EventType.ITEM_BROKEN, t.px, t.py, t.team, text = def.type.label)) }
            }
            else -> Unit
        }
        if (attacker != null) {
            t.lastAttackerId = attacker.id
            t.lastHitTime = time
            onAttacked(t, attacker)
        }
        if (dmg <= 0f) return
        t.hp -= dmg
        events.add(GameEvent(EventType.HIT, t.px, t.py, t.team, dmg))
        if (t.hp <= 0f) kill(t, attacker)
    }

    /** Riposte automatique : une créature attaquée se défend intelligemment. */
    private fun onAttacked(t: Creature, attacker: Creature) {
        if (!attacker.alive || !visibleTo(attacker, t.team)) return
        when (val o = t.order) {
            is Order.Move -> return // ordre de déplacement explicite : on continue (retraite possible)
            is Order.Attack -> {
                val cur = creature(o.targetId)
                if (cur == null || cur.id == attacker.id) return
                // On change de cible seulement si l'agresseur est à portée et la cible actuelle non.
                if (!inRange(t, cur.x, cur.y) && inRange(t, attacker.x, attacker.y)) t.autoTargetId = attacker.id
            }
            else -> {
                val cur = creature(t.autoTargetId)
                val switch = cur == null || !visibleTo(cur, t.team) ||
                    (!inRange(t, cur.x, cur.y) && inRange(t, attacker.x, attacker.y))
                if (switch) {
                    if (t.order is Order.Idle && cur == null) { t.anchorX = t.x; t.anchorY = t.y }
                    t.autoTargetId = attacker.id
                }
            }
        }
    }

    private fun kill(t: Creature, killer: Creature?) {
        t.alive = false
        t.hp = 0f
        occ[map.idx(t.x, t.y)] = 0
        if (t.isMoving && occ[map.idx(t.tx, t.ty)] == t.id) occ[map.idx(t.tx, t.ty)] = 0
        // Tout l'équipement tombe au sol, récupérable par tous.
        for (cat in Category.values()) {
            val it = t.itemIn(cat) ?: continue
            groundItems.add(GroundItem(nextItemId++, it, t.x, t.y))
            t.setItem(cat, null)
        }
        teams[t.team].deaths++
        if (killer != null) {
            killer.kills++
            teams[killer.team].kills++
            if (firstBloodTeam < 0) {
                firstBloodTeam = killer.team
                events.add(GameEvent(EventType.FIRST_BLOOD, t.px, t.py, killer.team))
            }
        }
        events.add(GameEvent(EventType.DEATH, t.px, t.py, t.team))
    }

    private fun knockback(t: Creature, fromX: Int, fromY: Int, tiles: Int) {
        if (t.isMoving) return
        val dx = sign((t.x - fromX).toFloat()).toInt(); val dy = sign((t.y - fromY).toFloat()).toInt()
        if (dx == 0 && dy == 0) return
        var moved = false
        repeat(tiles) {
            val nx = t.x + dx; val ny = t.y + dy
            if (!passable(t.team, nx, ny) || occ[map.idx(nx, ny)] != 0) return@repeat
            if (map.fortOwner[map.idx(nx, ny)] >= 0) return@repeat
            if (dx != 0 && dy != 0 && (!passable(t.team, t.x + dx, t.y) || !passable(t.team, t.x, t.y + dy))) return@repeat
            occ[map.idx(t.x, t.y)] = 0
            t.x = nx; t.y = ny; t.tx = nx; t.ty = ny
            occ[map.idx(nx, ny)] = t.id
            moved = true
        }
        t.stunTimer = max(t.stunTimer, Balance.KNOCKBACK_STUN)
        if (moved) {
            t.path = emptyList(); t.pathGoalKey = Long.MIN_VALUE
            pickup(t, swap = false)
        }
    }

    // ------------------------------------------------------------------- Structures

    private fun attackStructure(c: Creature, x: Int, y: Int, dt: Float): Boolean {
        val ws = c.weaponStats
        if (inRange(c, x, y)) {
            c.path = emptyList(); c.pathGoalKey = Long.MIN_VALUE
            face(c, x, y)
            if (c.attackCooldown > 0f) return true
            c.attackCooldown = ws.cooldown
            c.invisTimer = 0f
            val dmg = ws.damage * ws.structureMult * damageMult(c)
            if (ws.ranged) {
                val w = c.weapon!!
                w.charges--
                val d = Pos(c.x, c.y).dist(Pos(x, y))
                projectiles.add(
                    Projectile(
                        ProjectileKind.BOLT, c.team, c.id, c.px, c.py, x.toFloat(), y.toFloat(), 0, dmg,
                        x, y, duration = max(0.1f, d / Balance.PROJECTILE_SPEED),
                    )
                )
                events.add(GameEvent(EventType.SHOOT, c.px, c.py, c.team))
                if (w.charges <= 0) breakWeapon(c)
            } else {
                events.add(GameEvent(EventType.SWING, c.px, c.py, c.team))
                damageStructure(x, y, dmg, c.team)
            }
            return true
        }
        return followPath(c, dt, key(x, y) * 17 + ws.range.toInt()) { goalsWithin(c, x, y, ws.range, ws.ranged) }
    }

    /** Dégâts à une brique ou barricade ennemie. Retourne vrai si détruite. */
    private fun damageStructure(x: Int, y: Int, dmg: Float, team: Int): Boolean {
        if (!map.inside(x, y)) return false
        val i = map.idx(x, y)
        if (map.brickHp[i] > 0f && map.brickOwner[i] != team) {
            map.brickHp[i] -= dmg
            if (map.brickHp[i] <= 0f) {
                map.brickHp[i] = 0f
                map.version++
                events.add(GameEvent(EventType.BRICK_BREAK, x.toFloat(), y.toFloat(), map.brickOwner[i]))
                return true
            }
            events.add(GameEvent(EventType.BRICK_HIT, x.toFloat(), y.toFloat(), map.brickOwner[i], dmg))
            return false
        }
        val b = barricadeAt[i]
        if (b != null && b.team != team) {
            b.hp -= dmg
            if (b.hp <= 0f) { removeBarricade(b); return true }
            events.add(GameEvent(EventType.BRICK_HIT, x.toFloat(), y.toFloat(), b.team, dmg))
        }
        return false
    }

    private fun throwBomb(c: Creature, x: Int, y: Int, dt: Float, target: Creature? = null): Boolean {
        val ws = c.weaponStats
        if (Pos(c.x, c.y).dist(Pos(x, y)) <= ws.range + 0.01f) {
            c.path = emptyList(); c.pathGoalKey = Long.MIN_VALUE
            face(c, x, y)
            if (c.attackCooldown > 0f) return true
            c.attackCooldown = ws.cooldown
            c.invisTimer = 0f
            val w = c.weapon!!
            w.charges--
            projectiles.add(
                Projectile(
                    ProjectileKind.BOMB, c.team, c.id, c.px, c.py, x.toFloat(), y.toFloat(), target?.id ?: 0,
                    ws.damage * damageMult(c), x, y, duration = Balance.BOMB_FLIGHT,
                )
            )
            events.add(GameEvent(EventType.THROW, c.px, c.py, c.team))
            if (w.charges <= 0) breakWeapon(c)
            if (target == null) setOrder(c, Order.Idle)
            return true
        }
        return followPath(c, dt, key(x, y) * 13 + 4) { goalsWithin(c, x, y, ws.range, euclid = true) }
    }

    private fun explode(p: Projectile) {
        val owner = creatures.firstOrNull { it.id == p.ownerId }
        val cx = p.ex; val cy = p.ey
        val r = ItemType.BOMB.weapon!!.splash
        events.add(GameEvent(EventType.EXPLODE, cx, cy, p.team))
        for (e in creatures.toList()) {
            if (!e.alive || e.team == p.team) continue
            val dx = e.px - cx; val dy = e.py - cy
            if (dx * dx + dy * dy <= r * r) hitCreature(e, p.damage, owner, cx, cy, canBlock = false)
        }
        val structDmg = ItemType.BOMB.weapon.structureSplashDamage * (if (owner != null && owner.forceTimer > 0f) Balance.FORCE_MULT else 1f)
        val ix = cx.roundToInt(); val iy = cy.roundToInt()
        for (y in iy - 2..iy + 2) for (x in ix - 2..ix + 2) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= r * r) damageStructure(x, y, structDmg, p.team)
        }
    }

    private fun updateProjectiles(dt: Float) {
        val it = projectiles.iterator()
        val done = ArrayList<Projectile>()
        while (it.hasNext()) {
            val p = it.next()
            p.t += dt
            if (p.kind == ProjectileKind.BOLT && p.targetId != 0) {
                val t = creatures.firstOrNull { c -> c.id == p.targetId }
                if (t != null && t.alive) { p.ex = t.px; p.ey = t.py }
            }
            if (p.t >= p.duration) { it.remove(); done.add(p) }
        }
        for (p in done) {
            when (p.kind) {
                ProjectileKind.BOMB -> explode(p)
                ProjectileKind.BOLT -> {
                    if (p.targetId != 0) {
                        val t = creature(p.targetId)
                        val owner = creatures.firstOrNull { c -> c.id == p.ownerId }
                        if (t != null) hitCreature(t, p.damage, owner, p.sx, p.sy, canBlock = true)
                    } else {
                        damageStructure(p.structureX, p.structureY, p.damage, p.team)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------- Défenses actives

    private fun doRepair(c: Creature, o: Order.Repair, dt: Float) {
        val kit = c.defense
        if (kit?.type != ItemType.REPAIR_KIT) { setOrder(c, Order.Idle); return }
        val target = repairTarget(c.team, o.x, o.y) ?: run { setOrder(c, Order.Idle); return }
        if (max(abs(c.x - target.x), abs(c.y - target.y)) <= 1) {
            c.path = emptyList()
            face(c, target.x, target.y)
            val i = map.idx(target.x, target.y)
            if (map.brickHp[i] <= 0f && occ[i] != 0) return // une créature bloque la reconstruction
            c.actionTimer += dt
            if (c.actionTimer >= Balance.REPAIR_TIME) {
                c.actionTimer = 0f
                val wasRubble = map.brickHp[i] <= 0f
                map.brickHp[i] = Balance.BRICK_HP
                if (wasRubble) map.version++
                kit.charges--
                events.add(GameEvent(EventType.BRICK_REPAIR, target.x.toFloat(), target.y.toFloat(), c.team))
                if (kit.charges <= 0) {
                    c.defense = null
                    events.add(GameEvent(EventType.ITEM_BROKEN, c.px, c.py, c.team, text = kit.type.label))
                    setOrder(c, Order.Idle)
                }
            }
        } else {
            c.actionTimer = 0f
            followPath(c, dt, key(target.x, target.y) * 7 + 1) { goalsWithin(c, target.x, target.y, 1f, false) }
        }
    }

    /** Brique abîmée ou détruite de [team] la plus proche de (x, y), dans un rayon raisonnable. */
    fun repairTarget(team: Int, x: Int, y: Int): Pos? {
        var best: Pos? = null
        var bestD = Float.MAX_VALUE
        for (yy in 0 until map.height) for (xx in 0 until map.width) {
            val i = map.idx(xx, yy)
            if (map.brickOwner[i] != team) continue
            if (map.brickHp[i] >= Balance.BRICK_HP - 0.5f) continue
            val d = Pos(x, y).dist(Pos(xx, yy)) + (if (map.brickHp[i] <= 0f) -0.5f else 0f)
            if (d < bestD && d <= 8f) { bestD = d; best = Pos(xx, yy) }
        }
        return best
    }

    private fun doBarricade(c: Creature, o: Order.PlaceBarricade, dt: Float) {
        val kit = c.defense
        if (kit?.type != ItemType.BARRICADE || !canPlaceBarricade(o.x, o.y)) { setOrder(c, Order.Idle); return }
        if (max(abs(c.x - o.x), abs(c.y - o.y)) <= 1 && !(c.x == o.x && c.y == o.y)) {
            c.path = emptyList()
            face(c, o.x, o.y)
            if (occ[map.idx(o.x, o.y)] != 0) return
            c.actionTimer += dt
            if (c.actionTimer < 0.6f) return
            val b = Barricade(c.team, o.x, o.y)
            barricades.add(b)
            barricadeAt[map.idx(o.x, o.y)] = b
            map.version++
            kit.charges--
            events.add(GameEvent(EventType.BARRICADE_UP, o.x.toFloat(), o.y.toFloat(), c.team))
            if (kit.charges <= 0) c.defense = null
            setOrder(c, Order.Idle)
        } else {
            followPath(c, dt, key(o.x, o.y) * 5 + 3) {
                goalsWithin(c, o.x, o.y, 1f, false).filter { it.x != o.x || it.y != o.y }
            }
        }
    }

    private fun removeBarricade(b: Barricade) {
        barricades.remove(b)
        barricadeAt[map.idx(b.x, b.y)] = null
        map.version++
        events.add(GameEvent(EventType.BARRICADE_DOWN, b.x.toFloat(), b.y.toFloat(), b.team))
    }

    private fun updateBarricades(dt: Float) {
        for (b in barricades.toList()) {
            b.timeLeft -= dt
            if (b.timeLeft <= 0f) removeBarricade(b)
        }
    }

    // ------------------------------------------------------------------- Pouvoirs

    private fun usePower(c: Creature, targetId: Int): Boolean {
        val p = c.power ?: return false
        if (c.isDisabled) return false
        when (p.type) {
            ItemType.HEAL -> c.hp = min(Balance.MAX_HP, c.hp + Balance.HEAL_AMOUNT)
            ItemType.SPEED -> c.speedTimer = Balance.SPEED_DURATION
            ItemType.FORCE -> c.forceTimer = Balance.FORCE_DURATION
            ItemType.INVISIBILITY -> c.invisTimer = Balance.INVIS_DURATION
            ItemType.SHOCKWAVE -> {
                if (c.isMoving) return false
                events.add(GameEvent(EventType.SHOCKWAVE, c.px, c.py, c.team))
                val targets = creatures.filter {
                    it.alive && it.team != c.team && Pos(c.x, c.y).dist(Pos(it.x, it.y)) <= Balance.SHOCK_RADIUS
                }.sortedByDescending { Pos(c.x, c.y).dist(Pos(it.x, it.y)) }
                for (t in targets) {
                    hitCreature(t, Balance.SHOCK_DAMAGE, c, c.x.toFloat(), c.y.toFloat(), canBlock = false)
                    if (t.alive) {
                        knockback(t, c.x, c.y, Balance.SHOCK_PUSH)
                        t.stunTimer = max(t.stunTimer, Balance.SHOCK_STUN)
                    }
                }
            }
            ItemType.FREEZE -> {
                val t = creature(targetId) ?: return false
                if (t.team == c.team || !visibleTo(t, c.team)) return false
                setOrder(c, Order.CastFreeze(t.id))
                return true // le pouvoir est consommé au moment du lancer
            }
            else -> return false
        }
        c.power = null
        events.add(GameEvent(EventType.POWER, c.px, c.py, c.team, text = p.type.label))
        return true
    }

    // ------------------------------------------------------------------- Élimination

    private fun eliminate(team: Int, byTeam: Int, by: Creature) {
        val t = teams[team]
        t.eliminated = true
        t.eliminatedAt = time
        t.eliminatedBy = byTeam
        t.pendingOffer = null
        t.bag.clear()
        val fort = map.fortOf(team)!!
        for (c in creatures) if (c.alive && c.team == team) {
            c.alive = false
            occ[map.idx(c.x, c.y)] = 0
            if (c.isMoving && occ[map.idx(c.tx, c.ty)] == c.id) occ[map.idx(c.tx, c.ty)] = 0
            for (cat in Category.values()) {
                val it = c.itemIn(cat) ?: continue
                groundItems.add(GroundItem(nextItemId++, it, c.x, c.y)); c.setItem(cat, null)
            }
        }
        // Le fort tombe : ses cases et briques restantes deviennent neutres.
        for (p in fort.tiles()) map.fortOwner[map.idx(p.x, p.y)] = -1
        map.version++
        events.add(GameEvent(EventType.TEAM_ELIMINATED, fort.centerX, fort.centerY, team, text = t.name))
        val left = activeTeams()
        if (left.size == 1) {
            winner = left[0].id
            events.add(GameEvent(EventType.VICTORY, by.px, by.py, winner))
        }
    }

    // ------------------------------------------------------------------- Déplacement

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 20) or y.toLong()

    private fun face(c: Creature, x: Int, y: Int) {
        val dx = sign((x - c.x).toFloat()).toInt(); val dy = sign((y - c.y).toFloat()).toInt()
        if (dx != 0 || dy != 0) { c.fdx = dx; c.fdy = dy }
    }

    /** Cases d'où [c] peut atteindre (x, y) avec une portée donnée. */
    private fun goalsWithin(c: Creature, x: Int, y: Int, range: Float, euclid: Boolean): List<Pos> {
        val r = range.toInt().coerceAtLeast(1)
        val out = ArrayList<Pos>()
        for (dy in -r..r) for (dx in -r..r) {
            val nx = x + dx; val ny = y + dy
            if (euclid && sqrt((dx * dx + dy * dy).toFloat()) > range + 0.01f) continue
            if (!euclid && max(abs(dx), abs(dy)) > r) continue
            if (dx == 0 && dy == 0 && !euclid) continue
            if (!passable(c.team, nx, ny)) continue
            val o = occ[map.idx(nx, ny)]
            if (o != 0 && o != c.id) continue
            out.add(Pos(nx, ny))
        }
        return out
    }

    /**
     * Suit (et recalcule si besoin) un chemin vers des buts. Retourne faux si aucun chemin n'existe.
     */
    private fun followPath(c: Creature, dt: Float, goalKey: Long, goals: () -> List<Pos>): Boolean {
        c.repathTimer -= dt
        val needPath = c.pathGoalKey != goalKey || c.pathIdx >= c.path.size || c.repathTimer <= 0f
        if (needPath) {
            val g = goals()
            if (g.isEmpty()) { c.noPathTimer += dt; return c.noPathTimer < 2f }
            val p = computePath(c, g, avoidCreatures = c.blockedTimer > 0.4f)
                ?: computePath(c, g, avoidCreatures = false)
            c.pathGoalKey = goalKey
            c.repathTimer = 1.2f
            if (p == null) {
                c.path = emptyList(); c.pathIdx = 0
                c.noPathTimer += dt
                return c.noPathTimer < 1.5f
            }
            c.noPathTimer = 0f
            c.path = p; c.pathIdx = 0
            if (p.isEmpty()) return true
        }
        if (c.pathIdx >= c.path.size) return true
        val next = c.path[c.pathIdx]
        if (!passable(c.team, next.x, next.y)) { c.pathGoalKey = Long.MIN_VALUE; return true }
        val o = occ[map.idx(next.x, next.y)]
        if (o != 0 && o != c.id) {
            c.blockedTimer += dt
            if (c.blockedTimer > 0.5f) { c.pathGoalKey = Long.MIN_VALUE; c.blockedTimer = if (c.blockedTimer > 3f) 0f else c.blockedTimer }
            return true
        }
        val dx = next.x - c.x; val dy = next.y - c.y
        if (abs(dx) > 1 || abs(dy) > 1) { c.pathGoalKey = Long.MIN_VALUE; return true }
        if (dx != 0 && dy != 0 && (!passable(c.team, c.x + dx, c.y) || !passable(c.team, c.x, c.y + dy))) {
            c.pathGoalKey = Long.MIN_VALUE; return true
        }
        c.blockedTimer = 0f
        c.pathIdx++
        c.tx = next.x; c.ty = next.y; c.moveT = 0f
        val diag = dx != 0 && dy != 0
        val speed = if (c.speedTimer > 0f) Balance.SPEED_MULT else 1f
        c.moveDur = Balance.STEP_TIME * (if (diag) Pathfinder.SQRT2 else 1f) / speed
        c.fdx = dx; c.fdy = dy
        occ[map.idx(next.x, next.y)] = c.id
        return true
    }

    private fun computePath(c: Creature, goals: List<Pos>, avoidCreatures: Boolean): List<Pos>? {
        val team = c.team
        return pathfinder.find(
            c.x, c.y, goals,
            passable = { x, y ->
                if (!passable(team, x, y)) false
                else if (avoidCreatures) {
                    val o = occ[map.idx(x, y)]
                    o == 0 || o == c.id || max(abs(x - c.x), abs(y - c.y)) > 2
                } else true
            },
            extraCost = { x, y ->
                val o = occ[map.idx(x, y)]
                if (o != 0 && o != c.id) 3f else 0f
            },
        )
    }

    private fun nearestReachable(c: Creature, x: Int, y: Int): Pos? {
        val field = pathfinder.distanceField(listOf(Pos(c.x, c.y)), { xx, yy -> passable(c.team, xx, yy) })
        var best: Pos? = null
        var bestD = Float.MAX_VALUE
        for (yy in max(0, y - 6)..min(map.height - 1, y + 6)) for (xx in max(0, x - 6)..min(map.width - 1, x + 6)) {
            if (field[map.idx(xx, yy)].isInfinite()) continue
            val d = Pos(x, y).dist(Pos(xx, yy))
            if (d < bestD) { bestD = d; best = Pos(xx, yy) }
        }
        return best
    }
}
