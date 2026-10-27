package fr.djstef.gps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Station-service avec ses prix (€/L) par carburant. */
data class Station(val id: String, val pt: Pt, val address: String, val city: String, val prices: Map<String, Double>, val dist: Double)

data class Parking(val pt: Pt, val name: String, val fee: String?, val capacity: String?, val kind: String?, val dist: Double)

object Fuel {
    /** Libellé affiché -> préfixe du champ de l'API (ex. gazole_prix). */
    val TYPES = linkedMapOf(
        "Gazole" to "gazole",
        "SP95-E10" to "e10",
        "SP95" to "sp95",
        "SP98" to "sp98",
        "E85" to "e85",
        "GPLc" to "gplc"
    )

    /** Prix instantanés du ministère (data.economie.gouv.fr, Licence Ouverte), autour d'un point. */
    suspend fun around(p: Pt, radiusKm: Int = 10): List<Station> = withContext(Dispatchers.IO) {
        val where = "within_distance(geom, geom'POINT(${p.lon} ${p.lat})', ${radiusKm}km)"
        val url = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/" +
            "prix-des-carburants-en-france-flux-instantane-v2/records?limit=100&where=" +
            URLEncoder.encode(where, "UTF-8")
        val arr = JSONObject(Http.get(url, 20000)).optJSONArray("results") ?: JSONArray()
        val out = ArrayList<Station>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val g = o.optJSONObject("geom")
            var lat = g?.optDouble("lat", Double.NaN) ?: Double.NaN
            var lon = g?.optDouble("lon", Double.NaN) ?: Double.NaN
            if (lat.isNaN() || lon.isNaN()) {
                lat = o.optDouble("latitude", Double.NaN) / 100000.0
                lon = o.optDouble("longitude", Double.NaN) / 100000.0
            }
            if (lat.isNaN() || lon.isNaN()) continue
            val prices = HashMap<String, Double>()
            for ((_, k) in TYPES) {
                val v = o.optDouble("${k}_prix", Double.NaN)
                if (!v.isNaN() && v > 0) prices[k] = v
            }
            val pt = Pt(lat, lon)
            out.add(Station(o.optString("id", "$i"), pt, o.optString("adresse", ""), o.optString("ville", ""), prices, Geo.dist(p, pt)))
        }
        out
    }

}

object Parkings {
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    /** Parkings OpenStreetMap (amenity=parking) autour d'un point, payant ou gratuit si renseigné. */
    suspend fun around(p: Pt, radiusM: Int = 1500): List<Parking> = withContext(Dispatchers.IO) {
        val q = "[out:json][timeout:15];(nwr(around:$radiusM,${p.lat},${p.lon})[amenity=parking][access!~\"private|customers\"];);out center tags 80;"
        var txt: String? = null
        for (e in ENDPOINTS) {
            try {
                txt = Http.post(e, "data=" + URLEncoder.encode(q, "UTF-8"), 20000)
                break
            } catch (_: Exception) {
            }
        }
        val t = txt ?: throw java.io.IOException("Overpass indisponible")
        val els = JSONObject(t).getJSONArray("elements")
        val out = ArrayList<Parking>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val c = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.getDouble("lat") else c?.optDouble("lat", Double.NaN) ?: Double.NaN
            val lon = if (e.has("lon")) e.getDouble("lon") else c?.optDouble("lon", Double.NaN) ?: Double.NaN
            if (lat.isNaN() || lon.isNaN()) continue
            val pt = Pt(lat, lon)
            val fee = when (tags.optString("fee")) {
                "yes" -> "Payant"
                "no" -> "Gratuit"
                else -> null
            }
            val kind = when (tags.optString("parking")) {
                "underground" -> "Souterrain"
                "multi-storey" -> "Étagé"
                "surface" -> "En surface"
                "street_side", "lane" -> "Le long de la rue"
                else -> null
            }
            out.add(Parking(pt, tags.optString("name", "Parking"), fee, tags.optString("capacity").ifBlank { null }, kind, Geo.dist(p, pt)))
        }
        out.sortedBy { it.dist }
    }
}

/** Un trajet enregistré pendant la navigation. */
data class Trip(
    val start: Long, val end: Long, val dist: Double, val maxKmh: Int,
    val destName: String, val dest: Pt?, val trace: List<Pt>
)

class TripStore(ctx: Context) {
    private val file = File(ctx.filesDir, "trips.json")

    fun all(): List<Trip> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val tr = o.getJSONArray("t")
            Trip(
                o.getLong("s"), o.getLong("e"), o.getDouble("d"), o.optInt("v"),
                o.optString("n"),
                if (o.has("dla")) Pt(o.getDouble("dla"), o.getDouble("dlo")) else null,
                (0 until tr.length() / 2).map { Pt(tr.getDouble(it * 2), tr.getDouble(it * 2 + 1)) }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(list: List<Trip>) {
        val arr = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("s", t.start); o.put("e", t.end); o.put("d", t.dist); o.put("v", t.maxKmh); o.put("n", t.destName)
            t.dest?.let { o.put("dla", it.lat); o.put("dlo", it.lon) }
            val tr = JSONArray()
            for (p in t.trace) { tr.put(Math.round(p.lat * 1e5) / 1e5); tr.put(Math.round(p.lon * 1e5) / 1e5) }
            o.put("t", tr)
            arr.put(o)
        }
        file.writeText(arr.toString())
    }

    fun add(t: Trip) {
        // Ignore les trajets quasi nuls ; garde les 100 derniers
        if (t.dist < 200) return
        write((listOf(t) + all()).take(100))
    }

    fun remove(start: Long) = write(all().filter { it.start != start })
    fun clear() = write(emptyList())
}

/** Pastilles rondes brillantes pour stations et parkings. */
object PoiArt {
    private val cache = HashMap<String, Bitmap>()

    fun bitmap(letter: String, color: String): Bitmap = cache.getOrPut("$letter$color") {
        val px = 96
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val col = Color.parseColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x66000000
        c.drawCircle(px / 2f + 2, px / 2f + 4, px * 0.42f, p)
        p.color = Color.WHITE
        c.drawCircle(px / 2f, px / 2f, px * 0.42f, p)
        p.shader = LinearGradient(0f, 0f, 0f, px.toFloat(), Gloss.shade(col, 1.3f), Gloss.shade(col, 0.65f), Shader.TileMode.CLAMP)
        c.drawCircle(px / 2f, px / 2f, px * 0.36f, p)
        p.shader = LinearGradient(0f, px * 0.12f, 0f, px * 0.5f, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(RectF(px * 0.2f, px * 0.14f, px * 0.8f, px * 0.52f), p)
        p.shader = null
        p.color = Color.WHITE
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = px * 0.46f
        c.drawText(letter, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p)
        b.density = 320
        b
    }
}

/** API Lufop (https://api.lufop.net) : radars fixes, mobiles, feux rouges, vitesse moyenne… Données ODbL 1.0. */
object Lufop {
    const val CREDIT = "Données : Lufop.net et contributeurs OpenStreetMap — ODbL 1.0"

    /** Radars autour d'un point (m=10 ≈ 1 km ; ici m=300 ≈ 30 km). Plan gratuit : 200 requêtes/jour. */
    suspend fun around(key: String, p: Pt): List<Radar> = withContext(Dispatchers.IO) {
        val url = "https://api.lufop.net/api?key=${URLEncoder.encode(key, "UTF-8")}&format=json&pays=fr" +
            "&q=${p.lat},${p.lon}&m=300&nbr=200"
        val txt = Http.get(url, 20000).trim()
        if (txt.startsWith("{")) {
            val o = JSONObject(txt)
            throw java.io.IOException(o.optString("error", o.optString("message", "réponse inattendue")))
        }
        val arr = JSONArray(txt)
        val out = ArrayList<Radar>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lat = o.optDouble("lat", Double.NaN)
            val lng = o.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue
            val n = o.optString("name").lowercase()
            val kind = when {
                n.contains("mobile") -> "Radar mobile"
                n.contains("feu") -> "Radar feu rouge"
                n.contains("tron") || n.contains("moyenne") || n.contains("section") -> "Vitesse moyenne"
                n.contains("chantier") || n.contains("travaux") -> "Danger"
                else -> "Radar fixe"
            }
            out.add(Radar("lufop-" + o.optString("ID", "$i"), Pt(lat, lng), kind, o.optString("vitesse").toIntOrNull()))
        }
        out
    }

    /** Vérifie la clé sans consommer de quota (format=meta). Retourne le texte brut de l'API. */
    suspend fun meta(key: String): String = withContext(Dispatchers.IO) {
        Http.get("https://api.lufop.net/api?key=${URLEncoder.encode(key, "UTF-8")}&format=meta", 15000)
    }
}

// ---------- Catégories autour de soi (Nominatim, zone limitée) ----------

object Nearby {
    suspend fun category(near: Pt, phrase: String, d: Double = 0.12): List<Place> = withContext(Dispatchers.IO) {
        val dLon = d / Math.cos(Math.toRadians(near.lat)).coerceAtLeast(0.2)
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=30&bounded=1&accept-language=fr&q=" +
            URLEncoder.encode(phrase, "UTF-8") +
            "&viewbox=${near.lon - dLon},${near.lat + d},${near.lon + dLon},${near.lat - d}"
        val arr = JSONArray(Http.get(url))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val parts = o.optString("display_name").split(",").map { it.trim() }
            val name = o.optString("name").ifBlank { parts.firstOrNull() ?: "" }
            val town = parts.drop(1).firstOrNull { p -> p.any { it.isLetter() } && !p.first().isDigit() } ?: ""
            out.add(Place(if (town.isNotBlank()) "$name, $town" else name, Pt(o.getString("lat").toDouble(), o.getString("lon").toDouble())))
        }
        out.sortedBy { Geo.dist(near, it.pt) }
    }
}

// ---------- Heure estimée avec trafic (TomTom Routing) ----------

object TomTomEta {
    /** Retourne (durée avec trafic en s, retard dû au trafic en s). */
    suspend fun eta(key: String, a: Pt, b: Pt): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val url = "https://api.tomtom.com/routing/1/calculateRoute/${a.lat},${a.lon}:${b.lat},${b.lon}/json" +
            "?key=${URLEncoder.encode(key, "UTF-8")}&traffic=true&travelMode=car"
        val root = JSONObject(Http.get(url, 20000))
        val sum = root.getJSONArray("routes").getJSONObject(0).getJSONObject("summary")
        Pair(sum.getDouble("travelTimeInSeconds"), sum.optDouble("trafficDelayInSeconds", 0.0))
    }
}

// ---------- Lever et coucher du soleil (mode nuit automatique) ----------

object Sun {
    /** true si le soleil est couché à cet instant pour cette position (approximation NOAA). */
    fun isNight(lat: Double, lon: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).also { it.timeInMillis = nowMs }
        val n = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val g = 2 * Math.PI / 365 * (n - 1)
        val decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) +
            0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g)
        val eot = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g))
        val latR = Math.toRadians(lat)
        val cosH = (Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl))) - Math.tan(latR) * Math.tan(decl)
        if (cosH > 1) return true
        if (cosH < -1) return false
        val ha = Math.toDegrees(Math.acos(cosH))
        val sunriseMin = 720 - 4 * (lon + ha) - eot
        val sunsetMin = 720 - 4 * (lon - ha) - eot
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60.0 + cal.get(java.util.Calendar.MINUTE)
        // Marge de 20 min après le coucher / avant le lever
        return nowMin < sunriseMin - 20 || nowMin > sunsetMin + 20
    }
}
