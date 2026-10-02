package com.kratour.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.kratour.core.Balance
import com.kratour.core.Creature
import com.kratour.core.EventType
import com.kratour.core.GameEvent
import com.kratour.core.GameMap
import com.kratour.core.ItemType
import com.kratour.core.Order
import com.kratour.core.ProjectileKind
import com.kratour.core.Terrain
import com.kratour.core.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Dessine le monde en vue isométrique : terrain pré-rendu, objets triés en profondeur, effets. */
class WorldRenderer(private val world: World, private val cam: IsoCamera, private val viewerTeam: Int) {
    private val map: GameMap = world.map
    private val TW = IsoCamera.TW
    private val TH = IsoCamera.TH
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var time = 0f

    // Terrain pré-rendu
    private val groundOriginX: Float
    private val groundOriginY: Float
    private val ground: Bitmap
    private val CLIFF = 26f

    // Sprites
    private val rocks = List(3) { Sprites.rock(it * 13 + 5) }
    private val trees = List(3) { Sprites.tree(it * 7 + 2) }
    private val bricks = List(4) { t -> List(3) { d -> Sprites.brick(t, d) } }
    private val rubble = List(4) { Sprites.rubble(it) }
    private val barricades = List(4) { Sprites.barricade(it) }
    private val forts = List(4) { Sprites.fort(it, false) }
    private val fortsRuined = List(4) { Sprites.fort(it, true) }

    // Liste de dessin triée par profondeur (pré-allouée)
    private class DrawItem { var key = 0f; var kind = 0; var a = 0; var b = 0; var ref: Any? = null }
    private val items = ArrayList<DrawItem>()
    private var itemCount = 0
    private val sorted = ArrayList<DrawItem>()

    // Effets visuels
    private class Fx(val kind: Int, val x: Float, val y: Float, val life: Float, val color: Int, val text: String = "", val size: Float = 1f) {
        var t = 0f
    }
    private val fx = ArrayList<Fx>()

    // Ordre visuel donné (marqueur)
    var markerX = -1f; var markerY = -1f; var markerT = 0f; var markerColor = Color.WHITE

    init {
        val w = ((map.width + map.height) * TW / 2f).toInt() + 4
        val h = ((map.width + map.height) * TH / 2f + CLIFF).toInt() + 4
        groundOriginX = -map.height * TW / 2f - 2f
        groundOriginY = -TH / 2f - 2f
        ground = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bakeGround(Canvas(ground))
    }

    fun release() { ground.recycle() }

    // =================================================================== Terrain

    private fun tileHash(x: Int, y: Int): Int {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    private fun groundColor(t: Terrain, x: Int, y: Int): Int {
        val v = (tileHash(x, y) and 0xFF) / 255f
        return when (t) {
            Terrain.GRASS, Terrain.TREE, Terrain.ROCK -> Color.rgb((88 + v * 14).toInt(), (152 + v * 20).toInt(), (70 + v * 10).toInt())
            Terrain.DIRT -> Color.rgb((176 + v * 12).toInt(), (146 + v * 10).toInt(), (104 + v * 8).toInt())
            Terrain.SAND -> Color.rgb((222 + v * 10).toInt(), (204 + v * 10).toInt(), (150 + v * 8).toInt())
            Terrain.WATER -> Color.rgb((52 + v * 8).toInt(), (128 + v * 10).toInt(), (196 + v * 10).toInt())
            Terrain.BRIDGE -> Color.rgb((158 + v * 10).toInt(), (112 + v * 8).toInt(), (66 + v * 6).toInt())
            Terrain.FORT -> Color.rgb((150 + v * 10).toInt(), (146 + v * 10).toInt(), (140 + v * 10).toInt())
        }
    }

    private fun diamond(p: Path, cx: Float, cy: Float, s: Float = 1f) {
        p.reset()
        p.moveTo(cx, cy - TH / 2f * s); p.lineTo(cx + TW / 2f * s, cy); p.lineTo(cx, cy + TH / 2f * s); p.lineTo(cx - TW / 2f * s, cy); p.close()
    }

    private fun bakeGround(c: Canvas) {
        c.translate(-groundOriginX, -groundOriginY)
        val p = Path()
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
        // Falaises sur les bords avant (effet "île" 2.5D)
        for (x in 0 until map.width) {
            val y = map.height - 1
            val cx = cam.isoX(x.toFloat(), y.toFloat()); val cy = cam.isoY(x.toFloat(), y.toFloat())
            p.reset(); p.moveTo(cx - TW / 2f, cy); p.lineTo(cx, cy + TH / 2f); p.lineTo(cx, cy + TH / 2f + CLIFF); p.lineTo(cx - TW / 2f, cy + CLIFF); p.close()
            fillP.color = Color.rgb(120, 86, 56); c.drawPath(p, fillP)
        }
        for (y in 0 until map.height) {
            val x = map.width - 1
            val cx = cam.isoX(x.toFloat(), y.toFloat()); val cy = cam.isoY(x.toFloat(), y.toFloat())
            p.reset(); p.moveTo(cx, cy + TH / 2f); p.lineTo(cx + TW / 2f, cy); p.lineTo(cx + TW / 2f, cy + CLIFF); p.lineTo(cx, cy + TH / 2f + CLIFF); p.close()
            fillP.color = Color.rgb(92, 64, 42); c.drawPath(p, fillP)
        }
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val t = map.terrainAt(x, y)
            val cx = cam.isoX(x.toFloat(), y.toFloat()); val cy = cam.isoY(x.toFloat(), y.toFloat())
            diamond(p, cx, cy, 1.02f)
            fillP.color = groundColor(t, x, y)
            c.drawPath(p, fillP)
            val r = Random(tileHash(x, y))
            when (t) {
                Terrain.GRASS -> {
                    if (r.nextInt(3) == 0) {
                        lineP.color = Color.rgb(64, 124, 52); lineP.strokeWidth = 1.2f
                        val ox = r.nextInt(-14, 14).toFloat(); val oy = r.nextInt(-5, 5).toFloat()
                        c.drawLine(cx + ox, cy + oy, cx + ox - 2f, cy + oy - 5f, lineP)
                        c.drawLine(cx + ox, cy + oy, cx + ox + 2f, cy + oy - 5f, lineP)
                    }
                    if (r.nextInt(14) == 0) {
                        fillP.color = if (r.nextBoolean()) Color.rgb(250, 240, 120) else Color.rgb(250, 250, 250)
                        c.drawCircle(cx + r.nextInt(-12, 12), cy + r.nextInt(-4, 4), 1.8f, fillP)
                    }
                }
                Terrain.WATER -> {
                    lineP.color = Color.argb(90, 220, 240, 255); lineP.strokeWidth = 1.2f
                    val ox = r.nextInt(-10, 10).toFloat(); val oy = r.nextInt(-4, 4).toFloat()
                    c.drawLine(cx + ox - 6f, cy + oy, cx + ox + 6f, cy + oy, lineP)
                }
                Terrain.BRIDGE -> {
                    lineP.color = Color.rgb(110, 76, 42); lineP.strokeWidth = 1.2f
                    for (k in -2..2) {
                        val o = k * 6f
                        c.drawLine(cx - TW / 4f + o, cy - TH / 4f + o / 2f, cx + TW / 4f + o, cy + TH / 4f + o / 2f, lineP)
                    }
                }
                Terrain.DIRT, Terrain.SAND -> if (r.nextInt(4) == 0) {
                    fillP.color = Art.shade(groundColor(t, x, y), 0.85f)
                    c.drawCircle(cx + r.nextInt(-12, 12), cy + r.nextInt(-4, 4), 1.6f, fillP)
                }
                Terrain.FORT -> {
                    lineP.color = Color.rgb(110, 106, 100); lineP.strokeWidth = 1f
                    c.drawLine(cx - TW / 4f, cy - TH / 4f, cx + TW / 4f, cy + TH / 4f, lineP)
                    c.drawLine(cx + TW / 4f, cy - TH / 4f, cx - TW / 4f, cy + TH / 4f, lineP)
                }
                else -> Unit
            }
            // Grille discrète pour la lisibilité
            diamond(p, cx, cy)
            lineP.color = Color.argb(28, 0, 0, 0); lineP.strokeWidth = 1f
            c.drawPath(p, lineP)
        }
    }

    // =================================================================== Effets

    fun onEvent(e: GameEvent) {
        when (e.type) {
            EventType.HIT -> {
                fx.add(Fx(0, e.x, e.y, 0.9f, Color.rgb(255, 90, 70), "-" + e.value.toInt()))
                fx.add(Fx(3, e.x, e.y, 0.25f, Color.WHITE))
            }
            EventType.BLOCK -> fx.add(Fx(0, e.x, e.y, 0.8f, Color.rgb(200, 220, 255), "Bloqué"))
            EventType.ABSORB -> fx.add(Fx(0, e.x, e.y, 0.8f, Color.rgb(140, 220, 255), "Absorbé"))
            EventType.EXPLODE -> fx.add(Fx(1, e.x, e.y, 0.6f, Color.rgb(255, 160, 40), size = 1.5f))
            EventType.SHOCKWAVE -> fx.add(Fx(2, e.x, e.y, 0.6f, Color.rgb(160, 210, 255), size = Balance.SHOCK_RADIUS))
            EventType.DEATH -> fx.add(Fx(4, e.x, e.y, 0.9f, Color.rgb(220, 220, 220)))
            EventType.SPAWN -> fx.add(Fx(5, e.x, e.y, 1.2f, Art.TEAM_LIGHT[e.team.coerceIn(0, 3)]))
            EventType.BRICK_BREAK -> fx.add(Fx(4, e.x, e.y, 0.8f, Color.rgb(170, 150, 120)))
            EventType.BRICK_HIT -> if (fx.size < 120) fx.add(Fx(3, e.x, e.y, 0.2f, Color.rgb(230, 210, 170)))
            EventType.BRICK_REPAIR -> fx.add(Fx(0, e.x, e.y, 0.9f, Color.rgb(140, 255, 140), "Réparé"))
            EventType.POWER -> fx.add(Fx(0, e.x, e.y, 1.1f, Art.UI_ACCENT, e.text))
            EventType.FREEZE -> fx.add(Fx(0, e.x, e.y, 1.1f, Color.rgb(170, 230, 255), "Gelé !"))
            EventType.PICKUP -> if (e.text.isNotEmpty()) fx.add(Fx(0, e.x, e.y, 1.0f, Color.rgb(255, 240, 160), "+" + e.text))
            EventType.BONUS -> fx.add(Fx(0, e.x, e.y, 1.0f, Color.rgb(160, 255, 170), e.text))
            EventType.ITEM_BROKEN -> fx.add(Fx(0, e.x, e.y, 1.0f, Color.rgb(200, 200, 200), e.text + " cassé"))
            EventType.FIRST_BLOOD -> fx.add(Fx(0, e.x, e.y, 1.6f, Color.rgb(255, 60, 60), "Premier sang !", 1.4f))
            else -> Unit
        }
    }

    fun update(dt: Float) {
        time += dt
        val it = fx.iterator()
        while (it.hasNext()) { val f = it.next(); f.t += dt; if (f.t >= f.life) it.remove() }
        if (markerT > 0f) markerT -= dt
    }

    fun setMarker(x: Float, y: Float, color: Int) { markerX = x; markerY = y; markerT = 0.8f; markerColor = color }

    // =================================================================== Dessin

    private fun sx(x: Float, y: Float) = cam.toScreenX(cam.isoX(x, y))
    private fun sy(x: Float, y: Float) = cam.toScreenY(cam.isoY(x, y))

    private fun add(key: Float, kind: Int, a: Int = 0, b: Int = 0, ref: Any? = null) {
        if (itemCount >= items.size) items.add(DrawItem())
        val d = items[itemCount++]
        d.key = key; d.kind = kind; d.a = a; d.b = b; d.ref = ref
    }

    private fun onScreen(x: Float, y: Float, margin: Float): Boolean {
        val px = sx(x, y); val py = sy(x, y)
        return px > -margin && px < cam.screenW + margin && py > -margin && py < cam.screenH + margin * 1.5f
    }

    fun draw(c: Canvas, selectedId: Int, highlights: Collection<com.kratour.core.Pos>?, highlightColor: Int) {
        val z = cam.zoom
        // Fond
        c.drawColor(Color.rgb(34, 66, 96))
        // Terrain
        rect.set(
            cam.toScreenX(groundOriginX), cam.toScreenY(groundOriginY),
            cam.toScreenX(groundOriginX + ground.width), cam.toScreenY(groundOriginY + ground.height),
        )
        c.drawBitmap(ground, null, rect, bmpPaint)

        // Reflets animés sur l'eau (légers)
        drawWaterShimmer(c, z)

        // Surbrillances (mode ciblage)
        if (highlights != null) {
            for (p in highlights) {
                val cx = sx(p.x.toFloat(), p.y.toFloat()); val cy = sy(p.x.toFloat(), p.y.toFloat())
                path.reset()
                path.moveTo(cx, cy - TH / 2f * z); path.lineTo(cx + TW / 2f * z, cy); path.lineTo(cx, cy + TH / 2f * z); path.lineTo(cx - TW / 2f * z, cy); path.close()
                c.drawPath(path, Art.fill(Art.withAlpha(highlightColor, 70 + (40 * sin(time * 6f)).toInt())))
            }
        }

        // Gravats (au sol)
        for (y in 0 until map.height) for (x in 0 until map.width) {
            if (!map.isRubble(x, y) || world.barricadeAt(x, y) != null) continue
            if (!onScreen(x.toFloat(), y.toFloat(), 80f)) continue
            rubble[map.brickOwner[map.idx(x, y)].coerceIn(0, 3)].draw(c, sx(x.toFloat(), y.toFloat()), sy(x.toFloat(), y.toFloat()), z, bmpPaint)
        }

        // Chemin de la créature sélectionnée
        world.creature(selectedId)?.let { drawPathPreview(c, it, z) }

        // Marqueur d'ordre
        if (markerT > 0f) {
            val k = markerT / 0.8f
            val cx = sx(markerX, markerY); val cy = sy(markerX, markerY)
            rect.set(cx - TW / 2f * z * k, cy - TH / 2f * z * k, cx + TW / 2f * z * k, cy + TH / 2f * z * k)
            c.drawOval(rect, Art.stroke(Art.withAlpha(markerColor, (255 * k).toInt()), 3f * z))
        }

        // Collecte des objets à trier
        itemCount = 0
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val i = map.idx(x, y)
            val t = map.terrain[i]
            val xf = x.toFloat(); val yf = y.toFloat()
            if (t.tall || map.brickHp[i] > 0f) {
                if (!onScreen(xf, yf, 120f)) continue
                add(xf + yf, 1, x, y)
            }
        }
        for (f in map.forts) {
            val xf = f.x + 0.5f; val yf = f.y + 0.5f
            if (onScreen(xf, yf, 200f)) add(f.x + f.y + 2f, 2, f.team)
        }
        for (b in world.barricades) add(b.x + b.y + 0.05f, 3, ref = b)
        for (g in world.groundItems) add(g.x + g.y + 0.02f, 4, ref = g)
        for (b in map.bonuses) if (b.available) add(b.x + b.y + 0.02f, 5, ref = b)
        for (cr in world.creatures) {
            if (!cr.alive) continue
            if (cr.team != viewerTeam && cr.isInvisible) continue
            if (!onScreen(cr.px, cr.py, 100f)) continue
            add(cr.px + cr.py + 0.1f, 6, ref = cr)
        }
        for (p in world.projectiles) add(p.px + p.py + 0.3f, 7, ref = p)

        sorted.clear()
        for (k in 0 until itemCount) sorted.add(items[k])
        sorted.sortBy { it.key }
        for (d in sorted) {
            when (d.kind) {
                1 -> drawTall(c, d.a, d.b, z)
                2 -> {
                    val f = map.fortOf(d.a)!!
                    val s = if (world.teams[d.a].eliminated) fortsRuined[d.a] else forts[d.a]
                    s.draw(c, sx(f.x + 0.5f, f.y + 0.5f), sy(f.x + 0.5f, f.y + 0.5f), z, bmpPaint)
                }
                3 -> {
                    val b = d.ref as com.kratour.core.Barricade
                    barricades[b.team].draw(c, sx(b.x.toFloat(), b.y.toFloat()), sy(b.x.toFloat(), b.y.toFloat()), z, bmpPaint)
                    if (b.hp < Balance.BARRICADE_HP) hpBar(c, sx(b.x.toFloat(), b.y.toFloat()), sy(b.x.toFloat(), b.y.toFloat()) - 40f * z, z, b.hp / Balance.BARRICADE_HP)
                }
                4 -> {
                    val g = d.ref as com.kratour.core.GroundItem
                    val cx = sx(g.x.toFloat(), g.y.toFloat()); val cy = sy(g.x.toFloat(), g.y.toFloat())
                    val bob = sin(time * 3f + g.id) * 2f * z
                    rect.set(cx - 10f * z, cy - 3f * z, cx + 10f * z, cy + 4f * z)
                    c.drawOval(rect, Art.fill(0x44000000))
                    c.drawCircle(cx, cy - 12f * z + bob, 12f * z, Art.fill(Color.argb(150, 255, 250, 220)))
                    Art.icon(c, g.item.type, cx, cy - 12f * z + bob, 20f * z)
                }
                5 -> {
                    val b = d.ref as com.kratour.core.BonusSpot
                    val cx = sx(b.x.toFloat(), b.y.toFloat()); val cy = sy(b.x.toFloat(), b.y.toFloat())
                    val bob = sin(time * 2.5f + b.x) * 2.5f * z
                    rect.set(cx - 11f * z, cy - 4f * z, cx + 11f * z, cy + 5f * z)
                    c.drawOval(rect, Art.fill(Color.argb(90, 255, 255, 180)))
                    Art.bonusIcon(c, b.type, cx, cy - 13f * z + bob, 20f * z)
                }
                6 -> drawCreature(c, d.ref as Creature, z, (d.ref as Creature).id == selectedId)
                7 -> drawProjectile(c, d.ref as com.kratour.core.Projectile, z)
            }
        }
        drawFx(c, z)
    }

    private fun drawWaterShimmer(c: Canvas, z: Float) {
        if (z < 0.35f) return
        val p = Art.stroke(Color.argb(70, 255, 255, 255), 1.5f * z)
        for (y in 0 until map.height step 2) for (x in 0 until map.width step 2) {
            if (map.terrainAt(x, y) != Terrain.WATER) continue
            if (!onScreen(x.toFloat(), y.toFloat(), 40f)) continue
            val ph = time * 1.5f + (x * 0.7f + y * 0.4f)
            val o = sin(ph) * 6f * z
            val cx = sx(x.toFloat(), y.toFloat()) + o; val cy = sy(x.toFloat(), y.toFloat()) + cos(ph) * 2f * z
            c.drawLine(cx - 5f * z, cy, cx + 5f * z, cy, p)
        }
    }

    private fun drawTall(c: Canvas, x: Int, y: Int, z: Float) {
        val i = map.idx(x, y)
        val cx = sx(x.toFloat(), y.toFloat()); val cy = sy(x.toFloat(), y.toFloat())
        if (map.brickHp[i] > 0f) {
            val r = map.brickHp[i] / Balance.BRICK_HP
            val dmg = if (r > 0.66f) 0 else if (r > 0.33f) 1 else 2
            bricks[map.brickOwner[i].coerceIn(0, 3)][dmg].draw(c, cx, cy, z, bmpPaint)
            if (r < 0.999f) hpBar(c, cx, cy - (Sprites.BRICK_H + 18f) * z, z, r)
            return
        }
        val h = abs(tileHash(x, y))
        when (map.terrain[i]) {
            Terrain.ROCK -> rocks[h % rocks.size].draw(c, cx, cy, z, bmpPaint)
            Terrain.TREE -> trees[h % trees.size].draw(c, cx, cy, z, bmpPaint)
            else -> Unit
        }
    }

    private fun hpBar(c: Canvas, cx: Float, cy: Float, z: Float, ratio: Float) {
        val w = 26f * z; val h = 4f * z
        rect.set(cx - w / 2f - z, cy - z, cx + w / 2f + z, cy + h + z)
        c.drawRect(rect, Art.fill(Color.argb(200, 0, 0, 0)))
        val col = when {
            ratio > 0.6f -> Color.rgb(90, 220, 90)
            ratio > 0.3f -> Color.rgb(240, 200, 60)
            else -> Color.rgb(240, 70, 60)
        }
        rect.set(cx - w / 2f, cy, cx - w / 2f + w * ratio.coerceIn(0f, 1f), cy + h)
        c.drawRect(rect, Art.fill(col))
    }

    private fun drawPathPreview(c: Canvas, cr: Creature, z: Float) {
        if (cr.team != viewerTeam) return
        val pts = cr.path
        if (pts.isEmpty() || cr.pathIdx >= pts.size) return
        val col = Art.withAlpha(Color.WHITE, 140)
        for (k in cr.pathIdx until pts.size) {
            val p = pts[k]
            c.drawCircle(sx(p.x.toFloat(), p.y.toFloat()), sy(p.x.toFloat(), p.y.toFloat()), 2.5f * z, Art.fill(col))
        }
    }

    private fun drawProjectile(c: Canvas, p: com.kratour.core.Projectile, z: Float) {
        val k = (p.t / p.duration).coerceIn(0f, 1f)
        val cx = sx(p.px, p.py); val cy = sy(p.px, p.py)
        if (p.kind == ProjectileKind.BOLT) {
            val ex = sx(p.ex, p.ey); val ey = sy(p.ex, p.ey)
            val dx = ex - sx(p.sx, p.sy); val dy = ey - sy(p.sx, p.sy)
            val len = max(1f, sqrt(dx * dx + dy * dy))
            val ux = dx / len * 10f * z; val uy = dy / len * 10f * z
            val lift = -16f * z
            c.drawLine(cx - ux, cy - uy + lift, cx + ux, cy + uy + lift, Art.stroke(Color.rgb(90, 60, 30), 2.5f * z))
            c.drawCircle(cx + ux, cy + uy + lift, 2f * z, Art.fill(Color.rgb(220, 220, 230)))
        } else {
            val arc = sin(k * Math.PI.toFloat()) * 46f * z
            rect.set(cx - 7f * z, cy - 2.5f * z, cx + 7f * z, cy + 2.5f * z)
            c.drawOval(rect, Art.fill(0x44000000))
            c.drawCircle(cx, cy - arc - 8f * z, 6f * z, Art.fill(Color.rgb(40, 40, 46)))
            c.drawCircle(cx + 3f * z, cy - arc - 14f * z, 2.2f * z, Art.fill(if ((time * 12).toInt() % 2 == 0) Color.YELLOW else Color.rgb(255, 120, 30)))
        }
    }

    // ------------------------------------------------------------------ Créatures

    private fun drawCreature(c: Canvas, cr: Creature, z: Float, selected: Boolean) {
        val gx = sx(cr.px, cr.py); val gy = sy(cr.px, cr.py)
        val team = cr.team.coerceIn(0, 3)
        val col = Art.TEAM_COLORS[team]
        val dark = Art.TEAM_DARK[team]
        val light = Art.TEAM_LIGHT[team]
        val ghost = cr.isInvisible
        if (ghost) c.saveLayerAlpha(gx - 40f * z, gy - 70f * z, gx + 40f * z, gy + 20f * z, 95)

        // Direction de regard à l'écran
        var dx = (cr.fdx - cr.fdy).toFloat(); var dy = (cr.fdx + cr.fdy) * 0.5f
        val dl = sqrt(dx * dx + dy * dy)
        if (dl > 0f) { dx /= dl; dy /= dl } else { dx = 0f; dy = 1f }
        val moving = cr.isMoving
        val phase = if (moving) time * 12f + cr.id else 0f
        val bob = if (moving) -abs(sin(phase)) * 3f * z else sin(time * 2f + cr.id) * 0.8f * z

        // Ombre / sélection
        rect.set(gx - 14f * z, gy - 4f * z, gx + 14f * z, gy + 5f * z)
        c.drawOval(rect, Art.fill(0x50000000))
        if (selected) {
            val pulse = 1f + 0.08f * sin(time * 6f)
            rect.set(gx - 19f * z * pulse, gy - 7f * z * pulse, gx + 19f * z * pulse, gy + 8f * z * pulse)
            c.drawOval(rect, Art.stroke(Color.rgb(255, 240, 120), 2.5f * z))
        } else if (cr.team == viewerTeam) {
            rect.set(gx - 16f * z, gy - 5.5f * z, gx + 16f * z, gy + 6.5f * z)
            c.drawOval(rect, Art.stroke(Art.withAlpha(col, 150), 1.5f * z))
        }

        // Aura de force
        if (cr.forceTimer > 0f) c.drawCircle(gx, gy - 16f * z + bob, 19f * z + sin(time * 10f) * z, Art.fill(Color.argb(70, 255, 80, 30)))
        // Traînée de vitesse
        if (cr.speedTimer > 0f && moving) {
            val sp = Art.stroke(Color.argb(150, 160, 255, 170), 1.6f * z)
            for (k in 0..2) c.drawLine(gx - dx * (16f + k * 4) * z, gy - (10f + k * 5) * z, gx - dx * (26f + k * 4) * z, gy - (10f + k * 5) * z - dy * 6f * z, sp)
        }

        val bodyY = gy - 16f * z + bob
        val behindWeapon = dy < -0.2f
        if (behindWeapon) drawHeld(c, cr, gx, bodyY, dx, dy, z)
        if (cr.defense?.type == ItemType.REPAIR_KIT || cr.defense?.type == ItemType.BARRICADE) {
            Art.icon(c, cr.defense!!.type, gx - dx * 11f * z, bodyY - 4f * z, 13f * z)
        }

        // Pieds
        val f1 = if (moving) sin(phase) * 3f * z else 0f
        rect.set(gx - 10f * z, gy - 4f * z + f1 * 0.3f, gx - 2f * z, gy + 1.5f * z + f1 * 0.3f)
        c.drawOval(rect, Art.fill(dark))
        rect.set(gx + 2f * z, gy - 4f * z - f1 * 0.3f, gx + 10f * z, gy + 1.5f * z - f1 * 0.3f)
        c.drawOval(rect, Art.fill(dark))

        // Corps (galet arrondi)
        rect.set(gx - 13f * z, bodyY - 14f * z, gx + 13f * z, bodyY + 13f * z)
        c.drawOval(rect, Art.fill(col))
        rect.set(gx - 9f * z + dx * 2f * z, bodyY - 1f * z, gx + 9f * z + dx * 2f * z, bodyY + 12f * z)
        c.drawOval(rect, Art.fill(Art.withAlpha(light, 170)))
        rect.set(gx - 13f * z, bodyY - 14f * z, gx + 13f * z, bodyY + 13f * z)
        c.drawOval(rect, Art.stroke(dark, 1.6f * z))
        // Crête / pousse sur la tête (signature des Kratons)
        path.reset()
        path.moveTo(gx - 2f * z, bodyY - 13f * z)
        path.quadTo(gx - 6f * z, bodyY - 24f * z, gx + 3f * z, bodyY - 22f * z)
        path.quadTo(gx + 1f * z, bodyY - 17f * z, gx + 2f * z, bodyY - 13f * z)
        path.close()
        c.drawPath(path, Art.fill(light))
        c.drawPath(path, Art.stroke(dark, 1.1f * z))
        // Armure
        if (cr.defense?.type == ItemType.ARMOR) {
            rect.set(gx - 12f * z, bodyY - 4f * z, gx + 12f * z, bodyY + 12f * z)
            c.drawArc(rect, 0f, 180f, true, Art.fill(Color.rgb(150, 158, 176)))
            c.drawArc(rect, 0f, 180f, true, Art.stroke(Color.rgb(80, 86, 100), 1.2f * z))
            c.drawLine(gx - 10f * z, bodyY + 4f * z, gx + 10f * z, bodyY + 4f * z, Art.stroke(Color.rgb(100, 106, 120), 1f * z))
        }
        // Yeux
        if (dy > -0.6f) {
            val ex = dx * 4f * z; val ey = -3f * z + dy * 1.5f * z
            for (s in listOf(-1f, 1f)) {
                val exx = gx + s * 4.5f * z + ex
                c.drawCircle(exx, bodyY + ey, 3.6f * z, Art.fill(Color.WHITE))
                c.drawCircle(exx + dx * 1.3f * z, bodyY + ey + dy * 1.2f * z, 1.8f * z, Art.fill(Color.rgb(20, 20, 30)))
            }
            if (cr.forceTimer > 0f || cr.autoTargetId != 0 || cr.order is Order.Attack) {
                // Sourcils froncés au combat
                val br = Art.stroke(dark, 1.4f * z)
                c.drawLine(gx - 7.5f * z + ex, bodyY + ey - 5f * z, gx - 2f * z + ex, bodyY + ey - 3.5f * z, br)
                c.drawLine(gx + 7.5f * z + ex, bodyY + ey - 5f * z, gx + 2f * z + ex, bodyY + ey - 3.5f * z, br)
            }
        }
        if (!behindWeapon) drawHeld(c, cr, gx, bodyY, dx, dy, z)

        // Bulle
        if (cr.defense?.type == ItemType.BUBBLE) {
            c.drawCircle(gx, bodyY - 2f * z, 21f * z, Art.fill(Color.argb(55, 140, 210, 255)))
            c.drawCircle(gx, bodyY - 2f * z, 21f * z, Art.stroke(Color.argb(160, 190, 235, 255), 1.4f * z))
            c.drawCircle(gx - 9f * z, bodyY - 12f * z, 3f * z, Art.fill(Color.argb(180, 255, 255, 255)))
        }
        // Gel
        if (cr.frozenTimer > 0f) {
            rect.set(gx - 16f * z, bodyY - 26f * z, gx + 16f * z, gy + 4f * z)
            c.drawRoundRect(rect, 4f * z, 4f * z, Art.fill(Color.argb(130, 170, 230, 255)))
            c.drawRoundRect(rect, 4f * z, 4f * z, Art.stroke(Color.argb(220, 230, 250, 255), 1.4f * z))
        }
        // Étourdi
        if (cr.stunTimer > 0f) {
            for (k in 0..2) {
                val a = time * 6f + k * 2.1f
                c.drawCircle(gx + cos(a) * 10f * z, bodyY - 22f * z + sin(a) * 3f * z, 2f * z, Art.fill(Color.rgb(255, 240, 90)))
            }
        }
        // Pouvoir en réserve
        cr.power?.let {
            c.drawCircle(gx + 13f * z, bodyY - 22f * z, 7f * z, Art.fill(Color.argb(200, 30, 30, 50)))
            Art.icon(c, it.type, gx + 13f * z, bodyY - 22f * z, 11f * z)
        }
        // PV
        hpBar(c, gx, bodyY - 34f * z, z, cr.hp / Balance.MAX_HP)
        if (ghost) c.restore()
    }

    private fun drawHeld(c: Canvas, cr: Creature, gx: Float, bodyY: Float, dx: Float, dy: Float, z: Float) {
        val side = if (dx >= 0f) 1f else -1f
        cr.weapon?.let {
            val swing = if (cr.attackCooldown > cr.weaponStats.cooldown - 0.2f) 8f * z else 0f
            val hx = gx + side * 13f * z + dx * swing; val hy = bodyY + 2f * z + dy * swing * 0.5f
            c.save()
            if (side < 0f) c.scale(-1f, 1f, hx, hy)
            Art.icon(c, it.type, hx, hy - 6f * z, 20f * z)
            c.restore()
        }
        if (cr.defense?.type == ItemType.SHIELD) {
            Art.icon(c, ItemType.SHIELD, gx + dx * 11f * z - side * 4f * z, bodyY + 3f * z + dy * 4f * z, 15f * z)
        }
    }

    private fun drawFx(c: Canvas, z: Float) {
        for (f in fx) {
            val k = f.t / f.life
            val cx = sx(f.x, f.y); val cy = sy(f.x, f.y)
            when (f.kind) {
                0 -> {
                    val a = ((1f - k) * 255).toInt()
                    paint.reset(); paint.isAntiAlias = true
                    paint.textSize = 13f * z * f.size; paint.textAlign = Paint.Align.CENTER
                    paint.isFakeBoldText = true
                    val ty = cy - 40f * z - k * 22f * z
                    paint.color = Color.argb(a, 0, 0, 0)
                    c.drawText(f.text, cx + 1.2f * z, ty + 1.2f * z, paint)
                    paint.color = Art.withAlpha(f.color, a)
                    c.drawText(f.text, cx, ty, paint)
                }
                1 -> {
                    val r = f.size * TW * 0.55f * z * (0.4f + k)
                    rect.set(cx - r, cy - r * 0.5f, cx + r, cy + r * 0.5f)
                    c.drawOval(rect, Art.fill(Color.argb(((1 - k) * 200).toInt(), 255, 140, 40)))
                    c.drawCircle(cx, cy - 14f * z * (1 + k), r * 0.45f * (1 - k), Art.fill(Color.argb(((1 - k) * 230).toInt(), 255, 230, 120)))
                    c.drawCircle(cx, cy - 30f * z * k - 8f * z, r * 0.35f, Art.fill(Color.argb(((1 - k) * 120).toInt(), 80, 80, 80)))
                }
                2 -> {
                    val r = f.size * TW * 0.5f * z * k
                    rect.set(cx - r, cy - r * 0.5f, cx + r, cy + r * 0.5f)
                    c.drawOval(rect, Art.stroke(Color.argb(((1 - k) * 255).toInt(), 170, 220, 255), 4f * z))
                }
                3 -> {
                    val sp = Art.stroke(Art.withAlpha(f.color, ((1 - k) * 255).toInt()), 1.5f * z)
                    for (n in 0..4) {
                        val a = n * 1.256f + f.x
                        val r1 = 4f * z + k * 10f * z
                        c.drawLine(cx + cos(a) * r1, cy - 16f * z + sin(a) * r1, cx + cos(a) * (r1 + 5f * z), cy - 16f * z + sin(a) * (r1 + 5f * z), sp)
                    }
                }
                4 -> {
                    for (n in 0..5) {
                        val a = n * 1.05f
                        val r1 = k * 16f * z
                        c.drawCircle(cx + cos(a) * r1, cy - 10f * z - k * 14f * z + sin(a) * r1 * 0.5f, (6f - 4f * k) * z, Art.fill(Art.withAlpha(f.color, ((1 - k) * 200).toInt())))
                    }
                }
                5 -> {
                    rect.set(cx - 14f * z, cy - 80f * z * (1 - k * 0.3f), cx + 14f * z, cy + 4f * z)
                    c.drawRoundRect(rect, 14f * z, 14f * z, Art.fill(Art.withAlpha(f.color, ((1 - k) * 140).toInt())))
                }
            }
        }
    }

    // ------------------------------------------------------------------ Mini-carte

    private val mmRect = RectF()

    /** Dessine la mini-carte dans [r] (terrain, forts, créatures visibles, vue caméra). */
    fun drawMinimap(c: Canvas, r: RectF) {
        val k = min(r.width() / ground.width, r.height() / ground.height)
        val w = ground.width * k; val h = ground.height * k
        mmRect.set(r.centerX() - w / 2f, r.centerY() - h / 2f, r.centerX() + w / 2f, r.centerY() + h / 2f)
        c.drawBitmap(ground, null, mmRect, bmpPaint)
        fun mx(x: Float, y: Float) = mmRect.left + (cam.isoX(x, y) - groundOriginX) * k
        fun my(x: Float, y: Float) = mmRect.top + (cam.isoY(x, y) - groundOriginY) * k
        for (f in map.forts) {
            val col = if (world.teams[f.team].eliminated) Color.GRAY else Art.TEAM_COLORS[f.team]
            val x = mx(f.x + 0.5f, f.y + 0.5f); val y = my(f.x + 0.5f, f.y + 0.5f)
            rect.set(x - 4f, y - 4f, x + 4f, y + 4f)
            c.drawRect(rect, Art.fill(col)); c.drawRect(rect, Art.stroke(Color.WHITE, 1f))
        }
        for (cr in world.creatures) {
            if (!cr.alive || (cr.team != viewerTeam && cr.isInvisible)) continue
            c.drawCircle(mx(cr.px, cr.py), my(cr.px, cr.py), 2.6f, Art.fill(Art.TEAM_COLORS[cr.team.coerceIn(0, 3)]))
        }
        // Rectangle de la vue
        val hw = cam.screenW / 2f / cam.zoom; val hh = cam.screenH / 2f / cam.zoom
        rect.set(
            mmRect.left + (cam.camX - hw - groundOriginX) * k, mmRect.top + (cam.camY - hh - groundOriginY) * k,
            mmRect.left + (cam.camX + hw - groundOriginX) * k, mmRect.top + (cam.camY + hh - groundOriginY) * k,
        )
        c.save(); c.clipRect(r)
        c.drawRect(rect, Art.stroke(Color.WHITE, 1.2f))
        c.restore()
    }

    /** Point de la mini-carte -> position monde (pixels iso) pour centrer la caméra. */
    fun minimapToWorld(px: Float, py: Float): Pair<Float, Float> {
        val k = mmRect.width() / ground.width
        if (k <= 0f) return Pair(cam.camX, cam.camY)
        return Pair((px - mmRect.left) / k + groundOriginX, (py - mmRect.top) / k + groundOriginY)
    }

    /** Créature affichée sous un point écran (zone de toucher généreuse). */
    fun creatureAt(sxp: Float, syp: Float, team: Int): Creature? {
        var best: Creature? = null
        var bestD = Float.MAX_VALUE
        val z = cam.zoom
        val radius = max(30f * z, 26f * cam.baseZoom)
        for (cr in world.creatures) {
            if (!cr.alive) continue
            if (cr.team != team && cr.isInvisible) continue
            val cx = sx(cr.px, cr.py); val cy = sy(cr.px, cr.py) - 16f * z
            val ddx = cx - sxp; val ddy = cy - syp
            val d = sqrt(ddx * ddx + ddy * ddy)
            if (d < radius && d < bestD) { bestD = d; best = cr }
        }
        return best
    }

    fun minDim() = min(cam.screenW, cam.screenH)
}
