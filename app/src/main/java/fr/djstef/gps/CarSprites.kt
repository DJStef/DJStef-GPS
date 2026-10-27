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
    val NAMES = listOf("Citadine", "Berline", "SUV", "Sportive", "Utilitaire", "Mégane IV GT", "Hilux hardtop", "Concept 3D réel")
    val COLORS = listOf(
        "#E53935", "#FF9F1C", "#FDD835", "#43A047", "#1E88E5", "#F5F5F5", "#212121", "#B0BEC5"
    )

    private const val SCALE = 2f

    /** Accès aux images pré-rendues à partir de vrais modèles 3D (dossier assets/cars). */
    var assets: android.content.res.AssetManager? = null
    private val cache = HashMap<String, Bitmap>()

    /** Modèles 3D réels (index >= 7) : image pré-rendue de la couleur de palette la plus proche. */
    private fun real(view: String, color: Int): Bitmap? {
        val am = assets ?: return null
        var best = 0
        var bd = Int.MAX_VALUE
        for ((i, hex) in COLORS.withIndex()) {
            val c = Color.parseColor(hex)
            val dr = Color.red(c) - Color.red(color)
            val dg = Color.green(c) - Color.green(color)
            val db = Color.blue(c) - Color.blue(color)
            val d = dr * dr + dg * dg + db * db
            if (d < bd) {
                bd = d
                best = i
            }
        }
        val key = "$view$best"
        cache[key]?.let { return it }
        return try {
            am.open("cars/concept_${view}_$best.png").use { st ->
                val o = android.graphics.BitmapFactory.Options()
                o.inScaled = false
                val b = android.graphics.BitmapFactory.decodeStream(st, null, o)
                b?.density = 960
                if (b != null) cache[key] = b
                b
            }
        } catch (_: Exception) {
            null
        }
    }

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
        if (model == 7) real("rear", color)?.let { return it }
        if (model == 5) return drawRearMegane(color)
        if (model == 6) return drawRearHilux(color)
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
        if (model == 7) real("top", color)?.let { return it }
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
            5 -> floatArrayOf(98f, 218f, 38f)
            6 -> floatArrayOf(110f, 280f, 18f)
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
            5 -> floatArrayOf(0.25f, 0.38f, 0.38f, 0.64f, 0.64f, 0.76f)
            6 -> floatArrayOf(0.24f, 0.33f, 0.33f, 0.50f, 0.52f, 0.52f)
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
        if (model != 4 && model != 6) {
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
        if (model == 6) { // hardtop : toit, vitres latérales et lunette, barres de toit
            val ht = RectF(cx - gw - 2, body.top + bh * 0.53f, cx + gw + 2, body.bottom - 10)
            c.drawRoundRect(ht, 8f, 8f, hGrad(ht.left, ht.right, shade(color, 0.7f), shade(color, 1.35f), shade(color, 0.7f)))
            sheen(c, ht, 8f, 0x50)
            c.drawRoundRect(ht, 8f, 8f, stroke(shade(color, 0.4f), 1.4f))
            val sg = vGrad(ht.top, ht.bottom, 0xFF1C2733.toInt(), 0xFF0B1118.toInt())
            rr(c, ht.left - 1f, ht.top + 14, ht.left + 3f, ht.bottom - 14, 2f, sg)
            rr(c, ht.right - 3f, ht.top + 14, ht.right + 1f, ht.bottom - 14, 2f, sg)
            val lunette = RectF(cx - gw * 0.8f, ht.bottom - 12, cx + gw * 0.8f, ht.bottom - 3)
            c.drawRoundRect(lunette, 4f, 4f, glass(lunette.top, lunette.bottom))
            val rail = paint(0xFF222A33.toInt())
            c.drawRect(ht.left + 6, ht.top + 8, ht.left + 10, ht.bottom - 18, rail)
            c.drawRect(ht.right - 10, ht.top + 8, ht.right - 6, ht.bottom - 18, rail)
            c.drawRect(cx - gw, body.top + bh * 0.62f, cx + gw, body.top + bh * 0.62f + 1.6f, paint(0x66000000))
        }
        if (model == 5) { // toit panoramique en verre + feux avant en « C »
            val sr = RectF(roofRect.left + 6, roofRect.top + 8, roofRect.right - 6, roofRect.bottom - 10)
            c.drawRoundRect(sr, 10f, 10f, glass(sr.top, sr.bottom))
            sheen(c, sr, 10f, 0x66)
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

    // =====================================================================
    //  Modèles dédiés : Renault Mégane IV GT (205 ch) et Toyota Hilux 2020 + hardtop
    // =====================================================================
    private fun stroke(color: Int, w: Float): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = w
        p.color = color
        return p
    }

    private fun text(s: String, size: Float, color: Int, c: Canvas, x: Float, y: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textSize = size
        p.textAlign = Paint.Align.CENTER
        p.color = color
        c.drawText(s, x, y, p)
    }

    private fun groundShadow(c: Canvas, cx: Float, ground: Float, bw: Float) {
        val sh = paint(0x88000000.toInt())
        sh.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        c.drawOval(RectF(cx - bw / 2 - 10, ground - 14, cx + bw / 2 + 10, ground + 10), sh)
        val sh2 = paint(0xAA000000.toInt())
        sh2.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
        c.drawOval(RectF(cx - bw / 2 + 4, ground - 8, cx + bw / 2 - 4, ground + 4), sh2)
    }

    private fun plate(c: Canvas, cx: Float, py: Float) {
        rr(c, cx - 28, py, cx + 28, py + 18, 3.5f, vGrad(py, py + 18, 0xFFFFFFFF.toInt(), 0xFFD6D9DE.toInt()))
        rr(c, cx - 28, py, cx - 21, py + 18, 3.5f, paint(0xFF1E4FA8.toInt()))
        text("DJSTEF", 9.5f, 0xFF111111.toInt(), c, cx + 3.5f, py + 13f)
        rr(c, cx - 28, py, cx + 28, py + 18, 3.5f, stroke(0x88000000.toInt(), 1f))
    }

    private fun drawRearMegane(color: Int): Bitmap {
        val w = 240
        val h = 190
        val bmp = Bitmap.createBitmap((w * SCALE).toInt(), (h * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        bmp.density = 960
        val c = Canvas(bmp)
        c.scale(SCALE, SCALE)
        val cx = w / 2f
        val ground = h - 16f
        val bw = 176f
        val bodyBottom = ground - 12f
        val bodyTop = bodyBottom - 50f
        val cabTop = bodyTop - 40f
        groundShadow(c, cx, ground, bw)

        // Roues : pneus bas profil et jantes sombres 18"
        val lx0 = cx - bw / 2 + 12
        val rx1 = cx + bw / 2 - 12
        rr(c, lx0 - 2, bodyBottom - 24, lx0 + 42, bodyBottom - 2, 12f, paint(0xFF05070A.toInt()))
        rr(c, rx1 - 42, bodyBottom - 24, rx1 + 2, bodyBottom - 2, 12f, paint(0xFF05070A.toInt()))
        rr(c, lx0 + 3, bodyBottom - 19, lx0 + 39, ground, 7f, vGrad(bodyBottom - 19, ground, 0xFF303030.toInt(), 0xFF050505.toInt()))
        rr(c, rx1 - 39, bodyBottom - 19, rx1 - 3, ground, 7f, vGrad(bodyBottom - 19, ground, 0xFF303030.toInt(), 0xFF050505.toInt()))
        rr(c, lx0 + 12, bodyBottom - 14, lx0 + 30, ground - 3, 3f, paint(0xFF8E959E.toInt()))
        rr(c, rx1 - 30, bodyBottom - 14, rx1 - 12, ground - 3, 3f, paint(0xFF8E959E.toInt()))

        // Toit + lunette arrière très inclinée
        val roof = Path()
        roof.moveTo(cx - 70f, bodyTop + 6)
        roof.cubicTo(cx - 66f, cabTop + 10, cx - 54f, cabTop, cx - 40f, cabTop)
        roof.lineTo(cx + 40f, cabTop)
        roof.cubicTo(cx + 54f, cabTop, cx + 66f, cabTop + 10, cx + 70f, bodyTop + 6)
        roof.close()
        val rp = Paint(Paint.ANTI_ALIAS_FLAG)
        rp.shader = LinearGradient(0f, cabTop, 0f, bodyTop, shade(color, 1.4f), shade(color, 0.8f), Shader.TileMode.CLAMP)
        c.drawPath(roof, rp)
        c.drawPath(roof, stroke(shade(color, 0.4f), 1.6f))
        // aileron de toit (lèvre) avec 3e feu stop
        rr(c, cx - 52f, cabTop + 3, cx + 52f, cabTop + 11, 4f, vGrad(cabTop + 3, cabTop + 11, shade(color, 1.1f), shade(color, 0.5f)))
        rr(c, cx - 22f, cabTop + 7f, cx + 22f, cabTop + 9.5f, 1.2f, paint(0xFFE53935.toInt()))
        val gl = Path()
        gl.moveTo(cx - 62f, bodyTop + 2)
        gl.cubicTo(cx - 59f, cabTop + 16, cx - 50f, cabTop + 13, cx - 44f, cabTop + 13)
        gl.lineTo(cx + 44f, cabTop + 13)
        gl.cubicTo(cx + 50f, cabTop + 13, cx + 59f, cabTop + 16, cx + 62f, bodyTop + 2)
        gl.close()
        c.drawPath(gl, glass(cabTop, bodyTop))
        c.save()
        c.clipPath(gl)
        val rf = Paint(Paint.ANTI_ALIAS_FLAG)
        rf.shader = LinearGradient(cx - 60f, cabTop, cx + 10f, bodyTop, intArrayOf(0x00FFFFFF, 0x77FFFFFF, 0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF), floatArrayOf(0.05f, 0.2f, 0.34f, 0.5f, 0.62f), Shader.TileMode.CLAMP)
        c.drawRect(cx - 70f, cabTop, cx + 70f, bodyTop, rf)
        // dégivrage + essuie-glace arrière
        var yy = cabTop + 20
        while (yy < bodyTop - 3) {
            c.drawRect(cx - 62f, yy, cx + 62f, yy + 0.8f, paint(0x33FF8A80))
            yy += 5.5f
        }
        c.drawLine(cx - 4f, bodyTop - 1, cx + 34f, bodyTop - 14, stroke(0xCC000000.toInt(), 1.6f))
        c.restore()
        c.drawOval(RectF(cx + 12f, cabTop - 5, cx + 26f, cabTop + 1), paint(shade(color, 0.3f))) // antenne requin

        // Hayon / caisse
        val body = RectF(cx - bw / 2, bodyTop, cx + bw / 2, bodyBottom)
        c.drawRoundRect(body, 22f, 22f, vGrad(bodyTop, bodyBottom, shade(color, 1.3f), shade(color, 0.6f)))
        c.drawRoundRect(body, 22f, 22f, hGrad(body.left, body.right, 0x77000000, 0x00000000, 0x2AFFFFFF, 0x00000000, 0x77000000))
        sheen(c, body, 22f, 0x44)
        c.drawRoundRect(body, 22f, 22f, stroke(shade(color, 0.38f), 1.8f))
        // nervure du hayon (pli horizontal)
        c.drawRect(cx - bw * 0.36f, bodyTop + 34f, cx + bw * 0.36f, bodyTop + 35.2f, paint(0x44000000))
        c.drawRect(cx - bw * 0.36f, bodyTop + 35.2f, cx + bw * 0.36f, bodyTop + 36.2f, paint(0x33FFFFFF))

        // Feux 3D à LED : « C » rouge de chaque côté reliés par une barre noire
        val ty = bodyTop + 4f
        for (side in listOf(-1, 1)) {
            val outer = if (side < 0) cx - bw / 2 + 4f else cx + bw / 2 - 4f
            val inner = if (side < 0) cx - 20f else cx + 20f
            val p = Path()
            p.moveTo(outer, ty + 3)
            p.cubicTo(outer, ty, outer + side * -8f, ty - 1, outer + side * -22f, ty + 2)
            p.lineTo(inner, ty + 6)
            p.lineTo(inner, ty + 16)
            p.lineTo(outer + side * -26f, ty + 17)
            p.cubicTo(outer + side * -8f, ty + 21, outer, ty + 22, outer, ty + 16)
            p.close()
            val lp = Paint(Paint.ANTI_ALIAS_FLAG)
            lp.shader = LinearGradient(0f, ty, 0f, ty + 22, 0xFFFF6A5E.toInt(), 0xFF7A0C0C.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(p, lp)
            c.drawPath(p, stroke(0xAA000000.toInt(), 1f))
            // signature LED en C
            val led = Path()
            led.moveTo(outer + side * -4f, ty + 5)
            led.lineTo(outer + side * -22f, ty + 5)
            led.moveTo(outer + side * -4f, ty + 14)
            led.lineTo(outer + side * -22f, ty + 14)
            c.drawPath(led, stroke(0xFFFFD0CC.toInt(), 1.4f))
            // feu de recul
            c.drawCircle(if (side < 0) cx - 30f else cx + 30f, ty + 17.5f, 2.2f, paint(0xFFF5F5F5.toInt()))
            rr(c, if (side < 0) outer + 4 else outer - 36, ty + 3, if (side < 0) outer + 36 else outer - 4, ty + 9, 3f, paint(0x55FFFFFF))
        }
        rr(c, cx - 22f, ty + 7.5f, cx + 22f, ty + 14.5f, 2f, vGrad(ty + 7f, ty + 15f, 0xFF1B1B1B.toInt(), 0xFF050505.toInt()))
        c.drawRect(cx - 20f, ty + 10.2f, cx + 20f, ty + 11.4f, paint(0xFFE53935.toInt()))
        // Losange Renault (chromé) et badges
        val dia = Path()
        dia.moveTo(cx, ty + 17f)
        dia.lineTo(cx + 6f, ty + 27f)
        dia.lineTo(cx, ty + 37f)
        dia.lineTo(cx - 6f, ty + 27f)
        dia.close()
        c.drawPath(dia, vGrad(ty + 17, ty + 37, 0xFFF5F5F5.toInt(), 0xFF6B717A.toInt()))
        c.drawPath(dia, stroke(0xFF3A3F47.toInt(), 1f))
        text("MÉGANE", 6.5f, 0xFFC9CDD2.toInt(), c, cx - 40f, ty + 37f)
        text("GT", 8f, 0xFFC9CDD2.toInt(), c, cx + 48f, ty + 37f)

        // Pare-chocs : bas noir, diffuseur à ailettes, catadioptres, double échappement chromé
        val bumper = Path()
        bumper.moveTo(cx - bw / 2 + 4f, bodyBottom - 17)
        bumper.lineTo(cx + bw / 2 - 4f, bodyBottom - 17)
        bumper.lineTo(cx + bw / 2 - 14f, bodyBottom + 2)
        bumper.lineTo(cx - bw / 2 + 14f, bodyBottom + 2)
        bumper.close()
        c.drawPath(bumper, vGrad(bodyBottom - 17, bodyBottom + 2, 0xFF2A2D32.toInt(), 0xFF050607.toInt()))
        var fx = cx - 46f
        while (fx <= cx + 46f) {
            c.drawRect(fx, bodyBottom - 9, fx + 1.4f, bodyBottom + 1, paint(0xFF000000.toInt()))
            fx += 7f
        }
        rr(c, cx - bw / 2 + 8, bodyBottom - 16, cx - bw / 2 + 16, bodyBottom - 6, 2f, paint(0xFFB71C1C.toInt()))
        rr(c, cx + bw / 2 - 16, bodyBottom - 16, cx + bw / 2 - 8, bodyBottom - 6, 2f, paint(0xFFB71C1C.toInt()))
        val ex = vGrad(bodyBottom - 9, bodyBottom + 2, 0xFFF1F3F5.toInt(), 0xFF666C74.toInt())
        c.drawOval(RectF(cx - 70f, bodyBottom - 9, cx - 52f, bodyBottom + 2), ex)
        c.drawOval(RectF(cx + 52f, bodyBottom - 9, cx + 70f, bodyBottom + 2), ex)
        c.drawOval(RectF(cx - 66f, bodyBottom - 6, cx - 56f, bodyBottom), paint(0xFF111111.toInt()))
        c.drawOval(RectF(cx + 56f, bodyBottom - 6, cx + 66f, bodyBottom), paint(0xFF111111.toInt()))
        plate(c, cx, bodyBottom - 52f)
        return bmp
    }

    private fun drawRearHilux(color: Int): Bitmap {
        val w = 240
        val h = 190
        val bmp = Bitmap.createBitmap((w * SCALE).toInt(), (h * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        bmp.density = 960
        val c = Canvas(bmp)
        c.scale(SCALE, SCALE)
        val cx = w / 2f
        val ground = h - 10f
        val bw = 178f
        val bodyBottom = ground - 20f
        val gateTop = bodyBottom - 52f
        val capTop = gateTop - 62f
        groundShadow(c, cx, ground, bw)

        // Gros pneus tout-terrain sous les ailes
        val lx0 = cx - bw / 2 + 4
        val rx1 = cx + bw / 2 - 4
        val tire = vGrad(bodyBottom - 34, ground, 0xFF2A2A2A.toInt(), 0xFF040404.toInt())
        rr(c, lx0, bodyBottom - 34, lx0 + 34, ground, 9f, tire)
        rr(c, rx1 - 34, bodyBottom - 34, rx1, ground, 9f, tire)
        for (i in 0..4) {
            c.drawRect(lx0 + 3, bodyBottom - 30 + i * 9f, lx0 + 31, bodyBottom - 28 + i * 9f, paint(0x33FFFFFF))
            c.drawRect(rx1 - 31, bodyBottom - 30 + i * 9f, rx1 - 3, bodyBottom - 28 + i * 9f, paint(0x33FFFFFF))
        }

        // Hardtop (même couleur) : flancs, toit, vitre arrière
        val cap = Path()
        cap.moveTo(cx - 74f, gateTop + 2)
        cap.lineTo(cx - 68f, capTop + 8)
        cap.quadTo(cx - 66f, capTop, cx - 56f, capTop)
        cap.lineTo(cx + 56f, capTop)
        cap.quadTo(cx + 66f, capTop, cx + 68f, capTop + 8)
        cap.lineTo(cx + 74f, gateTop + 2)
        cap.close()
        val cp = Paint(Paint.ANTI_ALIAS_FLAG)
        cp.shader = LinearGradient(0f, capTop, 0f, gateTop, shade(color, 1.35f), shade(color, 0.8f), Shader.TileMode.CLAMP)
        c.drawPath(cap, cp)
        c.drawPath(cap, stroke(shade(color, 0.4f), 1.6f))
        // 3e feu stop sur le toit du hardtop
        rr(c, cx - 20f, capTop + 4f, cx + 20f, capTop + 8f, 2f, paint(0xFFE53935.toInt()))
        // Porte-vitre arrière (cadre + vitre teintée + reflets)
        val gl = RectF(cx - 56f, capTop + 12f, cx + 56f, gateTop - 8f)
        rr(c, gl.left - 4, gl.top - 3, gl.right + 4, gl.bottom + 3, 8f, paint(shade(color, 0.55f)))
        c.drawRoundRect(gl, 7f, 7f, glass(gl.top, gl.bottom))
        c.save()
        val clipP = Path()
        clipP.addRoundRect(gl, 7f, 7f, Path.Direction.CW)
        c.clipPath(clipP)
        val rf = Paint(Paint.ANTI_ALIAS_FLAG)
        rf.shader = LinearGradient(gl.left, gl.top, gl.right * 0.8f, gl.bottom, intArrayOf(0x00FFFFFF, 0x77FFFFFF, 0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF), floatArrayOf(0.05f, 0.22f, 0.36f, 0.55f, 0.68f), Shader.TileMode.CLAMP)
        c.drawRect(gl, rf)
        var yy = gl.top + 8
        while (yy < gl.bottom - 3) {
            c.drawRect(gl.left, yy, gl.right, yy + 0.8f, paint(0x33FF8A80))
            yy += 5.5f
        }
        c.restore()
        // poignée centrale du hardtop et joints
        rr(c, cx - 9f, gateTop - 5f, cx + 9f, gateTop - 2f, 1.5f, paint(0xFFB8BEC6.toInt()))
        c.drawRect(cx - 74f + 4, gateTop - 1f, cx + 74f - 4, gateTop + 1f, paint(0x66000000))

        // Hayon (plateau) avec embouts d'ailes
        val gate = RectF(cx - bw / 2 + 8, gateTop, cx + bw / 2 - 8, bodyBottom - 10f)
        c.drawRoundRect(gate, 8f, 8f, vGrad(gateTop, bodyBottom, shade(color, 1.28f), shade(color, 0.62f)))
        c.drawRoundRect(gate, 8f, 8f, hGrad(gate.left, gate.right, 0x66000000, 0x00000000, 0x22FFFFFF, 0x00000000, 0x66000000))
        sheen(c, gate, 8f, 0x40)
        c.drawRoundRect(gate, 8f, 8f, stroke(shade(color, 0.38f), 1.8f))
        // estampage du hayon + poignée
        c.drawRoundRect(RectF(cx - 60f, gateTop + 10f, cx + 60f, gateTop + 34f), 5f, 5f, stroke(0x55000000, 1.2f))
        text("TOYOTA", 13f, 0xFFE3E6EA.toInt(), c, cx, gateTop + 28f)
        rr(c, cx - 14f, gateTop + 4f, cx + 14f, gateTop + 8f, 2f, paint(shade(color, 0.45f)))
        // Ailes arrière + feux verticaux à LED
        for (side in listOf(-1, 1)) {
            val x0 = if (side < 0) cx - bw / 2 + 1f else cx + bw / 2 - 15f
            rr(c, x0, gateTop - 6f, x0 + 14f, gateTop + 40f, 5f, vGrad(gateTop - 6f, gateTop + 40f, 0xFFFF5A4E.toInt(), 0xFF7A0C0C.toInt()))
            rr(c, x0 + 2.5f, gateTop - 3f, x0 + 6f, gateTop + 37f, 2f, paint(0x66FFFFFF))
            c.drawRect(x0 + 8f, gateTop + 16f, x0 + 12f, gateTop + 24f, paint(0xFFF5F5F5.toInt())) // recul
            rr(c, x0, gateTop - 6f, x0 + 14f, gateTop + 40f, 5f, stroke(0x88000000.toInt(), 1f))
        }
        // Pare-chocs marche-pied (noir + chrome) avec plaque et crochet d'attelage
        val bp = Path()
        bp.moveTo(cx - bw / 2 + 2f, bodyBottom - 12)
        bp.lineTo(cx + bw / 2 - 2f, bodyBottom - 12)
        bp.lineTo(cx + bw / 2 - 6f, bodyBottom + 6)
        bp.lineTo(cx - bw / 2 + 6f, bodyBottom + 6)
        bp.close()
        c.drawPath(bp, vGrad(bodyBottom - 12, bodyBottom + 6, 0xFFE6E9ED.toInt(), 0xFF5E646C.toInt()))
        rr(c, cx - bw / 2 + 12f, bodyBottom - 4f, cx + bw / 2 - 12f, bodyBottom + 6f, 3f, vGrad(bodyBottom - 4, bodyBottom + 6, 0xFF2A2D32.toInt(), 0xFF0A0B0D.toInt()))
        plate(c, cx, bodyBottom - 10f)
        c.drawCircle(cx + 40f, bodyBottom + 5f, 4f, vGrad(bodyBottom + 1, bodyBottom + 9, 0xFFDADDE1.toInt(), 0xFF4A4F57.toInt()))
        rr(c, cx - bw / 2 + 4f, bodyBottom - 12f, cx - bw / 2 + 12f, bodyBottom - 6f, 2f, paint(0xFFB71C1C.toInt()))
        rr(c, cx + bw / 2 - 12f, bodyBottom - 12f, cx + bw / 2 - 4f, bodyBottom - 6f, 2f, paint(0xFFB71C1C.toInt()))
        return bmp
    }
}
