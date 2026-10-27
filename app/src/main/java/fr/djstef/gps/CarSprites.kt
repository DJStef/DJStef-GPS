package fr.djstef.gps

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Voitures dessinées en haute définition (ombres, reflets, vitres, jantes, feux, plaque).
 * - drawRear : vue de derrière, « debout » face à la caméra quand la carte est en 3D.
 * - draw : vue du dessus, posée sur la carte en 2D.
 * Les dessins sont faits à l'échelle x2 pour rester nets quand on les agrandit.
 */
object CarSprites {
    val NAMES = listOf("Citadine", "Berline", "SUV", "Sportive", "Utilitaire")
    val COLORS = listOf(
        "#E53935", "#FF9F1C", "#FDD835", "#43A047", "#1E88E5", "#F5F5F5", "#212121", "#B0BEC5"
    )

    private const val SCALE = 2f

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

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, p: Paint) =
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)

    private fun vGrad(top: Float, bottom: Float, c1: Int, c2: Int): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, top, 0f, bottom, c1, c2, Shader.TileMode.CLAMP)
        return p
    }

    private fun hGrad(left: Float, right: Float, vararg colors: Int): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pos = FloatArray(colors.size) { it.toFloat() / (colors.size - 1) }
        p.shader = LinearGradient(left, 0f, right, 0f, colors, pos, Shader.TileMode.CLAMP)
        return p
    }

    /** Bande de reflet diagonale, limitée au rectangle donné. */
    private fun sheen(c: Canvas, area: RectF, rad: Float, strength: Int = 0x55) {
        val clip = Path()
        clip.addRoundRect(area, rad, rad, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val a = (strength shl 24) or 0xFFFFFF
        p.shader = LinearGradient(
            area.left, area.top, area.right, area.bottom,
            intArrayOf(0x00FFFFFF, a, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0.15f, 0.30f, 0.46f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(area, p)
        c.restore()
    }

    private fun glass(top: Float, bottom: Float): Paint =
        vGrad(top, bottom, 0xFF3F5166.toInt(), 0xFF0F1822.toInt())

    // =====================================================================
    //  Vue de derrière (3D)
    // =====================================================================
    fun drawRear(model: Int, color: Int): Bitmap {
        val w = 240
        val h = 190
        val bmp = Bitmap.createBitmap((w * SCALE).toInt(), (h * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        bmp.density = 960
        val c = Canvas(bmp)
        c.scale(SCALE, SCALE)
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
        val cabTop = bodyTop - cabH
        val van = model == 4

        // --- Ombre au sol (contact + diffuse) ---
        val sh = paint(0x88000000.toInt())
        sh.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        c.drawOval(RectF(cx - bw / 2 - 10, ground - 14, cx + bw / 2 + 10, ground + 10), sh)
        val sh2 = paint(0xAA000000.toInt())
        sh2.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
        c.drawOval(RectF(cx - bw / 2 + 4, ground - 8, cx + bw / 2 - 4, ground + 4), sh2)

        // --- Passages de roues et roues ---
        val arch = paint(0xFF05070A.toInt())
        val wheel = vGrad(bodyBottom - 20, ground, 0xFF2B2B2B.toInt(), 0xFF050505.toInt())
        val lx0 = cx - bw / 2 + 10
        val rx1 = cx + bw / 2 - 10
        rr(c, lx0 - 2, bodyBottom - 26, lx0 + 44, bodyBottom - 2, 12f, arch)
        rr(c, rx1 - 44, bodyBottom - 26, rx1 + 2, bodyBottom - 2, 12f, arch)
        rr(c, lx0 + 2, bodyBottom - 20, lx0 + 40, ground, 8f, wheel)
        rr(c, rx1 - 40, bodyBottom - 20, rx1 - 2, ground, 8f, wheel)
        // reflet sur le flanc de pneu
        val tireHi = paint(0x33FFFFFF)
        rr(c, lx0 + 5, bodyBottom - 18, lx0 + 12, ground - 3, 3f, tireHi)
        rr(c, rx1 - 12, bodyBottom - 18, rx1 - 5, ground - 3, 3f, tireHi)

        // --- Habitacle ---
        if (!van) {
            val path = Path()
            path.moveTo(cx - cabW / 2, bodyTop + 8)
            path.lineTo(cx - cabW * 0.40f, cabTop)
            path.lineTo(cx + cabW * 0.40f, cabTop)
            path.lineTo(cx + cabW / 2, bodyTop + 8)
            path.close()
            val cp = Paint(Paint.ANTI_ALIAS_FLAG)
            cp.shader = LinearGradient(0f, cabTop, 0f, bodyTop, shade(color, 1.35f), shade(color, 0.8f), Shader.TileMode.CLAMP)
            c.drawPath(path, cp)
            val edgeP = Paint(Paint.ANTI_ALIAS_FLAG)
            edgeP.style = Paint.Style.STROKE
            edgeP.strokeWidth = 1.6f
            edgeP.color = shade(color, 0.4f)
            c.drawPath(path, edgeP)

            // Lunette arrière
            val gl = Path()
            gl.moveTo(cx - cabW * 0.44f, bodyTop + 3)
            gl.lineTo(cx - cabW * 0.35f, cabTop + 8)
            gl.lineTo(cx + cabW * 0.35f, cabTop + 8)
            gl.lineTo(cx + cabW * 0.44f, bodyTop + 3)
            gl.close()
            c.drawPath(gl, glass(cabTop, bodyTop))
            c.save()
            c.clipPath(gl)
            // dégivrage
            val defrost = paint(0x33FF8A80)
            var yy = cabTop + 14
            while (yy < bodyTop - 4) {
                c.drawRect(cx - cabW * 0.45f, yy, cx + cabW * 0.45f, yy + 0.9f, defrost)
                yy += 5.5f
            }
            // reflets diagonaux
            val rf = Paint(Paint.ANTI_ALIAS_FLAG)
            rf.shader = LinearGradient(cx - cabW * 0.4f, cabTop, cx + cabW * 0.1f, bodyTop, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF), floatArrayOf(0.05f, 0.2f, 0.34f, 0.5f, 0.62f), Shader.TileMode.CLAMP)
            c.drawRect(cx - cabW / 2, cabTop, cx + cabW / 2, bodyTop, rf)
            c.restore()
            // 3e feu stop + essuie-glace + antenne requin
            rr(c, cx - 14, cabTop + 9.5f, cx + 14, cabTop + 12.5f, 1.5f, paint(0xFFE53935.toInt()))
            if (model == 0 || model == 2) c.drawRect(cx - 1f, bodyTop - 24, cx + 1f, bodyTop - 2, paint(0xCC111111.toInt()))
            if (model == 1 || model == 2) c.drawOval(RectF(cx + cabW * 0.18f, cabTop - 4, cx + cabW * 0.3f, cabTop + 2), paint(shade(color, 0.35f)))
            if (model == 3) { // aileron galbé
                rr(c, cx - cabW * 0.52f, bodyTop - 7, cx + cabW * 0.52f, bodyTop + 1, 4f, vGrad(bodyTop - 7, bodyTop + 1, shade(color, 0.9f), shade(color, 0.4f)))
                rr(c, cx - cabW * 0.5f, bodyTop - 5.5f, cx + cabW * 0.5f, bodyTop - 3.5f, 1f, paint(0x55FFFFFF))
            }
        }

        // --- Caisse ---
        val body = RectF(cx - bw / 2, bodyTop, cx + bw / 2, bodyBottom)
        val rad = if (van) 16f else 26f
        c.drawRoundRect(body, rad, rad, vGrad(bodyTop, bodyBottom, shade(color, 1.28f), shade(color, 0.62f)))
        // ombrage latéral (bords sombres, centre lumineux)
        val sideShade = hGrad(body.left, body.right, 0x66000000, 0x00000000, 0x22FFFFFF, 0x00000000, 0x66000000)
        c.drawRoundRect(body, rad, rad, sideShade)
        sheen(c, body, rad, 0x44)
        // arête supérieure brillante
        rr(c, body.left + rad * 0.6f, bodyTop + 1.5f, body.right - rad * 0.6f, bodyTop + 3.2f, 1.5f, paint(0x66FFFFFF))
        // liseré
        val edge = Paint(Paint.ANTI_ALIAS_FLAG)
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = 1.8f
        edge.color = shade(color, 0.38f)
        c.drawRoundRect(body, rad, rad, edge)

        // Coffre : joints
        val seam = Paint(Paint.ANTI_ALIAS_FLAG)
        seam.style = Paint.Style.STROKE
        seam.strokeWidth = 1.2f
        seam.color = 0x66000000
        if (!van) {
            val trunk = RectF(cx - bw * 0.40f, bodyTop + 6, cx + bw * 0.40f, bodyBottom - 36)
            c.drawRoundRect(trunk, 8f, 8f, seam)
        }

        if (van) {
            val gp = vGrad(bodyTop + 12, bodyTop + 52, 0xFF2B3A4C.toInt(), 0xFF0D141C.toInt())
            rr(c, cx - bw / 2 + 14, bodyTop + 12, cx - 5, bodyTop + 52, 6f, gp)
            rr(c, cx + 5, bodyTop + 12, cx + bw / 2 - 14, bodyTop + 52, 6f, gp)
            sheen(c, RectF(cx - bw / 2 + 14, bodyTop + 12, cx - 5, bodyTop + 52), 6f, 0x44)
            sheen(c, RectF(cx + 5, bodyTop + 12, cx + bw / 2 - 14, bodyTop + 52), 6f, 0x44)
            c.drawRect(cx - 1.2f, bodyTop + 8, cx + 1.2f, bodyBottom - 22, paint(shade(color, 0.45f)))
            rr(c, cx - 9, bodyTop + 62, cx - 3, bodyTop + 67, 2f, paint(0xFFC9CDD2.toInt())) // poignées
            rr(c, cx + 3, bodyTop + 62, cx + 9, bodyTop + 67, 2f, paint(0xFFC9CDD2.toInt()))
        }

        // --- Feux arrière ---
        val ty = if (van) bodyBottom - 56 else bodyTop + 9
        for (side in listOf(-1, 1)) {
            val x0 = if (side < 0) cx - bw / 2 + 5 else cx + bw / 2 - 45
            val x1 = x0 + 40
            rr(c, x0, ty, x1, ty + 20, 7f, vGrad(ty, ty + 20, 0xFFFF5A4E.toInt(), 0xFF8E0F0F.toInt()))
            rr(c, x0 + 3, ty + 3, x1 - 3, ty + 9, 3f, paint(0x66FFFFFF)) // reflet
            val rev = if (side < 0) x1 - 11 else x0 + 11
            c.drawCircle(rev, ty + 14, 3.2f, paint(0xFFF5F5F5.toInt())) // feu de recul
            val ring = Paint(Paint.ANTI_ALIAS_FLAG)
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = 1f
            ring.color = 0x88000000.toInt()
            rr(c, x0, ty, x1, ty + 20, 7f, ring)
        }
        if (model == 3) rr(c, cx - bw / 2 + 45, ty + 6, cx + bw / 2 - 45, ty + 13, 3f, vGrad(ty + 6, ty + 13, 0xFFFF5A4E.toInt(), 0xFF8E0F0F.toInt()))

        // --- Logo, plaque, pare-chocs ---
        if (!van) {
            c.drawCircle(cx, ty + 7, 5f, vGrad(ty + 2, ty + 12, 0xFFF5F5F5.toInt(), 0xFF7C828B.toInt()))
        }
        val py = bodyBottom - 32
        rr(c, cx - 28, py, cx + 28, py + 18, 3.5f, vGrad(py, py + 18, 0xFFFFFFFF.toInt(), 0xFFD6D9DE.toInt()))
        rr(c, cx - 28, py, cx - 21, py + 18, 3.5f, paint(0xFF1E4FA8.toInt()))
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        pt.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        pt.textSize = 9.5f
        pt.textAlign = Paint.Align.CENTER
        pt.color = 0xFF111111.toInt()
        c.drawText("DJSTEF", cx + 3.5f, py + 13f, pt)
        val pe = Paint(Paint.ANTI_ALIAS_FLAG)
        pe.style = Paint.Style.STROKE
        pe.strokeWidth = 1f
        pe.color = 0x88000000.toInt()
        rr(c, cx - 28, py, cx + 28, py + 18, 3.5f, pe)

        // Pare-chocs bas + diffuseur + échappements + catadioptres
        rr(c, cx - bw / 2 + 8, bodyBottom - 11, cx + bw / 2 - 8, bodyBottom + 2, 6f, vGrad(bodyBottom - 11, bodyBottom + 2, shade(color, 0.55f), 0xFF0A0A0A.toInt()))
        val ex = vGrad(bodyBottom - 6, bodyBottom + 1, 0xFFE9ECEF.toInt(), 0xFF6B7078.toInt())
        c.drawOval(RectF(cx - bw / 2 + 22, bodyBottom - 7, cx - bw / 2 + 36, bodyBottom + 1), ex)
        if (model == 3 || model == 2) c.drawOval(RectF(cx + bw / 2 - 36, bodyBottom - 7, cx + bw / 2 - 22, bodyBottom + 1), ex)
        rr(c, cx - bw / 2 + 6, bodyBottom - 18, cx - bw / 2 + 16, bodyBottom - 12, 2f, paint(0xFFB71C1C.toInt()))
        rr(c, cx + bw / 2 - 16, bodyBottom - 18, cx + bw / 2 - 6, bodyBottom - 12, 2f, paint(0xFFB71C1C.toInt()))
        return bmp
    }

    // =====================================================================
    //  Vue du dessus (2D)
    // =====================================================================
    fun draw(model: Int, color: Int): Bitmap {
        val w = 180
        val h = 320
        val bmp = Bitmap.createBitmap((w * SCALE).toInt(), (h * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        bmp.density = 960
        val c = Canvas(bmp)
        c.scale(SCALE, SCALE)
        val cx = w / 2f
        val cy = h / 2f

        val dims = when (model) {
            0 -> floatArrayOf(96f, 176f, 36f)
            1 -> floatArrayOf(100f, 232f, 30f)
            2 -> floatArrayOf(112f, 224f, 26f)
            3 -> floatArrayOf(112f, 220f, 40f)
            else -> floatArrayOf(108f, 262f, 16f)
        }
        val bw = dims[0]
        val bh = dims[1]
        val cr = dims[2]
        val body = RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)

        // Ombre portée
        val sh = paint(0x77000000)
        sh.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(body.left + 6, body.top + 10, body.right + 10, body.bottom + 14), cr, cr, sh)

        // Roues avec pneus et jantes
        val tire = paint(0xFF101010.toInt())
        val wy = bh * 0.29f
        for (sy in listOf(-wy, wy)) {
            rr(c, body.left - 7, cy + sy - 20, body.left + 12, cy + sy + 20, 6f, tire)
            rr(c, body.right - 12, cy + sy - 20, body.right + 7, cy + sy + 20, 6f, tire)
            val rim = paint(0xFFC9CDD2.toInt())
            rr(c, body.left - 5, cy + sy - 11, body.left + 1, cy + sy + 11, 3f, rim)
            rr(c, body.right - 1, cy + sy - 11, body.right + 5, cy + sy + 11, 3f, rim)
        }

        // Carrosserie
        c.drawRoundRect(body, cr, cr, hGrad(body.left, body.right, shade(color, 0.58f), shade(color, 1.22f), shade(color, 0.58f)))
        sheen(c, body, cr, 0x4C)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG)
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = 2f
        edge.color = shade(color, 0.4f)
        c.drawRoundRect(body, cr, cr, edge)

        if (model == 3) { // bandes de course
            val st = paint(0xE6FFFFFF.toInt())
            c.drawRect(cx - 14, body.top + 10, cx - 5, body.bottom - 10, st)
            c.drawRect(cx + 5, body.top + 10, cx + 14, body.bottom - 10, st)
        }

        val q = when (model) {
            0 -> floatArrayOf(0.24f, 0.40f, 0.40f, 0.66f, 0.66f, 0.78f)
            1 -> floatArrayOf(0.25f, 0.38f, 0.38f, 0.62f, 0.62f, 0.72f)
            2 -> floatArrayOf(0.22f, 0.36f, 0.36f, 0.72f, 0.72f, 0.82f)
            3 -> floatArrayOf(0.34f, 0.46f, 0.46f, 0.62f, 0.62f, 0.72f)
            else -> floatArrayOf(0.14f, 0.28f, 0.28f, 0.92f, 0.92f, 0.94f)
        }
        val ws0 = q[0]
        val ws1 = q[1]
        val r0 = q[2]
        val r1 = q[3]
        val rw0 = q[4]
        val rw1 = q[5]
        val gw = bw * 0.40f

        // Joints de portes et poignées
        val seam = paint(0x55000000)
        val sideTop = body.top + bh * r0
        val sideBot = body.top + bh * r1
        c.drawRect(body.left + 3, sideTop, cx - gw - 2, sideTop + 1f, seam)
        c.drawRect(cx + gw + 2, sideTop, body.right - 3, sideTop + 1f, seam)
        c.drawRect(body.left + 3, sideBot, cx - gw - 2, sideBot + 1f, seam)
        c.drawRect(cx + gw + 2, sideBot, body.right - 3, sideBot + 1f, seam)
        val handle = paint(0xFFE3E6EA.toInt())
        val hy = (sideTop + sideBot) / 2f
        rr(c, body.left + 4, hy, body.left + 9, hy + 2.2f, 1f, handle)
        rr(c, body.right - 9, hy, body.right - 4, hy + 2.2f, 1f, handle)

        // Capot : creux et reflet
        c.drawRoundRect(RectF(cx - bw * 0.30f, body.top + 16, cx + bw * 0.30f, body.top + bh * ws0 - 6), 14f, 14f, paint(0x22FFFFFF))
        val crease = paint(0x40000000)
        c.drawRect(cx - bw * 0.30f, body.top + 22, cx - bw * 0.30f + 1.2f, body.top + bh * ws0 - 8, crease)
        c.drawRect(cx + bw * 0.30f - 1.2f, body.top + 22, cx + bw * 0.30f, body.top + bh * ws0 - 8, crease)

        // Pare-brise (avec reflet et essuie-glaces), lunette arrière, toit
        val wsRect = RectF(cx - gw, body.top + bh * ws0, cx + gw, body.top + bh * ws1)
        c.drawRoundRect(wsRect, 10f, 10f, glass(body.top, body.bottom))
        sheen(c, wsRect, 10f, 0x66)
        val wiper = Paint(Paint.ANTI_ALIAS_FLAG)
        wiper.style = Paint.Style.STROKE
        wiper.strokeWidth = 1.2f
        wiper.color = 0xAA000000.toInt()
        c.drawLine(wsRect.left + 6, wsRect.bottom - 3, cx - 2, wsRect.top + 5, wiper)
        c.drawLine(cx + 2, wsRect.bottom - 3, wsRect.right - 6, wsRect.top + 5, wiper)
        if (model != 4) {
            val rwRect = RectF(cx - gw * 0.92f, body.top + bh * rw0, cx + gw * 0.92f, body.top + bh * rw1)
            c.drawRoundRect(rwRect, 10f, 10f, glass(body.top, body.bottom))
            sheen(c, rwRect, 10f, 0x55)
        }
        val roofRect = RectF(cx - gw, body.top + bh * r0, cx + gw, body.top + bh * r1)
        c.drawRoundRect(roofRect, 12f, 12f, hGrad(roofRect.left, roofRect.right, shade(color, 0.8f), shade(color, 1.35f), shade(color, 0.8f)))
        sheen(c, roofRect, 12f, 0x50)
        if (model == 1 || model == 2) { // toit ouvrant panoramique
            val sr = RectF(roofRect.left + 8, roofRect.top + 14, roofRect.right - 8, roofRect.top + (roofRect.height()) * 0.65f)
            c.drawRoundRect(sr, 8f, 8f, glass(sr.top, sr.bottom))
            sheen(c, sr, 8f, 0x66)
        }
        if (model == 2) { // barres de toit
            val rail = paint(shade(color, 0.35f))
            c.drawRect(roofRect.left + 4, roofRect.top + 6, roofRect.left + 8, roofRect.bottom - 6, rail)
            c.drawRect(roofRect.right - 8, roofRect.top + 6, roofRect.right - 4, roofRect.bottom - 6, rail)
        }
        if (model == 4) { // nervures de l'utilitaire
            val rib = paint(shade(color, 0.7f))
            var y = roofRect.top + 26
            while (y < roofRect.bottom - 10) {
                c.drawRect(roofRect.left + 10, y, roofRect.right - 10, y + 3, rib)
                y += 34
            }
        }
        // antenne requin
        c.drawOval(RectF(cx - 4, roofRect.bottom - 14, cx + 4, roofRect.bottom - 4), paint(shade(color, 0.3f)))

        // Phares : optique, centre sombre, signature LED
        for (side in listOf(-1, 1)) {
            val x0 = if (side < 0) body.left + 7 else body.right - 31
            rr(c, x0, body.top + 4, x0 + 24, body.top + 17, 6f, vGrad(body.top + 4, body.top + 17, 0xFFFFFFFF.toInt(), 0xFFFFE082.toInt()))
            c.drawCircle(x0 + 12, body.top + 10.5f, 3.6f, paint(0xFF8D6E00.toInt()))
            rr(c, x0 + 2, body.top + 5, x0 + 22, body.top + 7, 1f, paint(0xFFFFFFFF.toInt()))
        }
        // Feux arrière
        for (side in listOf(-1, 1)) {
            val x0 = if (side < 0) body.left + 7 else body.right - 33
            rr(c, x0, body.bottom - 16, x0 + 26, body.bottom - 5, 4f, vGrad(body.bottom - 16, body.bottom - 5, 0xFFFF5A4E.toInt(), 0xFF8E0F0F.toInt()))
        }
        // Rétroviseurs
        val mirP = vGrad(body.top, body.bottom, shade(color, 1.1f), shade(color, 0.5f))
        val my = body.top + bh * ws1
        c.drawOval(RectF(body.left - 12, my - 14, body.left + 3, my + 4), mirP)
        c.drawOval(RectF(body.right - 3, my - 14, body.right + 12, my + 4), mirP)
        c.drawOval(RectF(body.left - 10, my - 11, body.left, my - 3), paint(0x66FFFFFF))
        c.drawOval(RectF(body.right, my - 11, body.right + 10, my - 3), paint(0x66FFFFFF))
        return bmp
    }
}
