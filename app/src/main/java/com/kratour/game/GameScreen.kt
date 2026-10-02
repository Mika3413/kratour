package com.kratour.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import com.kratour.core.Balance
import com.kratour.core.Category
import com.kratour.core.Command
import com.kratour.core.Creature
import com.kratour.core.EventType
import com.kratour.core.GameConfig
import com.kratour.core.GameSession
import com.kratour.core.ItemType
import com.kratour.core.Pos
import com.kratour.core.TeamNames
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/** Écran de partie : monde + interface tactile. */
class GameScreen(private val app: GameApp, private val config: GameConfig) : Screen {
    private val session = GameSession(config)
    private val world = session.world
    private val me = config.humanTeam
    private val cam = IsoCamera()
    private val renderer = WorldRenderer(world, cam, me)
    private val ui = app.ui
    private val tmp = PointF()

    private var selectedId = 0
    private var viewEnemyId = 0
    private var bagOpen = false
    private var paused = false
    private var overTime = -1f
    private var spectating = false
    private var lastRosterTap = 0L
    private var lastRosterId = 0

    private enum class Targeting(val hint: String) {
        FREEZE("Touchez un ennemi à geler"),
        BOMB("Touchez la case où lancer la bombe"),
        REPAIR("Touchez une brique de votre fort à réparer"),
        BARRICADE("Touchez une case libre pour la barricade"),
    }
    private var targeting: Targeting? = null

    private class Msg(val text: String, val color: Int, var ttl: Float)
    private val messages = ArrayList<Msg>()

    // Layout
    private val teamPanel = RectF()
    private val timerRect = RectF()
    private val pauseBtn = RectF()
    private val minimap = RectF()
    private val offerPanel = RectF()
    private val offerA = RectF()
    private val offerB = RectF()
    private val card = RectF()
    private val slots = Array(3) { RectF() }
    private val roster = Array(Balance.MAX_ALIVE) { RectF() }
    private val bagBtn = RectF()
    private val bagPanel = RectF()
    private val bagCells = ArrayList<RectF>()
    private val hintRect = RectF()
    private val hintCancel = RectF()
    private val overlayBtns = Array(4) { RectF() }
    private val r = RectF()

    // Toucher
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var dragging = false
    private var multi = false
    private var downOnHud = false
    private var downOnMinimap = false
    private var pinchDist = 0f
    private var midX = 0f; private var midY = 0f

    init {
        cam.setBounds(world.map.width, world.map.height)
        if (me >= 0) {
            val f = world.map.fortOf(me)!!
            cam.centerOnTile((f.x + f.spawnX) / 2f + 1f, (f.y + f.spawnY) / 2f)
        }
        message("Cassez les briques et entrez dans un fort ennemi !", Art.UI_ACCENT, 5f)
    }

    // =================================================================== Cycle

    override fun resize(w: Int, h: Int) {
        cam.screenW = w; cam.screenH = h
        cam.baseZoom = h / (15f * IsoCamera.TH)
        if (cam.zoom == 1f) cam.zoom = cam.baseZoom * 1.15f
        cam.clamp()
        val u = ui.u
        val W = w.toFloat(); val H = h.toFloat()
        val rows = world.teams.size
        teamPanel.set(8 * u, 8 * u, 8 * u + 150 * u, 8 * u + 10 * u + rows * 19 * u)
        pauseBtn.set(W - 50 * u, 8 * u, W - 8 * u, 50 * u)
        minimap.set(W - 8 * u - 132 * u, 56 * u, W - 8 * u, 56 * u + 76 * u)
        timerRect.set(W / 2 - 130 * u, 6 * u, W / 2 + 130 * u, 28 * u)
        offerPanel.set(W / 2 - 150 * u, 32 * u, W / 2 + 150 * u, 32 * u + 96 * u)
        offerA.set(offerPanel.left + 8 * u, offerPanel.top + 26 * u, offerPanel.centerX() - 4 * u, offerPanel.bottom - 8 * u)
        offerB.set(offerPanel.centerX() + 4 * u, offerPanel.top + 26 * u, offerPanel.right - 8 * u, offerPanel.bottom - 8 * u)
        card.set(8 * u, H - 8 * u - 84 * u, 8 * u + 214 * u, H - 8 * u)
        for (i in 0..2) {
            val x = card.left + 66 * u + i * 49 * u
            slots[i].set(x, card.top + 24 * u, x + 45 * u, card.top + 24 * u + 45 * u)
        }
        bagBtn.set(W - 8 * u - 66 * u, H - 8 * u - 66 * u, W - 8 * u, H - 8 * u)
        val rosterLeft = card.right + 10 * u
        val avail = bagBtn.left - 10 * u - rosterLeft
        val cell = min(46 * u, avail / Balance.MAX_ALIVE)
        for (i in roster.indices) {
            val x = rosterLeft + i * cell
            roster[i].set(x, H - 8 * u - 52 * u, x + cell - 4 * u, H - 8 * u)
        }
        bagPanel.set(W - 8 * u - 300 * u, bagBtn.top - 8 * u - 160 * u, W - 8 * u, bagBtn.top - 8 * u)
        hintRect.set(W / 2 - 170 * u, H - 8 * u - 52 * u - 46 * u, W / 2 + 170 * u, H - 8 * u - 52 * u - 8 * u)
        hintCancel.set(hintRect.right - 86 * u, hintRect.top + 5 * u, hintRect.right - 6 * u, hintRect.bottom - 5 * u)
    }

    override fun update(dt: Float) {
        if (!paused) session.advance(dt)
        renderer.update(dt)
        processEvents()
        val it = messages.iterator()
        while (it.hasNext()) { val m = it.next(); m.ttl -= dt; if (m.ttl <= 0f) it.remove() }
        if (world.creature(selectedId) == null) { if (selectedId != 0) targeting = null; selectedId = 0 }
        val ve = world.creature(viewEnemyId)
        if (ve == null || !world.visibleTo(ve, me)) viewEnemyId = 0
        if ((world.over || world.teams[me].eliminated) && overTime < 0f) {
            overTime = 0f
            val won = world.winner == me
            app.sound.play(if (won) SoundFx.S.WIN else SoundFx.S.LOSE)
        }
        if (overTime >= 0f) overTime += dt
    }

    override fun onPause() { if (!world.over) paused = true }

    override fun onBack(): Boolean {
        when {
            targeting != null -> targeting = null
            bagOpen -> bagOpen = false
            else -> paused = !paused
        }
        return true
    }

    override fun dispose() { renderer.release() }

    private fun message(t: String, color: Int = Art.UI_TEXT, ttl: Float = 3f) {
        messages.add(0, Msg(t, color, ttl))
        while (messages.size > 4) messages.removeAt(messages.size - 1)
    }

    private fun teamName(t: Int) = if (t == me) "Vous" else "Les ${TeamNames.names[t]}"

    private fun processEvents() {
        val cx = cam.camX; val cy = cam.camY
        for (e in world.events) {
            renderer.onEvent(e)
            val ex = cam.isoX(e.x, e.y); val ey = cam.isoY(e.x, e.y)
            val d = hypot(ex - cx, ey - cy) / (IsoCamera.TW * 12f)
            val vol = (1f - d).coerceIn(0.12f, 1f)
            val s = app.sound
            when (e.type) {
                EventType.HIT -> s.play(SoundFx.S.HIT, vol)
                EventType.SWING -> s.play(SoundFx.S.SWING, vol * 0.6f)
                EventType.BLOCK, EventType.ABSORB -> s.play(SoundFx.S.BLOCK, vol)
                EventType.SHOOT -> s.play(SoundFx.S.SHOOT, vol)
                EventType.THROW -> s.play(SoundFx.S.THROW, vol)
                EventType.EXPLODE -> s.play(SoundFx.S.EXPLODE, vol)
                EventType.DEATH -> s.play(SoundFx.S.DEATH, vol)
                EventType.BRICK_HIT -> s.play(SoundFx.S.BRICK, vol * 0.7f)
                EventType.BRICK_BREAK -> {
                    s.play(SoundFx.S.BREAK, vol)
                    if (e.team == me) message("Une brique de votre fort est tombée !", Color.rgb(255, 140, 120))
                }
                EventType.BRICK_REPAIR, EventType.BARRICADE_UP -> s.play(SoundFx.S.BRICK, vol)
                EventType.PICKUP, EventType.BONUS -> if (e.team == me) s.play(SoundFx.S.PICKUP, vol)
                EventType.SPAWN -> if (e.team == me) { s.play(SoundFx.S.SPAWN); message("Une nouvelle créature est arrivée", Art.TEAM_LIGHT[0]) }
                EventType.OFFER -> { s.play(SoundFx.S.OFFER); message("Nouveau choix d'équipement : ${e.text}", Art.UI_ACCENT) }
                EventType.POWER, EventType.SHOCKWAVE -> s.play(SoundFx.S.POWER, vol)
                EventType.FREEZE -> s.play(SoundFx.S.FREEZE, vol)
                EventType.FIRST_BLOOD -> message("Premier sang : ${teamName(e.team)} !", Color.rgb(255, 90, 80), 4f)
                EventType.TEAM_ELIMINATED -> {
                    s.play(SoundFx.S.ELIMINATED)
                    message(if (e.team == me) "Votre fort est tombé !" else "${teamName(e.team)} sont éliminés !", Color.rgb(255, 120, 90), 5f)
                }
                EventType.ITEM_BROKEN -> if (e.team == me) message("${e.text} usé(e)", Art.UI_DIM)
                else -> Unit
            }
        }
        world.events.clear()
    }

    // =================================================================== Dessin

    override fun draw(c: Canvas) {
        val highlights = if (targeting == Targeting.REPAIR) world.map.bricksOf(me).filter {
            world.map.brickHp[world.map.idx(it.x, it.y)] < Balance.BRICK_HP - 0.5f
        } else null
        renderer.draw(c, selectedId, highlights, Color.rgb(120, 255, 120))
        drawHud(c)
        if (paused) drawPause(c)
        else if (overTime >= 1.2f && !spectating) drawEnd(c)
    }

    private fun drawHud(c: Canvas) {
        val u = ui.u
        // Équipes
        ui.panel(c, teamPanel)
        for (t in world.teams) {
            val y = teamPanel.top + 8 * u + t.id * 19 * u
            val col = Art.TEAM_COLORS[t.id]
            c.drawCircle(teamPanel.left + 12 * u, y + 7 * u, 5.5f * u, Art.fill(if (t.eliminated) Color.GRAY else col))
            ui.text(c, if (t.id == me) "Vous" else t.name, teamPanel.left + 22 * u, y + 11 * u, 11f, if (t.eliminated) Art.UI_DIM else Art.UI_TEXT)
            if (t.eliminated) {
                ui.text(c, "éliminés", teamPanel.left + 78 * u, y + 11 * u, 10f, Color.rgb(255, 120, 100))
            } else {
                ui.text(c, "${world.countAlive(t.id)}/${Balance.MAX_ALIVE}", teamPanel.left + 72 * u, y + 11 * u, 11f)
                val bricks = world.map.bricksOf(t.id)
                var hp = 0f
                for (b in bricks) hp += world.map.brickHp[world.map.idx(b.x, b.y)]
                val ratio = if (bricks.isEmpty()) 0f else hp / (bricks.size * Balance.BRICK_HP)
                r.set(teamPanel.left + 100 * u, y + 3 * u, teamPanel.right - 8 * u, y + 11 * u)
                c.drawRect(r, Art.fill(Color.argb(160, 0, 0, 0)))
                r.right = r.left + (r.width()) * ratio
                c.drawRect(r, Art.fill(Sprites.mix(Color.rgb(200, 190, 170), col, 0.4f)))
            }
        }

        // Minuteurs
        ui.panel(c, timerRect, radius = 6f)
        val time = world.time.toInt()
        val spawnIn = world.spawnTimer.toInt() + 1
        val offerIn = world.offerTimer.toInt() + 1
        ui.text(
            c, "%d:%02d   ·   Renfort %ds   ·   %s %ds".format(time / 60, time % 60, spawnIn, world.nextOfferCategory.label, offerIn),
            timerRect.centerX(), timerRect.centerY() + 4 * u, 11f, Art.UI_TEXT, Paint.Align.CENTER,
        )

        // Pause + mini-carte
        ui.button(c, pauseBtn, "II", size = 16f)
        ui.panel(c, minimap, Color.argb(200, 20, 30, 44), radius = 6f)
        renderer.drawMinimap(c, minimap)

        // Messages
        var my = if (world.teams[me].pendingOffer != null && !world.teams[me].eliminated) offerPanel.bottom + 18 * u else 48 * u
        for (m in messages) {
            val a = (min(1f, m.ttl) * 255).toInt()
            ui.text(c, m.text, ui.w / 2f, my, 13f, Art.withAlpha(m.color, a), Paint.Align.CENTER, shadow = true)
            my += 18 * u
        }

        if (world.teams[me].eliminated) {
            if (spectating) ui.text(c, "Mode spectateur", ui.w / 2f, ui.h - 20 * u, 14f, Art.UI_DIM, Paint.Align.CENTER, shadow = true)
            return
        }

        // Choix d'équipement
        world.teams[me].pendingOffer?.let { o ->
            ui.panel(c, offerPanel, Color.argb(230, 30, 34, 52), Art.UI_ACCENT)
            ui.text(c, "Choisissez : ${o.category.label.uppercase()}", offerPanel.centerX(), offerPanel.top + 17 * u, 12f, Art.UI_ACCENT, Paint.Align.CENTER)
            for ((i, rr) in listOf(offerA, offerB).withIndex()) {
                val t = o.options[i]
                ui.panel(c, rr, Art.UI_BG_LIGHT, Art.UI_TEXT, 7f)
                Art.icon(c, t, rr.left + 22 * u, rr.centerY(), 32 * u)
                ui.text(c, t.label, rr.left + 42 * u, rr.top + 18 * u, 12f)
                ui.wrap(c, t.description, rr.left + 42 * u, rr.top + 31 * u, rr.width() - 46 * u, 8.5f, Art.UI_DIM)
            }
        }

        // Carte de la créature sélectionnée (ou ennemie observée)
        val sel = world.creature(selectedId)
        val shown = sel ?: world.creature(viewEnemyId)
        if (shown != null) drawCard(c, shown, shown.team == me)
        else {
            ui.panel(c, card)
            ui.wrap(c, "Touchez une de vos créatures pour la sélectionner, puis touchez une case, un ennemi ou une brique.", card.left + 10 * u, card.top + 20 * u, card.width() - 20 * u, 10.5f, Art.UI_DIM)
        }

        // Effectif
        val mine = world.aliveOf(me).sortedBy { it.id }
        for ((i, rr) in roster.withIndex()) {
            val cr = mine.getOrNull(i)
            ui.panel(c, rr, if (cr != null && cr.id == selectedId) Color.argb(230, 70, 110, 190) else Art.UI_BG, radius = 6f)
            if (cr == null) continue
            ui.kraton(c, rr.centerX(), rr.top + 20 * u, 22 * u, me)
            // mini PV
            r.set(rr.left + 4 * u, rr.bottom - 10 * u, rr.right - 4 * u, rr.bottom - 6 * u)
            c.drawRect(r, Art.fill(Color.argb(180, 0, 0, 0)))
            r.right = r.left + r.width() * (cr.hp / Balance.MAX_HP)
            c.drawRect(r, Art.fill(if (cr.hp > 60) Color.rgb(90, 220, 90) else if (cr.hp > 30) Color.rgb(240, 200, 60) else Color.rgb(240, 70, 60)))
            // pastilles d'équipement
            var dx = rr.left + 6 * u
            for (it in listOf(cr.weapon, cr.defense, cr.power)) {
                c.drawCircle(dx, rr.bottom - 15 * u, 2.6f * u, Art.fill(if (it != null) Art.UI_ACCENT else Color.argb(120, 255, 255, 255)))
                dx += 7 * u
            }
            if (cr.lastHitTime > world.time - 1.5f) c.drawRoundRect(rr, 6 * u, 6 * u, Art.stroke(Color.rgb(255, 80, 60), 2f * u))
        }

        // Sac commun
        val bag = world.teams[me].bag
        ui.button(c, bagBtn, "", selected = bagOpen)
        drawSack(c, bagBtn.centerX(), bagBtn.centerY() - 4 * u, 34 * u)
        ui.text(c, "Sac", bagBtn.centerX(), bagBtn.bottom - 6 * u, 10f, Art.UI_TEXT, Paint.Align.CENTER)
        if (bag.isNotEmpty()) {
            c.drawCircle(bagBtn.right - 6 * u, bagBtn.top + 6 * u, 10 * u, Art.fill(Color.rgb(230, 60, 50)))
            ui.text(c, "${bag.size}", bagBtn.right - 6 * u, bagBtn.top + 10 * u, 11f, Color.WHITE, Paint.Align.CENTER)
        }
        if (bagOpen) drawBag(c)

        // Mode ciblage
        targeting?.let { t ->
            ui.panel(c, hintRect, Color.argb(235, 40, 60, 40), Color.rgb(140, 255, 140))
            ui.text(c, t.hint, hintRect.left + 12 * u, hintRect.centerY() + 5 * u, 12f)
            ui.button(c, hintCancel, "Annuler", size = 11f)
        }
    }

    private fun drawSack(c: Canvas, cx: Float, cy: Float, s: Float) {
        val h = s / 2f
        r.set(cx - h * 0.8f, cy - h * 0.4f, cx + h * 0.8f, cy + h * 0.9f)
        c.drawOval(r, Art.fill(Color.rgb(170, 125, 70)))
        r.set(cx - h * 0.35f, cy - h * 0.8f, cx + h * 0.35f, cy - h * 0.25f)
        c.drawOval(r, Art.fill(Color.rgb(150, 105, 55)))
        c.drawLine(cx - h * 0.45f, cy - h * 0.35f, cx + h * 0.45f, cy - h * 0.35f, Art.stroke(Color.rgb(90, 60, 30), s * 0.07f))
    }

    private fun slotItem(cr: Creature, i: Int) = when (i) { 0 -> cr.weapon; 1 -> cr.defense; else -> cr.power }

    private fun drawCard(c: Canvas, cr: Creature, mine: Boolean) {
        val u = ui.u
        ui.panel(c, card, if (mine) Art.UI_BG else Color.argb(215, 60, 26, 26))
        ui.kraton(c, card.left + 32 * u, card.top + 46 * u, 32 * u, cr.team.coerceIn(0, 3))
        ui.text(c, if (mine) "PV ${cr.hp.toInt()}" else "Ennemi ${cr.hp.toInt()} PV", card.left + 8 * u, card.top + 15 * u, 10.5f)
        r.set(card.left + 8 * u, card.bottom - 16 * u, card.left + 56 * u, card.bottom - 10 * u)
        c.drawRect(r, Art.fill(Color.argb(180, 0, 0, 0)))
        r.right = r.left + r.width() * (cr.hp / Balance.MAX_HP)
        c.drawRect(r, Art.fill(Color.rgb(90, 220, 90)))
        val labels = arrayOf("Arme", "Défense", "Pouvoir")
        for (i in 0..2) {
            val rr = slots[i]
            val it = slotItem(cr, i)
            val usable = mine && it != null && when (it.type) {
                ItemType.BOMB, ItemType.REPAIR_KIT, ItemType.BARRICADE -> true
                else -> it.category == Category.POWER
            }
            ui.panel(c, rr, if (usable) Color.argb(240, 60, 90, 60) else Art.UI_BG_LIGHT, if (usable) Color.rgb(160, 255, 160) else Art.UI_BORDER, 6f)
            ui.text(c, labels[i], rr.centerX(), card.top + 17 * u, 8.5f, Art.UI_DIM, Paint.Align.CENTER, bold = false)
            if (it == null) {
                if (i == 0) ui.text(c, "poings", rr.centerX(), rr.centerY() + 3 * u, 8.5f, Art.UI_DIM, Paint.Align.CENTER, bold = false)
                continue
            }
            Art.icon(c, it.type, rr.centerX(), rr.centerY() - 2 * u, 30 * u)
            if (it.type.usesCharges && (mine || it.type.maxCharges > 1)) {
                val txt = if (mine) "${it.charges}" else ""
                if (txt.isNotEmpty()) ui.text(c, txt, rr.right - 4 * u, rr.bottom - 4 * u, 10f, Color.WHITE, Paint.Align.RIGHT, shadow = true)
            }
            if (usable) ui.text(c, "Utiliser", rr.centerX(), card.bottom - 5 * u, 8.5f, Color.rgb(160, 255, 160), Paint.Align.CENTER)
        }
        // Effets actifs
        val fx = ArrayList<String>()
        if (cr.speedTimer > 0f) fx.add("Vitesse ${cr.speedTimer.toInt() + 1}s")
        if (cr.forceTimer > 0f) fx.add("Force ${cr.forceTimer.toInt() + 1}s")
        if (cr.invisTimer > 0f) fx.add("Invisible ${cr.invisTimer.toInt() + 1}s")
        if (cr.frozenTimer > 0f) fx.add("Gelé")
        if (fx.isNotEmpty()) ui.text(c, fx.joinToString(" · "), card.left + 8 * u, card.top - 6 * u, 10.5f, Art.UI_ACCENT, shadow = true)
    }

    private fun drawBag(c: Canvas) {
        val u = ui.u
        val bag = world.teams[me].bag
        val rows = if (bag.size > 4) 2 else 1
        bagPanel.top = bagPanel.bottom - (if (bag.isEmpty()) 80 * u else 30 * u + rows * 66 * u)
        ui.panel(c, bagPanel, Color.argb(240, 28, 32, 46))
        val sel = world.creature(selectedId)
        ui.text(c, if (sel != null) "Touchez un objet pour le donner à la créature sélectionnée" else "Sélectionnez d'abord une créature",
            bagPanel.left + 10 * u, bagPanel.top + 16 * u, 9.5f, Art.UI_DIM, bold = false)
        bagCells.clear()
        if (bag.isEmpty()) {
            ui.wrap(c, "Le sac est vide. Un nouveau choix d'équipement arrive toutes les 40 secondes.", bagPanel.left + 10 * u, bagPanel.top + 40 * u, bagPanel.width() - 20 * u, 11f, Art.UI_TEXT)
            return
        }
        val cols = 4
        val cw = (bagPanel.width() - 16 * u) / cols
        val chh = 62 * u
        for ((i, item) in bag.withIndex()) {
            val col = i % cols; val row = i / cols
            if (row > 1) break
            val cell = RectF(bagPanel.left + 8 * u + col * cw, bagPanel.top + 24 * u + row * (chh + 4 * u), bagPanel.left + 8 * u + (col + 1) * cw - 4 * u, bagPanel.top + 24 * u + row * (chh + 4 * u) + chh)
            bagCells.add(cell)
            ui.panel(c, cell, Art.UI_BG_LIGHT, radius = 6f)
            Art.icon(c, item.type, cell.centerX(), cell.top + 22 * u, 30 * u)
            ui.text(c, item.type.label, cell.centerX(), cell.bottom - 16 * u, if (item.type.label.length > 10) 8f else 9.5f, Art.UI_TEXT, Paint.Align.CENTER)
            ui.text(c, item.category.label, cell.centerX(), cell.bottom - 5 * u, 8f, Art.UI_DIM, Paint.Align.CENTER, bold = false)
            // remplace quoi ?
            val cur = sel?.itemIn(item.category)
            if (cur != null) ui.text(c, "⇄", cell.right - 8 * u, cell.top + 12 * u, 10f, Art.UI_ACCENT, Paint.Align.CENTER)
        }
    }

    private fun overlayBase(c: Canvas, title: String, color: Int) {
        c.drawColor(Color.argb(150, 0, 0, 0))
        ui.text(c, title, ui.w / 2f, ui.h * 0.26f, 38f, color, Paint.Align.CENTER, shadow = true)
    }

    private fun layoutOverlayButtons(n: Int) {
        val u = ui.u
        val bw = 200 * u; val bh = 42 * u
        val top = ui.h * 0.45f
        for (i in 0 until n) overlayBtns[i].set(ui.w / 2f - bw / 2f, top + i * (bh + 10 * u), ui.w / 2f + bw / 2f, top + i * (bh + 10 * u) + bh)
    }

    private fun drawPause(c: Canvas) {
        overlayBase(c, "Pause", Art.UI_TEXT)
        layoutOverlayButtons(4)
        ui.button(c, overlayBtns[0], "Reprendre", accent = true)
        ui.button(c, overlayBtns[1], "Recommencer")
        ui.button(c, overlayBtns[2], if (app.sound.enabled) "Son : activé" else "Son : coupé")
        ui.button(c, overlayBtns[3], "Menu principal")
        ui.text(c, "${world.map.name} · IA niveau ${config.aiLevel}", ui.w / 2f, ui.h * 0.36f, 12f, Art.UI_DIM, Paint.Align.CENTER)
    }

    private fun drawEnd(c: Canvas) {
        val won = world.winner == me
        overlayBase(c, if (won) "VICTOIRE !" else "DÉFAITE", if (won) Art.UI_ACCENT else Color.rgb(255, 110, 100))
        val t = world.teams[me]
        val min = (world.time / 60).toInt(); val sec = (world.time % 60).toInt()
        val sub = if (won) "Vous avez pris le dernier fort en %d:%02d".format(min, sec)
        else if (world.over) "Les ${TeamNames.names[world.winner]} l'emportent" else "Votre fort est tombé (%d:%02d)".format(min, sec)
        ui.text(c, sub, ui.w / 2f, ui.h * 0.34f, 14f, Art.UI_TEXT, Paint.Align.CENTER)
        ui.text(c, "Victimes : ${t.kills}   ·   Pertes : ${t.deaths}   ·   Créatures reçues : ${t.spawned}", ui.w / 2f, ui.h * 0.40f, 12f, Art.UI_DIM, Paint.Align.CENTER)
        val spect = !world.over
        layoutOverlayButtons(if (spect) 3 else 2)
        ui.button(c, overlayBtns[0], "Rejouer", accent = true)
        ui.button(c, overlayBtns[1], "Menu principal")
        if (spect) ui.button(c, overlayBtns[2], "Regarder la suite")
    }

    // =================================================================== Toucher

    override fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                dragging = false; multi = false
                downOnMinimap = minimap.contains(e.x, e.y) && !paused && overTime < 1.2f
                downOnHud = isOnHud(e.x, e.y)
                if (downOnMinimap) moveCamFromMinimap(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    multi = true
                    pinchDist = dist(e)
                    midX = (e.getX(0) + e.getX(1)) / 2f; midY = (e.getY(0) + e.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2 && multi) {
                    val d = dist(e)
                    val mx = (e.getX(0) + e.getX(1)) / 2f; val myy = (e.getY(0) + e.getY(1)) / 2f
                    if (pinchDist > 10f && d > 10f) cam.zoomBy(d / pinchDist, mx, myy)
                    cam.pan(mx - midX, myy - midY)
                    pinchDist = d; midX = mx; midY = myy
                } else if (!multi) {
                    if (downOnMinimap) { moveCamFromMinimap(e.x, e.y); return }
                    if (!dragging && hypot(e.x - downX, e.y - downY) > 12 * ui.u && !downOnHud) dragging = true
                    if (dragging) cam.pan(e.x - lastX, e.y - lastY)
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && !multi && !downOnMinimap) tap(e.x, e.y)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; multi = false }
        }
    }

    private fun dist(e: MotionEvent): Float = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun moveCamFromMinimap(x: Float, y: Float) {
        val (wx, wy) = renderer.minimapToWorld(x, y)
        cam.camX = wx; cam.camY = wy; cam.clamp()
    }

    private fun isOnHud(x: Float, y: Float): Boolean {
        if (paused || overTime >= 1.2f && !spectating) return true
        if (teamPanel.contains(x, y) || pauseBtn.contains(x, y) || minimap.contains(x, y) || timerRect.contains(x, y)) return true
        if (world.teams[me].eliminated) return false
        if (world.teams[me].pendingOffer != null && offerPanel.contains(x, y)) return true
        if (card.contains(x, y) || bagBtn.contains(x, y)) return true
        if (bagOpen && bagPanel.contains(x, y)) return true
        if (roster.any { it.contains(x, y) }) return true
        if (targeting != null && hintRect.contains(x, y)) return true
        return false
    }

    private fun tap(x: Float, y: Float) {
        // Superpositions
        if (paused) {
            when {
                overlayBtns[0].contains(x, y) -> { paused = false; app.sound.play(SoundFx.S.CLICK) }
                overlayBtns[1].contains(x, y) -> app.startGame(GameConfig(config.mapId, config.aiLevel))
                overlayBtns[2].contains(x, y) -> app.toggleSound()
                overlayBtns[3].contains(x, y) -> app.showMenu()
            }
            return
        }
        if (overTime >= 1.2f && !spectating) {
            when {
                overlayBtns[0].contains(x, y) -> app.startGame(GameConfig(config.mapId, config.aiLevel))
                overlayBtns[1].contains(x, y) -> app.showMenu()
                !world.over && overlayBtns[2].contains(x, y) -> spectating = true
            }
            return
        }
        if (pauseBtn.contains(x, y)) { paused = true; app.sound.play(SoundFx.S.CLICK); return }
        if (minimap.contains(x, y) || teamPanel.contains(x, y) || timerRect.contains(x, y)) return
        if (world.teams[me].eliminated) return

        val team = world.teams[me]
        team.pendingOffer?.let { o ->
            if (offerA.contains(x, y)) { world.chooseOffer(me, o.id, 0); app.sound.play(SoundFx.S.PICKUP); message("${o.options[0].label} ajouté(e) au sac", Art.UI_ACCENT); return }
            if (offerB.contains(x, y)) { world.chooseOffer(me, o.id, 1); app.sound.play(SoundFx.S.PICKUP); message("${o.options[1].label} ajouté(e) au sac", Art.UI_ACCENT); return }
            if (offerPanel.contains(x, y)) return
        }
        if (targeting != null && hintCancel.contains(x, y)) { targeting = null; return }
        if (targeting != null && hintRect.contains(x, y)) return
        if (bagBtn.contains(x, y)) { bagOpen = !bagOpen; app.sound.play(SoundFx.S.CLICK); return }
        if (bagOpen && bagPanel.contains(x, y)) {
            for ((i, cell) in bagCells.withIndex()) if (cell.contains(x, y)) {
                val item = team.bag.getOrNull(i) ?: return
                val sel = world.creature(selectedId)
                if (sel == null) { message("${item.type.label} : ${item.type.description}", Art.UI_TEXT, 4f); return }
                if (world.assignFromBag(me, i, sel.id)) {
                    message("${item.type.label} équipé(e)", Art.UI_ACCENT)
                    if (team.bag.isEmpty()) bagOpen = false
                }
                return
            }
            return
        }
        for ((i, rr) in roster.withIndex()) if (rr.contains(x, y)) {
            val cr = world.aliveOf(me).sortedBy { it.id }.getOrNull(i) ?: return
            val now = System.currentTimeMillis()
            if (cr.id == selectedId && cr.id == lastRosterId && now - lastRosterTap < 600 || cr.id == selectedId) cam.centerOnTile(cr.px, cr.py)
            select(cr)
            lastRosterId = cr.id; lastRosterTap = now
            return
        }
        if (card.contains(x, y)) { cardTap(x, y); return }
        worldTap(x, y)
    }

    private fun select(cr: Creature) {
        selectedId = cr.id; viewEnemyId = 0; targeting = null
        app.sound.play(SoundFx.S.SELECT)
    }

    private fun cardTap(x: Float, y: Float) {
        val sel = world.creature(selectedId) ?: return
        for (i in 0..2) if (slots[i].contains(x, y)) {
            val it = slotItem(sel, i)
            if (it == null) { bagOpen = true; return }
            when (it.type) {
                ItemType.BOMB -> targeting = Targeting.BOMB
                ItemType.REPAIR_KIT -> targeting = Targeting.REPAIR
                ItemType.BARRICADE -> targeting = Targeting.BARRICADE
                ItemType.FREEZE -> targeting = Targeting.FREEZE
                else -> if (it.category == Category.POWER) {
                    if (world.issue(me, Command.UsePower(sel.id))) app.sound.play(SoundFx.S.POWER)
                } else message("${it.type.label} : ${it.type.description}", Art.UI_TEXT, 4f)
            }
            return
        }
    }

    private fun tileAt(x: Float, y: Float): Pos {
        cam.screenToTile(x, y, tmp)
        return Pos(floor(tmp.x + 0.5f).toInt(), floor(tmp.y + 0.5f).toInt())
    }

    private fun worldTap(x: Float, y: Float) {
        val sel = world.creature(selectedId)
        val tile = tileAt(x, y)
        val inside = world.map.inside(tile.x, tile.y)
        val t = targeting
        if (t != null && sel != null) {
            when (t) {
                Targeting.FREEZE -> {
                    val target = renderer.creatureAt(x, y, me)
                    if (target == null || target.team == me) { message("Touchez une créature ennemie", Art.UI_DIM); return }
                    world.issue(me, Command.UsePower(sel.id, target.id))
                    renderer.setMarker(target.px, target.py, Color.rgb(170, 230, 255))
                }
                Targeting.BOMB -> if (inside) {
                    world.issue(me, Command.Bomb(sel.id, tile.x, tile.y))
                    renderer.setMarker(tile.x.toFloat(), tile.y.toFloat(), Color.rgb(255, 150, 40))
                }
                Targeting.REPAIR -> if (inside) {
                    if (world.repairTarget(me, tile.x, tile.y) == null) { message("Aucune brique abîmée près d'ici", Art.UI_DIM); return }
                    world.issue(me, Command.Repair(sel.id, tile.x, tile.y))
                    renderer.setMarker(tile.x.toFloat(), tile.y.toFloat(), Color.rgb(120, 255, 120))
                }
                Targeting.BARRICADE -> if (inside) {
                    if (!world.canPlaceBarricade(tile.x, tile.y)) { message("Impossible de placer une barricade ici", Art.UI_DIM); return }
                    world.issue(me, Command.Barricade(sel.id, tile.x, tile.y))
                    renderer.setMarker(tile.x.toFloat(), tile.y.toFloat(), Color.rgb(200, 150, 90))
                }
            }
            app.sound.play(SoundFx.S.ORDER)
            targeting = null
            return
        }

        val hit = renderer.creatureAt(x, y, me)
        if (hit != null) {
            if (hit.team == me) { select(hit); return }
            if (sel != null) {
                if (world.issue(me, Command.AttackCreature(sel.id, hit.id))) {
                    renderer.setMarker(hit.px, hit.py, Color.rgb(255, 80, 60))
                    app.sound.play(SoundFx.S.ORDER, pitch = 0.8f)
                }
            } else {
                viewEnemyId = hit.id
                app.sound.play(SoundFx.S.CLICK)
            }
            return
        }
        if (!inside) return
        if (sel == null) { viewEnemyId = 0; return }
        if (world.isEnemyStructure(me, tile.x, tile.y)) {
            world.issue(me, Command.AttackTile(sel.id, tile.x, tile.y))
            renderer.setMarker(tile.x.toFloat(), tile.y.toFloat(), Color.rgb(255, 80, 60))
            app.sound.play(SoundFx.S.ORDER, pitch = 0.8f)
            return
        }
        val i = world.map.idx(tile.x, tile.y)
        if (world.map.brickOwner[i] == me && sel.defense?.type == ItemType.REPAIR_KIT && world.repairTarget(me, tile.x, tile.y) != null) {
            world.issue(me, Command.Repair(sel.id, tile.x, tile.y))
            renderer.setMarker(tile.x.toFloat(), tile.y.toFloat(), Color.rgb(120, 255, 120))
            app.sound.play(SoundFx.S.ORDER)
            return
        }
        if (world.issue(me, Command.MoveTo(sel.id, tile.x, tile.y))) {
            renderer.setMarker(tile.x.toFloat(), tile.y.toFloat(), Color.WHITE)
            app.sound.play(SoundFx.S.ORDER)
        }
    }

}
