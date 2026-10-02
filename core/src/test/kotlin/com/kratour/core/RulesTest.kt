package com.kratour.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {

    private fun duel() = World(MapId.DUEL, listOf("A", "B"), 0, 42L)

    private fun run(w: World, seconds: Float) {
        val steps = (seconds / Balance.TICK).toInt()
        repeat(steps) { w.step() }
    }

    @Test
    fun startsWithThreeNakedCreatures() {
        val w = duel()
        for (t in 0..1) {
            val cs = w.aliveOf(t)
            assertEquals(3, cs.size)
            assertTrue(cs.all { it.weapon == null && it.defense == null && it.power == null && it.hp == 100f })
        }
    }

    @Test
    fun spawnsEverySixtySecondsUpToSeven() {
        val w = duel()
        run(w, 61f)
        assertEquals(4, w.countAlive(0))
        run(w, 60f * 6)
        assertEquals(7, w.countAlive(0))
        run(w, 60f)
        assertEquals(7, w.countAlive(0))
    }

    @Test
    fun offersAreIdenticalForAllTeamsAndRotate() {
        val w = World(MapId.MELEE, listOf("A", "B", "C", "D"), 0, 7L)
        run(w, Balance.FIRST_OFFER_AT + 0.1f)
        val o = w.teams.map { it.pendingOffer!! }
        assertTrue(o.all { it === o[0] })
        assertEquals(Category.WEAPON, o[0].category)
        assertEquals(2, o[0].options.distinct().size)
        // Choix indépendants
        assertTrue(w.chooseOffer(0, o[0].id, 0))
        assertTrue(w.chooseOffer(1, o[0].id, 1))
        assertEquals(o[0].options[0], w.teams[0].bag[0].type)
        assertEquals(o[0].options[1], w.teams[1].bag[0].type)
        run(w, Balance.OFFER_INTERVAL)
        assertEquals(Category.DEFENSE, w.teams[0].pendingOffer!!.category)
        // L'équipe 2 n'avait pas choisi : elle reçoit l'option 1 automatiquement, rien n'est perdu.
        assertEquals(1, w.teams[2].bag.size)
        run(w, Balance.OFFER_INTERVAL)
        assertEquals(Category.POWER, w.teams[0].pendingOffer!!.category)
    }

    @Test
    fun bagAssignmentSwapsBackIntoBag() {
        val w = duel()
        val c = w.aliveOf(0)[0]
        w.teams[0].bag.add(Item(ItemType.SWORD))
        w.teams[0].bag.add(Item(ItemType.MACE))
        assertTrue(w.assignFromBag(0, 0, c.id))
        assertEquals(ItemType.SWORD, c.weapon!!.type)
        assertTrue(w.assignFromBag(0, 0, c.id))
        assertEquals(ItemType.MACE, c.weapon!!.type)
        assertEquals(ItemType.SWORD, w.teams[0].bag.single().type)
        // Impossible d'équiper une créature adverse
        assertFalse(w.assignFromBag(0, 0, w.aliveOf(1)[0].id))
    }

    @Test
    fun deathDropsEverythingAndLootCanBePickedByEnemy() {
        val w = duel()
        val a = w.aliveOf(0)[0]
        val b = w.aliveOf(1)[0]
        a.weapon = Item(ItemType.SPEAR); a.defense = Item(ItemType.ARMOR); a.power = Item(ItemType.HEAL)
        a.hp = 1f
        w.hitCreature(a, 10f, b, b.x.toFloat(), b.y.toFloat(), true)
        assertFalse(a.alive)
        assertEquals(3, w.itemsAt(a.x, a.y).size)
        assertEquals(1, w.firstBloodTeam)
        // Le gagnant va chercher le butin
        b.weapon = Item(ItemType.SWORD)
        w.issue(1, Command.MoveTo(b.id, a.x, a.y))
        run(w, 40f)
        assertEquals(a.x, b.x); assertEquals(a.y, b.y)
        assertEquals(ItemType.SPEAR, b.weapon!!.type)
        assertEquals(ItemType.ARMOR, b.defense!!.type)
        // L'épée a été lâchée au sol à la place
        assertTrue(w.itemsAt(a.x, a.y).any { it.item.type == ItemType.SWORD })
    }

    @Test
    fun noFriendlyFire() {
        val w = duel()
        val a = w.aliveOf(0)[0]; val b = w.aliveOf(0)[1]
        w.hitCreature(b, 50f, a, a.x.toFloat(), a.y.toFloat(), true)
        assertEquals(100f, b.hp, 0.01f)
        assertFalse(w.issue(0, Command.AttackCreature(a.id, b.id)))
    }

    @Test
    fun attackedCreatureFightsBack() {
        val w = duel()
        val a = w.aliveOf(0)[0]
        val e = w.aliveOf(1)[0]
        // Place l'ennemi juste à côté
        teleport(w, e, a.x + 1, a.y)
        e.weapon = Item(ItemType.SWORD)
        w.issue(1, Command.AttackCreature(e.id, a.id))
        run(w, 1.5f)
        assertTrue(a.hp < 100f)
        val hpBefore = e.hp
        run(w, 2f)
        assertTrue("la créature attaquée doit riposter", e.hp < hpBefore)
    }

    @Test
    fun bareHandsBarelyScratchBricksButHammerBreaksThem() {
        val w = duel()
        val fort = w.map.fortOf(1)!!
        val brick = w.map.bricksOf(1).first { it.x == fort.x - 2 && it.y == fort.y }
        val a = w.aliveOf(0)[0]
        teleport(w, a, brick.x - 1, brick.y)
        w.issue(0, Command.AttackTile(a.id, brick.x, brick.y))
        run(w, 10f)
        val hpFists = w.map.brickHp[w.map.idx(brick.x, brick.y)]
        assertTrue(hpFists > Balance.BRICK_HP - 10f)
        a.weapon = Item(ItemType.HAMMER)
        w.issue(0, Command.AttackTile(a.id, brick.x, brick.y))
        run(w, 40f)
        assertFalse(w.map.hasBrick(brick.x, brick.y))
    }

    @Test
    fun enteringEnemyFortEliminatesTeam() {
        val w = duel()
        val fort = w.map.fortOf(1)!!
        for (b in w.map.bricksOf(1)) w.map.brickHp[w.map.idx(b.x, b.y)] = 0f
        w.map.version++
        val a = w.aliveOf(0)[0]
        teleport(w, a, fort.x - 2, fort.y)
        w.issue(0, Command.MoveTo(a.id, fort.x, fort.y))
        run(w, 5f)
        assertTrue(w.teams[1].eliminated)
        assertEquals(0, w.winner)
        assertEquals(0, w.countAlive(1))
    }

    @Test
    fun ownFortIsNotWalkable() {
        val w = duel()
        val fort = w.map.fortOf(0)!!
        assertFalse(w.passable(0, fort.x, fort.y))
        assertTrue(w.map.walkableStatic(fort.x, fort.y, 1))
    }

    @Test
    fun shieldBlocksFrontButNotBack() {
        val w = duel()
        val a = w.aliveOf(0)[0]; val e = w.aliveOf(1)[0]
        a.defense = Item(ItemType.SHIELD)
        a.fdx = 1; a.fdy = 0
        w.hitCreature(a, 20f, e, a.x + 1f, a.y.toFloat(), true)
        assertEquals(100f, a.hp, 0.01f)
        w.hitCreature(a, 20f, e, a.x - 1f, a.y.toFloat(), true)
        assertEquals(80f, a.hp, 0.01f)
    }

    @Test
    fun bubbleAbsorbsTwoHits() {
        val w = duel()
        val a = w.aliveOf(0)[0]; val e = w.aliveOf(1)[0]
        a.defense = Item(ItemType.BUBBLE)
        repeat(2) { w.hitCreature(a, 30f, e, 0f, 0f, true) }
        assertEquals(100f, a.hp, 0.01f)
        assertNull(a.defense)
        w.hitCreature(a, 30f, e, 0f, 0f, true)
        assertEquals(70f, a.hp, 0.01f)
    }

    @Test
    fun crossbowRunsOutOfAmmo() {
        val w = duel()
        val a = w.aliveOf(0)[0]; val e = w.aliveOf(1)[0]
        teleport(w, e, a.x + 4, a.y)
        e.hp = 1000f
        a.weapon = Item(ItemType.CROSSBOW)
        w.issue(0, Command.AttackCreature(a.id, e.id))
        run(w, 12f)
        assertNull(a.weapon)
        assertTrue(e.hp <= 1000f - 6 * 14f + 0.1f) // 6 carreaux tirés (les alliés proches peuvent aider)
    }

    @Test
    fun invisibleEnemiesAreHiddenFromTheView() {
        val w = duel()
        val e = w.aliveOf(1)[0]
        e.power = Item(ItemType.INVISIBILITY)
        assertTrue(w.issue(1, Command.UsePower(e.id)))
        val view = TeamView(w, 0)
        assertTrue(view.enemies().none { it.id == e.id })
        assertFalse(w.issue(0, Command.AttackCreature(w.aliveOf(0)[0].id, e.id)))
        assertNotNull(TeamView(w, 1).mine().firstOrNull { it.id == e.id })
    }

    @Test
    fun repairKitRebuildsBricks() {
        val w = duel()
        val bricks = w.map.bricksOf(0)
        val b = bricks.first()
        w.map.brickHp[w.map.idx(b.x, b.y)] = 0f
        val a = w.aliveOf(0)[0]
        a.defense = Item(ItemType.REPAIR_KIT)
        assertTrue(w.issue(0, Command.Repair(a.id, b.x, b.y)))
        run(w, 25f)
        assertTrue(w.map.hasBrick(b.x, b.y))
        assertEquals(2, a.defense!!.charges)
    }

    private fun teleport(w: World, c: Creature, x: Int, y: Int) {
        // Utilitaire de test : déplace une créature via des ordres successifs serait long ; on triche ici seulement.
        val f = World::class.java.getDeclaredField("occ"); f.isAccessible = true
        val occ = f.get(w) as IntArray
        occ[w.map.idx(c.x, c.y)] = 0
        c.x = x; c.y = y; c.tx = x; c.ty = y; c.anchorX = x; c.anchorY = y
        occ[w.map.idx(x, y)] = c.id
    }
}
