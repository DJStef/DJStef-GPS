package fr.djstef.gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import kotlin.math.min

/** Dessins 3D (relief, ombre, reflet) réutilisés par l'interface. */
object Gloss {
    fun shade(color: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * f).coerceIn(0f, 1f)
        if (f > 1f) hsv[1] = (hsv[1] / f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** Rectangle arrondi bombé : dégradé vertical + reflet sur la moitié haute + liseré clair. */
    fun rect(color: Int, radiusPx: Float, glossHeightPx: Int): LayerDrawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(shade(color, 1.25f), color, shade(color, 0.62f)))
        base.cornerRadius = radiusPx
        base.setStroke(2, shade(color, 1.5f) and 0x88FFFFFF.toInt() or 0x44000000)
        val hi = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66FFFFFF, 0x00FFFFFF))
        hi.cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        val ld = LayerDrawable(arrayOf(base, hi))
        ld.setLayerInset(1, 3, 3, 3, 0)
        ld.setLayerHeight(1, glossHeightPx)
        ld.setLayerGravity(1, android.view.Gravity.TOP)
        return ld
    }

    /** Pastille ronde bombée avec reflet. */
    fun oval(color: Int): LayerDrawable {
        val base = GradientDrawable()
        base.shape = GradientDrawable.OVAL
        base.gradientType = GradientDrawable.RADIAL_GRADIENT
        base.gradientRadius = 220f
        base.setGradientCenter(0.35f, 0.3f)
        base.colors = intArrayOf(shade(color, 1.6f), color, shade(color, 0.55f))
        base.setStroke(2, 0x55FFFFFF)
        val hi = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x77FFFFFF, 0x00FFFFFF))
        hi.shape = GradientDrawable.OVAL
        val ld = LayerDrawable(arrayOf(base, hi))
        ld.setLayerInsetLeft(1, 10)
        ld.setLayerInsetRight(1, 10)
        ld.setLayerInsetTop(1, 4)
        ld.setLayerHeight(1, 26)
        ld.setLayerGravity(1, android.view.Gravity.TOP)
        return ld
    }
}

private fun dpToPx(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

/** Panneau de limitation de vitesse en 3D : cadre métal, anneau rouge bombé, face blanche, reflet. */
class SignView(ctx: Context) : View(ctx) {
    var value: Int = 0
        set(v) {
            field = v
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = s / 2f - dpToPx(context, 4f)

        p.shader = null
        p.style = Paint.Style.FILL

        // Cadre métallique (dégradé conique)
        p.shader = SweepGradient(
            cx, cy,
            intArrayOf(0xFFF5F5F5.toInt(), 0xFF8A9099.toInt(), 0xFFE8EAED.toInt(), 0xFF6B717A.toInt(), 0xFFF5F5F5.toInt()),
            floatArrayOf(0f, 0.3f, 0.55f, 0.8f, 1f)
        )
        c.drawCircle(cx, cy, r, p)

        // Anneau rouge bombé
        val rr = r * 0.9f
        p.shader = RadialGradient(cx - rr * 0.3f, cy - rr * 0.4f, rr * 1.5f, intArrayOf(0xFFE53935.toInt(), 0xFFD32F2F.toInt(), 0xFFB71C1C.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rr, p)

        // Face blanche en creux
        val fr = r * 0.66f
        p.shader = RadialGradient(cx - fr * 0.3f, cy - fr * 0.4f, fr * 1.6f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFF2F2F2.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, fr, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = dpToPx(context, 1.5f)
        p.color = 0xFFB0B0B0.toInt()
        c.drawCircle(cx, cy, fr, p)

        // Chiffre en relief
        p.style = Paint.Style.FILL
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val txt = value.toString()
        p.textSize = if (txt.length >= 3) fr * 0.95f else fr * 1.15f
        p.textAlign = Paint.Align.CENTER
        val ty = cy - (p.descent() + p.ascent()) / 2f
        p.color = 0xFF111111.toInt()
        c.drawText(txt, cx, ty, p)

        // Reflet de vitre (croissant clair en haut à gauche)
        c.save()
        val clip = Path()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        c.clipPath(clip)
        val glossRect = RectF(cx - r, cy - r, cx + r * 0.9f, cy + r * 0.1f)
        p.shader = LinearGradient(0f, glossRect.top, 0f, glossRect.bottom, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawOval(RectF(cx - r * 0.85f, cy - r * 1.05f, cx + r * 0.7f, cy + r * 0.05f), p)
        c.restore()
        p.shader = null
    }
}

/** Compteur de vitesse rond en 3D (sphère sombre, anneau chromé, reflet). Devient rouge en excès de vitesse. */
class SpeedBubble(ctx: Context) : View(ctx) {
    private var speed = 0
    private var over = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun set(kmh: Int, isOver: Boolean) {
        if (kmh != speed || isOver != over) {
            speed = kmh
            over = isOver
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = s / 2f - dpToPx(context, 4f)

        p.style = Paint.Style.FILL
        p.shader = null
        p.color = 0x99000000.toInt()
        p.maskFilter = android.graphics.BlurMaskFilter(dpToPx(context, 5f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(cx + dpToPx(context, 1.5f), cy + dpToPx(context, 3f), r, p)
        p.maskFilter = null

        // Anneau chromé
        p.shader = SweepGradient(
            cx, cy,
            intArrayOf(0xFFF5F5F5.toInt(), 0xFF7C828B.toInt(), 0xFFE0E3E8.toInt(), 0xFF5F656E.toInt(), 0xFFF5F5F5.toInt()),
            floatArrayOf(0f, 0.3f, 0.55f, 0.8f, 1f)
        )
        c.drawCircle(cx, cy, r, p)

        // Sphère
        val br = r * 0.9f
        val light = if (over) 0xFFFF6B5E.toInt() else 0xFF4A5A80.toInt()
        val mid = if (over) 0xFFD32F2F.toInt() else 0xFF1B2540.toInt()
        val dark = if (over) 0xFF7A0F0F.toInt() else 0xFF070C18.toInt()
        p.shader = RadialGradient(cx - br * 0.35f, cy - br * 0.45f, br * 1.7f, intArrayOf(light, mid, dark), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, br, p)

        // Texte
        p.shader = null
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.color = 0x88000000.toInt()
        p.textSize = br * 0.85f
        val txt = speed.toString()
        c.drawText(txt, cx + 1.5f, cy + br * 0.2f + 1.5f, p)
        p.color = Color.WHITE
        c.drawText(txt, cx, cy + br * 0.2f, p)
        p.textSize = br * 0.3f
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        p.color = 0xFFD5DBE8.toInt()
        c.drawText("km/h", cx, cy + br * 0.62f, p)

        // Reflet
        c.save()
        val clip = Path()
        clip.addCircle(cx, cy, br, Path.Direction.CW)
        c.clipPath(clip)
        p.shader = LinearGradient(0f, cy - br, 0f, cy, 0x77FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - br * 0.8f, cy - br * 1.0f, cx + br * 0.8f, cy - br * 0.05f), p)
        c.restore()
        p.shader = null
    }
}

/** Pictogramme de manœuvre dessiné (virage, rond-point avec sortie, bifurcation, demi-tour, arrivée…), lisible d'un coup d'œil. */
class ManeuverView(ctx: Context) : View(ctx) {
    private var kind = 8
    private var exitNo = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(k: Int, text: String) {
        kind = k
        exitNo = Regex("(\\d+)\\s*(?:er|re|ère|ème|e|è)?\\s*sortie", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        invalidate()
    }

    private fun head(c: Canvas, tx: Float, ty: Float, deg: Double) {
        val r = Math.toRadians(deg)
        val dx = Math.cos(r).toFloat()
        val dy = Math.sin(r).toFloat()
        val bx = tx - dx * 22f
        val by = ty - dy * 22f
        val px = -dy
        val py = dx
        val path = Path()
        path.moveTo(tx, ty)
        path.lineTo(bx + px * 15f, by + py * 15f)
        path.lineTo(bx - px * 15f, by - py * 15f)
        path.close()
        p.style = Paint.Style.FILL
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
    }

    override fun onDraw(c: Canvas) {
        val sz = min(width, height).toFloat()
        if (sz <= 0f) return
        c.save()
        c.translate((width - sz) / 2f, (height - sz) / 2f)
        c.scale(sz / 100f, sz / 100f)
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = 11f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val m = when (kind) {
            10, 2, 15, 3, 9, 16, 11, 14, 18, 20, 23, 19, 21, 24, 12, 13 -> if (kind in intArrayOf(15, 3, 16, 14, 19, 21, 24, 13)) -1 else 1
            else -> 1
        }
        fun px(x: Float) = if (m > 0) x else 100f - x
        fun md(d: Double) = if (m > 0) d else 180.0 - d
        val path = Path()
        when (kind) {
            10, 2, 15, 3 -> { // virage à droite / à gauche
                path.moveTo(px(36f), 92f); path.lineTo(px(36f), 56f); path.quadTo(px(36f), 40f, px(52f), 40f); path.lineTo(px(62f), 40f)
                c.drawPath(path, p); head(c, px(82f), 40f, md(0.0))
            }
            9, 16 -> { // légèrement
                path.moveTo(px(40f), 92f); path.lineTo(px(40f), 64f); path.lineTo(px(58f), 46f)
                c.drawPath(path, p); head(c, px(74f), 30f, md(-45.0))
            }
            11, 14 -> { // fortement
                path.moveTo(px(34f), 92f); path.lineTo(px(34f), 44f); path.lineTo(px(55f), 59f)
                c.drawPath(path, p); head(c, px(72f), 70f, md(34.0))
            }
            12, 13 -> { // demi-tour
                path.moveTo(px(66f), 92f); path.lineTo(px(66f), 44f); path.cubicTo(px(66f), 20f, px(34f), 20f, px(34f), 44f); path.lineTo(px(34f), 56f)
                c.drawPath(path, p); head(c, px(34f), 78f, 90.0)
            }
            18, 20, 23, 19, 21, 24 -> { // sortie / bifurcation : la voie à prendre en blanc, l'autre atténuée
                p.alpha = 90
                val straight = Path(); straight.moveTo(50f, 56f); straight.lineTo(50f, 30f)
                c.drawPath(straight, p); head(c, 50f, 14f, -90.0)
                p.alpha = 255
                path.moveTo(50f, 92f); path.lineTo(50f, 56f); path.lineTo(px(52f), 52f); path.lineTo(px(66f), 40f)
                c.drawPath(path, p); head(c, px(80f), 26f, md(-45.0))
            }
            26, 27 -> { // rond-point : sortie à prendre mise en évidence (circulation à droite = sens inverse des aiguilles)
                val n = if (exitNo in 1..8) exitNo else 2
                val cx = 50f; val cy = 54f; val rr = 15f
                p.alpha = 110; p.strokeWidth = 8f
                c.drawCircle(cx, cy, rr, p)
                p.alpha = 255; p.strokeWidth = 11f
                path.moveTo(50f, 96f); path.lineTo(50f, cy + rr)
                c.drawPath(path, p)
                val sweep = -(90f * n).coerceAtMost(330f)
                c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 90f, sweep, false, p)
                val deg = 90.0 - 90.0 * n
                val r = Math.toRadians(deg)
                val dx = Math.cos(r).toFloat(); val dy = Math.sin(r).toFloat()
                val ex = Path(); ex.moveTo(cx + dx * rr, cy + dy * rr); ex.lineTo(cx + dx * 28f, cy + dy * 28f)
                c.drawPath(ex, p); head(c, cx + dx * 42f, cy + dy * 42f, deg)
            }
            4, 5, 6 -> { // arrivée : drapeau
                p.strokeWidth = 9f
                c.drawLine(36f, 90f, 36f, 14f, p)
                p.style = Paint.Style.FILL
                val f = Path(); f.moveTo(36f, 14f); f.lineTo(82f, 28f); f.lineTo(36f, 46f); f.close()
                c.drawPath(f, p)
            }
            else -> { // tout droit
                path.moveTo(50f, 92f); path.lineTo(50f, 38f)
                c.drawPath(path, p); head(c, 50f, 16f, -90.0)
            }
        }
        c.restore()
    }
}
