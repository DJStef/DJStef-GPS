package fr.djstef.gps

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Voitures vues du dessus, dessinées avec ombre, reflets et vitres pour un rendu en relief.
 * Posées à plat sur la carte, elles prennent la perspective quand la carte est inclinée.
 */
object CarSprites {
    val NAMES = listOf("Citadine", "Berline", "SUV", "Sportive", "Utilitaire")
    val COLORS = listOf(
        "#E53935", "#FF9F1C", "#FDD835", "#43A047", "#1E88E5", "#F5F5F5", "#212121", "#B0BEC5"
    )

    private const val W = 180
    private const val H = 320

    private fun shade(color: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * f).coerceIn(0f, 1f)
        if (f > 1f) hsv[1] = (hsv[1] / f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun paint(color: Int): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        return p
    }

    /**
     * Voiture vue de derrière (comme dans un GPS en 3D) : sprite « debout » qui reste face à la
     * caméra quand la carte est inclinée. Le point d'ancrage est le bas de la voiture.
     */
    fun drawRear(model: Int, color: Int): Bitmap {
        val w = 240
        val h = 190
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.density = 480
        val c = Canvas(bmp)
        val cx = w / 2f
        val ground = h - 16f

        // largeur, hauteur du bas de caisse, largeur de l'habitacle, hauteur de l'habitacle
        val dims = when (model) {
            0 -> floatArrayOf(150f, 62f, 112f, 58f)
            1 -> floatArrayOf(168f, 56f, 128f, 50f)
            2 -> floatArrayOf(176f, 76f, 144f, 60f)
            3 -> floatArrayOf(180f, 50f, 124f, 38f)
            else -> floatArrayOf(166f, 128f, 0f, 0f)
        }
        val bw = dims[0]
        val lowH = dims[1]
        val cabW = dims[2]
        val cabH = dims[3]
        val bodyBottom = ground - 14f
        val bodyTop = bodyBottom - lowH

        // Ombre au sol
        val sh = paint(0x77000000)
        sh.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
        c.drawOval(RectF(cx - bw / 2 - 8, ground - 16, cx + bw / 2 + 8, ground + 10), sh)

        // Roues
        val wheel = paint(0xFF0E0E0E.toInt())
        c.drawRoundRect(RectF(cx - bw / 2 + 12, bodyBottom - 18, cx - bw / 2 + 48, ground), 8f, 8f, wheel)
        c.drawRoundRect(RectF(cx + bw / 2 - 48, bodyBottom - 18, cx + bw / 2 - 12, ground), 8f, 8f, wheel)

        // Habitacle (trapèze) avec lunette arrière
        if (cabH > 0f) {
            val cabTop = bodyTop - cabH
            val path = android.graphics.Path()
            path.moveTo(cx - cabW / 2, bodyTop + 8)
            path.lineTo(cx - cabW * 0.40f, cabTop)
            path.lineTo(cx + cabW * 0.40f, cabTop)
            path.lineTo(cx + cabW / 2, bodyTop + 8)
            path.close()
            val cp = Paint(Paint.ANTI_ALIAS_FLAG)
            cp.shader = LinearGradient(0f, cabTop, 0f, bodyTop, shade(color, 1.25f), shade(color, 0.85f), Shader.TileMode.CLAMP)
            c.drawPath(path, cp)
            val gl = android.graphics.Path()
            gl.moveTo(cx - cabW * 0.44f, bodyTop + 2)
            gl.lineTo(cx - cabW * 0.35f, cabTop + 9)
            gl.lineTo(cx + cabW * 0.35f, cabTop + 9)
            gl.lineTo(cx + cabW * 0.44f, bodyTop + 2)
            gl.close()
            val gp = Paint(Paint.ANTI_ALIAS_FLAG)
            gp.shader = LinearGradient(0f, cabTop, 0f, bodyTop, 0xFF3B4A5C.toInt(), 0xFF111A24.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(gl, gp)
            if (model == 3) { // aileron
                c.drawRoundRect(RectF(cx - cabW * 0.5f, bodyTop - 4, cx + cabW * 0.5f, bodyTop + 4), 4f, 4f, paint(shade(color, 0.55f)))
            }
        }

        // Caisse
        val bp = Paint(Paint.ANTI_ALIAS_FLAG)
        bp.shader = LinearGradient(0f, bodyTop, 0f, bodyBottom, shade(color, 1.22f), shade(color, 0.68f), Shader.TileMode.CLAMP)
        val body = RectF(cx - bw / 2, bodyTop, cx + bw / 2, bodyBottom)
        c.drawRoundRect(body, if (model == 4) 16f else 26f, if (model == 4) 16f else 26f, bp)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG)
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = 3f
        edge.color = shade(color, 0.4f)
        c.drawRoundRect(body, if (model == 4) 16f else 26f, if (model == 4) 16f else 26f, edge)

        if (model == 4) { // utilitaire : deux vitres de portes arrière
            val gp = paint(0xFF1E2A38.toInt())
            c.drawRoundRect(RectF(cx - bw / 2 + 16, bodyTop + 14, cx - 6, bodyTop + 50), 6f, 6f, gp)
            c.drawRoundRect(RectF(cx + 6, bodyTop + 14, cx + bw / 2 - 16, bodyTop + 50), 6f, 6f, gp)
            c.drawRect(cx - 1.5f, bodyTop + 10, cx + 1.5f, bodyBottom - 22, paint(shade(color, 0.5f)))
        }

        // Feux arrière
        val tail = paint(0xFFE53935.toInt())
        val ty = if (model == 4) bodyBottom - 52 else bodyTop + 10
        c.drawRoundRect(RectF(cx - bw / 2 + 6, ty, cx - bw / 2 + 42, ty + 18), 6f, 6f, tail)
        c.drawRoundRect(RectF(cx + bw / 2 - 42, ty, cx + bw / 2 - 6, ty + 18), 6f, 6f, tail)
        if (model == 3) c.drawRect(cx - bw / 2 + 42, ty + 6, cx + bw / 2 - 42, ty + 12, paint(0xFFB71C1C.toInt()))

        // Plaque et pare-chocs
        c.drawRoundRect(RectF(cx - 26, bodyBottom - 30, cx + 26, bodyBottom - 12), 4f, 4f, paint(0xFFF5F5F5.toInt()))
        c.drawRoundRect(RectF(cx - bw / 2 + 10, bodyBottom - 10, cx + bw / 2 - 10, bodyBottom + 2), 6f, 6f, paint(shade(color, 0.4f)))
        return bmp
    }

    fun draw(model: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        bmp.density = 480 // 3x : taille indépendante de l'écran
        val c = Canvas(bmp)
        val cx = W / 2f
        val cy = H / 2f

        // largeur, longueur, arrondi du corps
        val (bw, bh, cr) = when (model) {
            0 -> Triple(96f, 176f, 36f)
            1 -> Triple(100f, 232f, 30f)
            2 -> Triple(112f, 224f, 26f)
            3 -> Triple(112f, 220f, 40f)
            else -> Triple(108f, 262f, 16f)
        }
        val body = RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)

        // Ombre portée
        val sh = paint(0x66000000)
        sh.maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(body.left + 6, body.top + 10, body.right + 10, body.bottom + 14), cr, cr, sh)

        // Roues
        val wheel = paint(0xFF111111.toInt())
        val wy = bh * 0.29f
        for (sy in listOf(-wy, wy)) {
            c.drawRoundRect(RectF(body.left - 7, cy + sy - 20, body.left + 12, cy + sy + 20), 6f, 6f, wheel)
            c.drawRoundRect(RectF(body.right - 12, cy + sy - 20, body.right + 7, cy + sy + 20), 6f, 6f, wheel)
        }

        // Carrosserie (dégradé latéral : bords sombres, centre éclairé)
        val bp = Paint(Paint.ANTI_ALIAS_FLAG)
        bp.shader = LinearGradient(
            body.left, 0f, body.right, 0f,
            intArrayOf(shade(color, 0.62f), shade(color, 1.18f), shade(color, 0.62f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(body, cr, cr, bp)

        // Liseré
        val edge = Paint(Paint.ANTI_ALIAS_FLAG)
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = 3f
        edge.color = shade(color, 0.45f)
        c.drawRoundRect(body, cr, cr, edge)

        // Bandes (sportive)
        if (model == 3) {
            val st = paint(0xE6FFFFFF.toInt())
            c.drawRect(cx - 14, body.top + 10, cx - 5, body.bottom - 10, st)
            c.drawRect(cx + 5, body.top + 10, cx + 14, body.bottom - 10, st)
        }

        // Vitres et toit : (début, fin) en fraction de la longueur, de l'avant (haut) vers l'arrière
        val q = when (model) {
            0 -> listOf(0.24f, 0.40f, 0.40f, 0.66f, 0.66f, 0.78f)
            1 -> listOf(0.25f, 0.38f, 0.38f, 0.62f, 0.62f, 0.72f)
            2 -> listOf(0.22f, 0.36f, 0.36f, 0.72f, 0.72f, 0.82f)
            3 -> listOf(0.34f, 0.46f, 0.46f, 0.62f, 0.62f, 0.72f)
            else -> listOf(0.14f, 0.28f, 0.28f, 0.92f, 0.92f, 0.94f)
        }
        val ws0 = q[0]
        val ws1 = q[1]
        val r0 = q[2]
        val r1 = q[3]
        val rw0 = q[4]
        val rw1 = q[5]
        val gw = bw * 0.40f
        val glass = Paint(Paint.ANTI_ALIAS_FLAG)
        glass.shader = LinearGradient(0f, body.top, 0f, body.bottom, 0xFF16202B.toInt(), 0xFF3B4A5C.toInt(), Shader.TileMode.CLAMP)
        // pare-brise
        c.drawRoundRect(RectF(cx - gw, body.top + bh * ws0, cx + gw, body.top + bh * ws1), 10f, 10f, glass)
        // lunette arrière
        if (model != 4) {
            c.drawRoundRect(RectF(cx - gw * 0.92f, body.top + bh * rw0, cx + gw * 0.92f, body.top + bh * rw1), 10f, 10f, glass)
        }
        // toit
        val roof = Paint(Paint.ANTI_ALIAS_FLAG)
        val roofRect = RectF(cx - gw, body.top + bh * r0, cx + gw, body.top + bh * r1)
        roof.shader = LinearGradient(
            roofRect.left, 0f, roofRect.right, 0f,
            intArrayOf(shade(color, 0.85f), shade(color, 1.3f), shade(color, 0.85f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(roofRect, 12f, 12f, roof)
        if (model == 2) { // barres de toit
            val rail = paint(shade(color, 0.4f))
            c.drawRect(roofRect.left + 6, roofRect.top + 8, roofRect.left + 11, roofRect.bottom - 8, rail)
            c.drawRect(roofRect.right - 11, roofRect.top + 8, roofRect.right - 6, roofRect.bottom - 8, rail)
        }
        if (model == 4) { // nervures du toit de l'utilitaire
            val rib = paint(shade(color, 0.7f))
            var y = roofRect.top + 26
            while (y < roofRect.bottom - 10) {
                c.drawRect(roofRect.left + 10, y, roofRect.right - 10, y + 3, rib)
                y += 34
            }
        }

        // Capot : reflet léger
        val hood = paint(0x22FFFFFF)
        c.drawRoundRect(RectF(cx - bw * 0.30f, body.top + 14, cx + bw * 0.30f, body.top + bh * ws0 - 6), 14f, 14f, hood)

        // Phares et feux arrière
        val head = paint(0xFFFFF59D.toInt())
        c.drawRoundRect(RectF(body.left + 8, body.top + 5, body.left + 30, body.top + 16), 5f, 5f, head)
        c.drawRoundRect(RectF(body.right - 30, body.top + 5, body.right - 8, body.top + 16), 5f, 5f, head)
        val tail = paint(0xFFD32F2F.toInt())
        c.drawRoundRect(RectF(body.left + 8, body.bottom - 15, body.left + 32, body.bottom - 5), 4f, 4f, tail)
        c.drawRoundRect(RectF(body.right - 32, body.bottom - 15, body.right - 8, body.bottom - 5), 4f, 4f, tail)

        // Rétroviseurs
        val mir = paint(shade(color, 0.6f))
        c.drawOval(RectF(body.left - 11, body.top + bh * ws1 - 14, body.left + 3, body.top + bh * ws1 + 4), mir)
        c.drawOval(RectF(body.right - 3, body.top + bh * ws1 - 14, body.right + 11, body.top + bh * ws1 + 4), mir)
        return bmp
    }
}
