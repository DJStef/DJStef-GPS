package fr.djstef.gps

import android.graphics.Bitmap
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
 * Illustrations en relief des radars et signalements (dessinées dans l'appli, sans image externe).
 * Chaque type : un badge bombé avec reflet et un pictogramme détaillé.
 */
object RadarArt {
    /** Types proposés dans le menu de signalement, dans l'ordre d'affichage. */
    val TYPES = listOf("Radar fixe", "Radar mobile", "Radar feu rouge", "Vitesse moyenne", "Contrôle", "Accident", "Danger")

    private val cache = HashMap<String, Bitmap>()

    fun key(kind: String): String {
        val k = kind.lowercase()
        return when {
            k.contains("feu rouge") -> "Radar feu rouge"
            k.contains("moyenne") || k.contains("tronçon") -> "Vitesse moyenne"
            k.contains("mobile") -> "Radar mobile"
            k.contains("contr") || k.contains("police") -> "Contrôle"
            k.contains("accident") -> "Accident"
            k.contains("danger") -> "Danger"
            else -> "Radar fixe"
        }
    }

    fun color(type: String): Int = Color.parseColor(
        when (type) {
            "Radar mobile" -> "#EF6C00"
            "Radar feu rouge" -> "#B71C1C"
            "Vitesse moyenne" -> "#1565C0"
            "Contrôle" -> "#0D47A1"
            "Accident" -> "#E65100"
            "Danger" -> "#F9A825"
            else -> "#C62828"
        }
    )

    fun bitmap(type: String, px: Int = 128): Bitmap {
        val k = "$type/$px"
        return cache.getOrPut(k) { draw(type, px) }
    }

    private fun shade(color: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * f).coerceIn(0f, 1f)
        if (f > 1f) hsv[1] = (hsv[1] / f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun p(color: Int): Paint {
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.color = color
        return q
    }

    private fun v(top: Float, bottom: Float, c1: Int, c2: Int): Paint {
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.shader = LinearGradient(0f, top, 0f, bottom, c1, c2, Shader.TileMode.CLAMP)
        return q
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, paint: Paint) =
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, paint)

    private fun metal() = intArrayOf(0xFFE9ECEF.toInt(), 0xFF9AA1AB.toInt())

    private fun stroke(color: Int, w: Float): Paint {
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.style = Paint.Style.STROKE
        q.strokeWidth = w
        q.color = color
        q.strokeCap = Paint.Cap.ROUND
        return q
    }

    private fun lens(c: Canvas, x: Float, y: Float, r: Float) {
        c.drawCircle(x, y, r + 1.5f, v(y - r, y + r, 0xFF50565F.toInt(), 0xFF1B1E23.toInt()))
        val g = Paint(Paint.ANTI_ALIAS_FLAG)
        g.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.3f, intArrayOf(0xFF6FA8E8.toInt(), 0xFF10243F.toInt(), 0xFF02060C.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, g)
        c.drawCircle(x - r * 0.35f, y - r * 0.4f, r * 0.28f, p(0xCCFFFFFF.toInt()))
    }

    private fun camBox(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val m = metal()
        rr(c, l, t, r, b, 5f, v(t, b, m[0], m[1]))
        rr(c, l + 2, t + 2, r - 2, t + (b - t) * 0.4f, 3f, p(0x55FFFFFF))
        rr(c, l, t, r, b, 5f, stroke(0x66000000, 1f))
        lens(c, (l + r) / 2f - 4f, (t + b) / 2f, (b - t) * 0.26f)
        c.drawCircle(r - 8f, t + 8f, 2.4f, p(0xFFFF5252.toInt()))
    }

    private fun pole(c: Canvas, x: Float, top: Float, bottom: Float, w: Float = 6f) {
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.shader = LinearGradient(x - w / 2, 0f, x + w / 2, 0f, intArrayOf(0xFF7C828B.toInt(), 0xFFEAEDF0.toInt(), 0xFF6B717A.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(x - w / 2, top, x + w / 2, bottom), 2f, 2f, q)
    }

    private fun triangle(c: Canvas, color: Int) {
        val path = Path()
        path.moveTo(50f, 15f)
        path.lineTo(86f, 78f)
        path.lineTo(14f, 78f)
        path.close()
        c.drawPath(path, v(15f, 78f, 0xFFFFFFFF.toInt(), 0xFFE3E6EA.toInt()))
        c.drawPath(path, stroke(color, 7f).also { it.strokeJoin = Paint.Join.ROUND })
    }

    private fun exclam(c: Canvas) {
        rr(c, 46.5f, 34f, 53.5f, 62f, 3.5f, v(34f, 62f, 0xFF3A3A3A.toInt(), 0xFF050505.toInt()))
        c.drawCircle(50f, 69f, 4.2f, p(0xFF111111.toInt()))
    }

    private fun draw(type: String, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = 512
        val c = Canvas(bmp)
        c.scale(px / 100f, px / 100f)
        val col = color(type)

        // Ombre + badge bombé avec anneau métal
        val sh = p(0x88000000.toInt())
        sh.maskFilter = android.graphics.BlurMaskFilter(3f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(51.5f, 53.5f, 45f, sh)
        c.drawCircle(50f, 50f, 46f, v(4f, 96f, 0xFFF5F6F8.toInt(), 0xFF6B717A.toInt()))
        val face = Paint(Paint.ANTI_ALIAS_FLAG)
        face.shader = RadialGradient(38f, 32f, 70f, intArrayOf(shade(col, 1.6f), col, shade(col, 0.5f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(50f, 50f, 41f, face)

        // Pictogramme
        when (type) {
            "Radar mobile" -> {
                val leg = stroke(0xFF2B2F36.toInt(), 3.4f)
                c.drawLine(50f, 50f, 33f, 84f, leg)
                c.drawLine(50f, 50f, 67f, 84f, leg)
                c.drawLine(50f, 50f, 50f, 86f, leg)
                camBox(c, 28f, 22f, 72f, 52f)
            }
            "Radar feu rouge" -> {
                pole(c, 66f, 44f, 84f)
                rr(c, 52f, 14f, 80f, 56f, 7f, v(14f, 56f, 0xFF3A3F47.toInt(), 0xFF0E1013.toInt()))
                val glow = Paint(Paint.ANTI_ALIAS_FLAG)
                glow.shader = RadialGradient(66f, 26f, 11f, intArrayOf(0xFFFF8A80.toInt(), 0xFFE53935.toInt(), 0x00E53935), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(66f, 26f, 11f, glow)
                c.drawCircle(66f, 26f, 6f, p(0xFFFF3B30.toInt()))
                c.drawCircle(64.5f, 24f, 2f, p(0xAAFFFFFF.toInt()))
                c.drawCircle(66f, 38f, 5.5f, p(0xFF5D4B12.toInt()))
                c.drawCircle(66f, 49f, 5.5f, p(0xFF14401B.toInt()))
                camBox(c, 20f, 50f, 54f, 72f)
            }
            "Vitesse moyenne" -> {
                pole(c, 27f, 38f, 82f)
                pole(c, 73f, 38f, 82f)
                camBox(c, 15f, 20f, 40f, 40f)
                camBox(c, 60f, 20f, 85f, 40f)
                val dash = stroke(0xFFFFFFFF.toInt(), 3.4f)
                c.drawLine(42f, 30f, 58f, 30f, dash)
                c.drawLine(52f, 25f, 58f, 30f, dash)
                c.drawLine(52f, 35f, 58f, 30f, dash)
                val t = Paint(Paint.ANTI_ALIAS_FLAG)
                t.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                t.textAlign = Paint.Align.CENTER
                t.textSize = 15f
                t.color = 0xFFFFFFFF.toInt()
                c.drawText("moy.", 50f, 70f, t)
            }
            "Contrôle" -> {
                // Gyrophare bleu / rouge au-dessus d'une voiture
                rr(c, 24f, 22f, 76f, 38f, 6f, v(22f, 38f, 0xFFEEEEEE.toInt(), 0xFF9E9E9E.toInt()))
                rr(c, 26f, 24f, 49f, 36f, 4f, v(24f, 36f, 0xFF64B5F6.toInt(), 0xFF0D47A1.toInt()))
                rr(c, 51f, 24f, 74f, 36f, 4f, v(24f, 36f, 0xFFFF6E6E.toInt(), 0xFFB71C1C.toInt()))
                rr(c, 16f, 44f, 84f, 70f, 9f, v(44f, 70f, 0xFFFFFFFF.toInt(), 0xFFB8BCC2.toInt()))
                rr(c, 30f, 48f, 70f, 56f, 3f, v(48f, 56f, 0xFF3F5166.toInt(), 0xFF0F1822.toInt()))
                rr(c, 16f, 58f, 84f, 62f, 1f, p(0xFF0D47A1.toInt()))
                c.drawCircle(30f, 72f, 7f, p(0xFF14161A.toInt()))
                c.drawCircle(70f, 72f, 7f, p(0xFF14161A.toInt()))
                c.drawCircle(30f, 72f, 3f, p(0xFFC9CDD2.toInt()))
                c.drawCircle(70f, 72f, 3f, p(0xFFC9CDD2.toInt()))
            }
            "Accident" -> {
                triangle(c, 0xFFE65100.toInt())
                // éclat de collision
                val burst = Path()
                for (i in 0 until 16) {
                    val a = Math.PI * 2 * i / 16
                    val r = if (i % 2 == 0) 20f else 10f
                    val x = 50f + (r * Math.cos(a)).toFloat()
                    val y = 54f + (r * Math.sin(a)).toFloat()
                    if (i == 0) burst.moveTo(x, y) else burst.lineTo(x, y)
                }
                burst.close()
                c.drawPath(burst, v(34f, 74f, 0xFFFFD54F.toInt(), 0xFFEF6C00.toInt()))
                c.drawPath(burst, stroke(0xFF7A2E00.toInt(), 1.4f))
                exclam(c)
            }
            "Danger" -> {
                triangle(c, 0xFFD32F2F.toInt())
                exclam(c)
            }
            else -> { // Radar fixe
                pole(c, 50f, 48f, 86f, 8f)
                rr(c, 40f, 80f, 60f, 88f, 3f, p(0xFF5F656E.toInt()))
                camBox(c, 26f, 20f, 74f, 52f)
                // arcs de détection
                val arc = stroke(0xCCFFFFFF.toInt(), 2.2f)
                c.drawArc(RectF(18f, 22f, 30f, 50f), 120f, 120f, false, arc)
                c.drawArc(RectF(70f, 22f, 82f, 50f), -60f, 120f, false, arc)
            }
        }

        // Reflet de verre sur le badge
        val hi = Paint(Paint.ANTI_ALIAS_FLAG)
        hi.shader = LinearGradient(0f, 9f, 0f, 52f, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(RectF(14f, 8f, 86f, 50f), hi)
        return bmp
    }
}
