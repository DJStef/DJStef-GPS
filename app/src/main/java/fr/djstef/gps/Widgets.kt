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
