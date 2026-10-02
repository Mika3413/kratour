package com.kratour.game

import android.graphics.PointF

/**
 * Projection isométrique et caméra (déplacement / zoom).
 * Coordonnées "monde" : pixels isométriques non zoomés ; la case (x, y) est centrée en
 * ((x - y) * TW/2, (x + y) * TH/2).
 */
class IsoCamera {
    companion object {
        const val TW = 64f
        const val TH = 32f
    }

    var camX = 0f
    var camY = 0f
    var zoom = 1f
    var baseZoom = 1f
    var screenW = 1
    var screenH = 1
    var minX = 0f; var maxX = 0f; var minY = 0f; var maxY = 0f

    fun isoX(x: Float, y: Float) = (x - y) * TW / 2f
    fun isoY(x: Float, y: Float) = (x + y) * TH / 2f

    fun toScreenX(wx: Float) = (wx - camX) * zoom + screenW / 2f
    fun toScreenY(wy: Float) = (wy - camY) * zoom + screenH / 2f

    fun screenToWorld(sx: Float, sy: Float, out: PointF): PointF {
        out.x = (sx - screenW / 2f) / zoom + camX
        out.y = (sy - screenH / 2f) / zoom + camY
        return out
    }

    /** Case (fractionnaire) sous un point écran. */
    fun screenToTile(sx: Float, sy: Float, out: PointF): PointF {
        val wx = (sx - screenW / 2f) / zoom + camX
        val wy = (sy - screenH / 2f) / zoom + camY
        val a = wx / (TW / 2f); val b = wy / (TH / 2f)
        out.x = (a + b) / 2f
        out.y = (b - a) / 2f
        return out
    }

    fun setBounds(mapW: Int, mapH: Int) {
        minX = isoX(0f, mapH - 1f); maxX = isoX(mapW - 1f, 0f)
        minY = 0f; maxY = isoY(mapW - 1f, mapH - 1f)
    }

    fun centerOnTile(x: Float, y: Float) {
        camX = isoX(x, y); camY = isoY(x, y); clamp()
    }

    fun pan(dx: Float, dy: Float) {
        camX -= dx / zoom; camY -= dy / zoom; clamp()
    }

    fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val before = PointF(); screenToWorld(fx, fy, before)
        zoom = (zoom * factor).coerceIn(baseZoom * 0.45f, baseZoom * 2.2f)
        val after = PointF(); screenToWorld(fx, fy, after)
        camX += before.x - after.x; camY += before.y - after.y
        clamp()
    }

    fun clamp() {
        camX = camX.coerceIn(minX, maxX)
        camY = camY.coerceIn(minY, maxY)
    }
}
