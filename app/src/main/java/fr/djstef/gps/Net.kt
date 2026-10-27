package fr.djstef.gps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------- Modèles ----------

data class Pt(val lat: Double, val lon: Double)

/** kind : code de manœuvre (type Valhalla) servant à choisir la flèche affichée. */
data class Step(
    val text: String,
    val kind: Int,
    val name: String,
    val dist: Double,
    val at: Pt
)

/** true = autorisé. */
data class RouteOpts(val tolls: Boolean = true, val highways: Boolean = true, val ferries: Boolean = true)

data class Route(val pts: List<Pt>, val steps: List<Step>, val dist: Double, val dur: Double)

data class Place(val name: String, val pt: Pt)

data class Radar(val id: String, val pt: Pt, val kind: String, val limit: Int?, val live: Boolean = false)

// ---------- Géométrie ----------

object Geo {
    private const val R = 6371000.0

    fun dist(a: Pt, b: Pt): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val x = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(x), sqrt(1 - x))
    }

    fun bearing(a: Pt, b: Pt): Double {
        val la = Math.toRadians(a.lat)
        val lb = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(lb)
        val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    /** Distance d'un point à un segment, en mètres (projection locale). */
    fun distToSegment(p: Pt, a: Pt, b: Pt): Double {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * k * 111320.0
        val ay = (a.lat - p.lat) * 110540.0
        val bx = (b.lon - p.lon) * k * 111320.0
        val by = (b.lat - p.lat) * 110540.0
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    fun distToRoute(p: Pt, pts: List<Pt>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val d = distToSegment(p, pts[i], pts[i + 1])
            if (d < best) best = d
        }
        return best
    }
}

// ---------- HTTP ----------

object Http {
    private const val UA = "DJStefGPS/0.1"

    fun get(url: String, timeout: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", UA)
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun post(url: String, body: String, timeout: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", UA)
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() > 0) {
                dest.delete()
                tmp.renameTo(dest)
            }
        } finally {
            c.disconnect()
            tmp.delete()
        }
    }
}

// ---------- Recherche d'adresses (Nominatim / OpenStreetMap) ----------

object Geocoder {
    private val STOP = setOf("le", "la", "les", "l", "un", "une", "de", "du", "des", "d", "au", "aux", "et", "chez")
    private val TYPES = setOf(
        "camping", "campings", "restaurant", "hotel", "hôtel", "boulangerie", "pharmacie", "supermarche",
        "supermarché", "garage", "mairie", "gare", "parking", "bar", "cafe", "café", "gite", "gîte", "centre", "plage"
    )

    private fun fold(w: String) = w.map {
        if (it in "àâäáéèêëíìîïóòôöúùûüçœæ") '.' else it
    }.joinToString("")

    /** Recherche d'adresses et de lieux (« camping les peupliers à colombier », commerces…). */
    suspend fun search(q0: String, near: Pt? = null): List<Place> = withContext(Dispatchers.IO) {
        val q = q0.trim()
        val out = ArrayList<Place>()
        fun add(pl: Place) {
            if (out.none { Geo.dist(it.pt, pl.pt) < 120.0 }) out.add(pl)
        }
        // « nom à ville » → nom + ville
        val m = Regex("^(.{3,}?)(?:\\s+(?:à|a|au|aux|près de|pres de|sur|dans|en)\\s+|\\s*,\\s*|\\s+-\\s+)(.{3,})$", RegexOption.IGNORE_CASE).find(q)
        val ws = q.split(Regex("\\s+"))
        // Sans séparateur : « camping les peupliers colombier » → dernier mot = ville si le premier est un type de lieu
        val implicit = m == null && ws.size >= 3 && ws[0].lowercase() in TYPES
        val what = if (implicit) ws.dropLast(1).joinToString(" ") else m?.groupValues?.get(1)?.trim()
        val where = if (implicit) ws.last() else m?.groupValues?.get(2)?.trim()

        val named = ArrayList<Place>()
        if (what != null && where != null) {
            try {
                val cities = JSONArray(Http.get(
                    "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=4&countrycodes=fr&accept-language=fr&q=" +
                        URLEncoder.encode(where, "UTF-8")))
                val words = what.lowercase().split(Regex("[\\s'’-]+")).filter { it.length > 1 && it !in STOP && it !in TYPES }
                val rx = words.joinToString(".*") { fold(Regex.escape(it).replace("\\Q", "").replace("\\E", "")) }
                for (i in 0 until cities.length()) {
                    val c = cities.getJSONObject(i)
                    val lat = c.getString("lat").toDouble()
                    val lon = c.getString("lon").toDouble()
                    val cname = c.optString("name", where)
                    val dep = c.optString("display_name").split(",").map { it.trim() }.let { l -> if (l.size >= 3) l[l.size - 3] else "" }
                    val tag = if (dep.isNotBlank() && dep != cname) "$cname ($dep)" else cname
                    if (rx.isNotBlank()) {
                        try {
                            val oq = "[out:json][timeout:15];nwr[\"name\"~\"$rx\",i](around:12000,$lat,$lon);out center tags 8;"
                            var txt: String? = null
                            for (e in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
                                try { txt = Http.post(e, "data=" + URLEncoder.encode(oq, "UTF-8"), 20000); break } catch (_: Exception) {}
                            }
                            val els = JSONObject(txt ?: "{\"elements\":[]}").getJSONArray("elements")
                            for (k in 0 until els.length()) {
                                val e = els.getJSONObject(k)
                                val tags = e.optJSONObject("tags") ?: continue
                                val ce = e.optJSONObject("center")
                                val la = if (e.has("lat")) e.getDouble("lat") else ce?.optDouble("lat", Double.NaN) ?: Double.NaN
                                val lo = if (e.has("lon")) e.getDouble("lon") else ce?.optDouble("lon", Double.NaN) ?: Double.NaN
                                if (la.isNaN() || lo.isNaN()) continue
                                val town = tags.optString("addr:city", "").ifBlank { tag }
                                named.add(Place(tags.optString("name") + ", " + town, Pt(la, lo)))
                            }
                        } catch (_: Exception) {
                        }
                    }
                    // Photon biaisé sur la ville candidate
                    try {
                        val feats = JSONObject(Http.get("https://photon.komoot.io/api/?limit=4&lang=fr&lat=$lat&lon=$lon&q=" +
                            URLEncoder.encode(what, "UTF-8"))).optJSONArray("features") ?: JSONArray()
                        for (k in 0 until feats.length()) {
                            val f = feats.getJSONObject(k)
                            val pr = f.optJSONObject("properties") ?: continue
                            val co = f.getJSONObject("geometry").getJSONArray("coordinates")
                            val pt = Pt(co.getDouble(1), co.getDouble(0))
                            if (Geo.dist(pt, Pt(lat, lon)) > 25000.0) continue
                            val parts = ArrayList<String>()
                            for (key in listOf("name", "street", "city", "postcode")) {
                                val v = pr.optString(key, "")
                                if (v.isNotBlank() && v !in parts) parts.add(v)
                            }
                            if (parts.isNotEmpty()) named.add(Place(parts.joinToString(", "), pt))
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        for (p in named) add(p)

        try {
            var url = "https://photon.komoot.io/api/?limit=8&lang=fr&q=" + URLEncoder.encode(q, "UTF-8")
            if (near != null) url += "&lat=${near.lat}&lon=${near.lon}"
            val feats = JSONObject(Http.get(url)).optJSONArray("features") ?: JSONArray()
            for (i in 0 until feats.length()) {
                val f = feats.getJSONObject(i)
                val pr = f.optJSONObject("properties") ?: continue
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val parts = ArrayList<String>()
                for (k in listOf("name", "street", "city", "postcode", "state")) {
                    val v = pr.optString(k, "")
                    if (v.isNotBlank() && v !in parts) parts.add(v)
                }
                if (parts.isEmpty()) continue
                add(Place(parts.joinToString(", "), Pt(c.getDouble(1), c.getDouble(0))))
            }
        } catch (_: Exception) {
        }
        try {
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&accept-language=fr&q=" +
                URLEncoder.encode(q, "UTF-8")
            val arr = JSONArray(Http.get(url))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(Place(o.getString("display_name"), Pt(o.getString("lat").toDouble(), o.getString("lon").toDouble())))
            }
        } catch (_: Exception) {
            if (out.isEmpty()) throw java.io.IOException("recherche indisponible")
        }
        // Les résultats qui mentionnent la ville demandée passent en premier
        if (where != null) {
            fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val w = norm(where)
            val (hit, rest) = out.partition { norm(it.name).contains(w) }
            return@withContext hit + rest
        }
        out
    }
}

// ---------- Itinéraires (Valhalla, avec OSRM en secours) ----------

object Router {
    /** mode : car, bike ou foot */
    suspend fun route(mode: String, a: Pt, b: Pt, opts: RouteOpts = RouteOpts(), exclude: List<Pt> = emptyList()): Route =
        withContext(Dispatchers.IO) {
            try {
                valhalla(mode, a, b, opts, exclude)
            } catch (e: Exception) {
                if (exclude.isNotEmpty()) throw e
                osrm(mode, a, b)
            }
        }

    private fun valhalla(mode: String, a: Pt, b: Pt, opts: RouteOpts, exclude: List<Pt>): Route {
        val costing = when (mode) {
            "bike" -> "bicycle"
            "foot" -> "pedestrian"
            else -> "auto"
        }
        val co = JSONObject()
        if (mode == "car") {
            val auto = JSONObject()
            if (!opts.tolls) auto.put("use_tolls", 0.0).put("exclude_tolls", true)
            if (!opts.highways) auto.put("use_highways", 0.0).put("exclude_highways", true)
            if (!opts.ferries) auto.put("use_ferry", 0.0).put("exclude_ferries", true)
            co.put("auto", auto)
        }
        val locs = JSONArray()
            .put(JSONObject().put("lat", a.lat).put("lon", a.lon).put("type", "break"))
            .put(JSONObject().put("lat", b.lat).put("lon", b.lon).put("type", "break"))
        val body = JSONObject()
            .put("locations", locs)
            .put("costing", costing)
            .put("costing_options", co)
            .put("directions_options", JSONObject().put("language", "fr-FR").put("units", "kilometers"))
        if (exclude.isNotEmpty()) {
            val ex = JSONArray()
            for (p in exclude.take(50)) ex.put(JSONObject().put("lat", p.lat).put("lon", p.lon))
            body.put("exclude_locations", ex)
        }
        val root = JSONObject(Http.post("https://valhalla1.openstreetmap.de/route", body.toString(), 30000))
        if (root.has("error")) throw IOException(root.optString("error"))
        val trip = root.getJSONObject("trip")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val pts = decodePolyline6(leg.getString("shape"))
        val steps = ArrayList<Step>()
        val man = leg.getJSONArray("maneuvers")
        for (i in 0 until man.length()) {
            val m = man.getJSONObject(i)
            val idx = m.optInt("begin_shape_index", 0).coerceIn(0, pts.size - 1)
            val names = m.optJSONArray("street_names")
            val name = if (names != null && names.length() > 0) names.getString(0) else ""
            steps.add(
                Step(
                    m.optString("instruction").trim().trimEnd('.'),
                    m.optInt("type", 8),
                    name,
                    m.optDouble("length", 0.0) * 1000.0,
                    pts[idx]
                )
            )
        }
        val sum = trip.getJSONObject("summary")
        return Route(pts, steps, sum.getDouble("length") * 1000.0, sum.getDouble("time"))
    }

    private fun decodePolyline6(s: String): List<Pt> {
        val out = ArrayList<Pt>()
        var i = 0
        var lat = 0
        var lon = 0
        while (i < s.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            shift = 0
            result = 0
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            out.add(Pt(lat / 1e6, lon / 1e6))
        }
        return out
    }

    // --- Secours : OSRM (routing.openstreetmap.de) ---

    private fun osrm(mode: String, a: Pt, b: Pt): Route {
        val url = "https://routing.openstreetmap.de/routed-$mode/route/v1/driving/" +
            "${a.lon},${a.lat};${b.lon},${b.lat}?overview=full&geometries=geojson&steps=true"
        val root = JSONObject(Http.get(url, 25000))
        if (root.optString("code") != "Ok") throw IOException("Aucun itinéraire")
        val r = root.getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<Pt>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            pts.add(Pt(c.getDouble(1), c.getDouble(0)))
        }
        val steps = ArrayList<Step>()
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val st = legs.getJSONObject(l).getJSONArray("steps")
            for (i in 0 until st.length()) {
                val s = st.getJSONObject(i)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val type = m.optString("type")
                val mod = m.optString("modifier")
                val name = s.optString("name")
                val exit = m.optInt("exit", 0)
                steps.add(Step(osrmText(type, mod, name, exit), osrmKind(type, mod), name, s.getDouble("distance"), Pt(loc.getDouble(1), loc.getDouble(0))))
            }
        }
        return Route(pts, steps, r.getDouble("distance"), r.getDouble("duration"))
    }

    private fun osrmKind(type: String, mod: String): Int = when (type) {
        "depart" -> 1
        "arrive" -> 4
        "roundabout", "rotary", "roundabout turn" -> 26
        else -> when (mod) {
            "left" -> 15
            "right" -> 10
            "slight left" -> 16
            "slight right" -> 9
            "sharp left" -> 14
            "sharp right" -> 11
            "uturn" -> 12
            else -> 8
        }
    }

    private fun osrmText(type: String, mod: String, name: String, exit: Int): String {
        val dir = when (mod) {
            "left" -> "à gauche"
            "right" -> "à droite"
            "slight left" -> "légèrement à gauche"
            "slight right" -> "légèrement à droite"
            "sharp left" -> "franchement à gauche"
            "sharp right" -> "franchement à droite"
            else -> "tout droit"
        }
        val on = if (name.isNotBlank()) " sur $name" else ""
        return when (type) {
            "depart" -> "Démarrez$on"
            "arrive" -> "Vous êtes arrivé"
            "roundabout", "rotary", "roundabout turn" ->
                if (exit > 0) "Au rond-point, prenez la ${exit}${if (exit == 1) "ère" else "ème"} sortie$on" else "Prenez le rond-point$on"
            "merge" -> "Insérez-vous $dir$on"
            "fork" -> "Serrez $dir$on"
            else -> when (mod) {
                "uturn" -> "Faites demi-tour$on"
                "straight", "" -> "Continuez tout droit$on"
                else -> "Tournez $dir$on"
            }
        }
    }
}

// ---------- Limitations de vitesse (OpenStreetMap, tag maxspeed, via Overpass) ----------

object SpeedLimits {
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )
    private const val ROADS = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|" +
        "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|service"

    /** Limite en km/h de la route sous la position donnée, ou null si inconnue. */
    suspend fun at(p: Pt, heading: Double?): Int? = withContext(Dispatchers.IO) {
        val q = "[out:json][timeout:6];way(around:25,${p.lat},${p.lon})[highway~\"^($ROADS)\$\"];out tags geom;"
        var txt: String? = null
        for (ep in ENDPOINTS) {
            try {
                txt = Http.get("$ep?data=" + URLEncoder.encode(q, "UTF-8"), 9000)
                break
            } catch (e: Exception) {
            }
        }
        val t = txt ?: throw IOException("Overpass indisponible")
        val els = JSONObject(t).getJSONArray("elements")
        var bestTags: JSONObject? = null
        var bestScore = Double.MAX_VALUE
        var bestBearing = 0.0
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val g = e.optJSONArray("geometry") ?: continue
            var minD = Double.MAX_VALUE
            var segB = 0.0
            for (j in 0 until g.length() - 1) {
                val a = g.getJSONObject(j)
                val b = g.getJSONObject(j + 1)
                val pa = Pt(a.getDouble("lat"), a.getDouble("lon"))
                val pb = Pt(b.getDouble("lat"), b.getDouble("lon"))
                val d = Geo.distToSegment(p, pa, pb)
                if (d < minD) {
                    minD = d
                    segB = Geo.bearing(pa, pb)
                }
            }
            val score = minD + if (tags.optString("highway") == "service") 15.0 else 0.0
            if (score < bestScore) {
                bestScore = score
                bestTags = tags
                bestBearing = segB
            }
        }
        val tags = bestTags ?: return@withContext null
        val backward = heading != null && Geo.angleDiff(bestBearing, heading) > 90.0
        val dirKey = if (backward) "maxspeed:backward" else "maxspeed:forward"
        val raw = tags.optString(dirKey).ifBlank { tags.optString("maxspeed") }
        val v = parse(raw)
        if (v != null) return@withContext v
        when (tags.optString("highway")) {
            "motorway" -> 130
            "residential" -> 50
            "living_street" -> 20
            else -> null
        }
    }

    private fun parse(s: String): Int? {
        val v = s.trim().lowercase()
        if (v.isEmpty()) return null
        return when (v) {
            "fr:urban" -> 50
            "fr:rural" -> 80
            "fr:motorway" -> 130
            "walk" -> 10
            else -> Regex("^(\\d{1,3})( ?km/h)?$").find(v)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}

// ---------- Radars fixes (data.gouv.fr, Licence Ouverte 2.0) ----------

object RadarRepository {
    private const val URL = "https://www.data.gouv.fr/api/1/datasets/r/17f7cfd9-a5fe-4b6a-9f5d-3625feaa396e"
    private const val MAX_AGE = 7L * 24 * 3600 * 1000

    suspend fun load(ctx: Context): List<Radar> = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "radars.csv")
        val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE
        if (stale) {
            try {
                Http.download(URL, f)
            } catch (e: Exception) {
                // on garde l'ancien fichier s'il existe
            }
        }
        if (f.exists()) parse(f.readText()) else emptyList()
    }

    private fun splitCsv(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        for (ch in line) {
            when {
                ch == '"' -> q = !q
                ch == sep && !q -> {
                    out.add(sb.toString().trim())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun kindName(code: String): String = when (code.trim().uppercase()) {
        "ETF" -> "Radar fixe"
        "ETD" -> "Radar discriminant"
        "ETT" -> "Radar tourelle"
        "ETU" -> "Radar urbain"
        "ETVM" -> "Radar vitesse moyenne"
        "ETFR" -> "Radar feu rouge"
        "ETPN" -> "Radar passage à niveau"
        "" -> "Radar"
        else -> "Radar $code"
    }

    fun parse(text: String): List<Radar> {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val sep = if (lines[0].count { it == ';' } >= lines[0].count { it == ',' }) ';' else ','
        val head = splitCsv(lines[0], sep).map { it.lowercase() }
        var iLat = head.indexOfFirst { it == "latitude" || it == "lat" || it.contains("latitude") }
        var iLon = head.indexOfFirst { it == "longitude" || it == "lon" || it == "lng" || it.contains("longitude") }
        val iGeo = head.indexOfFirst { it.contains("geo") || it.contains("coord") }
        val iType = head.indexOfFirst { it.contains("type") }
        val iVma = head.indexOfFirst { it.contains("vma") || it.contains("vitesse") }
        val iId = head.indexOfFirst { it == "id" || it.startsWith("id") }
        val num = Regex("-?\\d+[.,]\\d+")
        val out = ArrayList<Radar>()
        for (n in 1 until lines.size) {
            val c = splitCsv(lines[n], sep)
            var lat: Double? = null
            var lon: Double? = null
            if (iLat >= 0 && iLon >= 0 && iLat < c.size && iLon < c.size) {
                lat = c[iLat].replace(',', '.').toDoubleOrNull()
                lon = c[iLon].replace(',', '.').toDoubleOrNull()
            } else if (iGeo >= 0 && iGeo < c.size) {
                val m = num.findAll(c[iGeo]).map { it.value.replace(',', '.').toDouble() }.toList()
                if (m.size >= 2) {
                    if (m[0] in 40.0..52.0 || m[0] in -25.0..20.0 && m[1] !in 40.0..52.0) {
                        lat = m[0]; lon = m[1]
                    } else {
                        lat = m[1]; lon = m[0]
                    }
                }
            }
            if (lat == null || lon == null || (lat == 0.0 && lon == 0.0)) continue
            val type = if (iType in c.indices) c[iType] else ""
            val vma = if (iVma in c.indices) Regex("\\d+").find(c[iVma])?.value?.toIntOrNull() else null
            val id = if (iId in c.indices) c[iId] else "r$n"
            out.add(Radar("fix-$id", Pt(lat, lon), kindName(type), vma))
        }
        return out
    }
}

// ---------- Signalements des utilisateurs (radars mobiles, contrôles, dangers) ----------

data class Report(val id: String, val type: String, val pt: Pt, val ts: Long, val conf: Int)

object ReportClient {
    val TYPES = listOf("Radar fixe", "Radar mobile", "Radar feu rouge", "Vitesse moyenne", "Contrôle", "Accident", "Danger")

    suspend fun fetch(base: String, around: Pt): List<Report> = withContext(Dispatchers.IO) {
        val url = base.trimEnd('/') + "/reports?lat=${around.lat}&lon=${around.lon}&r=30000"
        val arr = JSONObject(Http.get(url)).getJSONArray("reports")
        val out = ArrayList<Report>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Report(o.getString("id"), o.getString("type"), Pt(o.getDouble("lat"), o.getDouble("lon")), o.optLong("ts"), o.optInt("conf", 1)))
        }
        out
    }

    suspend fun send(base: String, type: String, at: Pt) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("type", type).put("lat", at.lat).put("lon", at.lon).toString()
        Http.post(base.trimEnd('/') + "/report", body)
    }

    suspend fun confirm(base: String, id: String) = withContext(Dispatchers.IO) {
        Http.post(base.trimEnd('/') + "/confirm", JSONObject().put("id", id).toString())
    }
}
