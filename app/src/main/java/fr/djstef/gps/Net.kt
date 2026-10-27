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

data class Step(
    val text: String,
    val type: String,
    val modifier: String,
    val name: String,
    val dist: Double,
    val at: Pt,
    val exit: Int
)

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
    suspend fun search(q: String): List<Place> = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&accept-language=fr&q=" +
            URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(Http.get(url))
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Place(o.getString("display_name"), Pt(o.getString("lat").toDouble(), o.getString("lon").toDouble())))
        }
        out
    }
}

// ---------- Itinéraires (OSRM, routing.openstreetmap.de) ----------

object Router {
    /** mode : car, bike ou foot */
    suspend fun route(mode: String, a: Pt, b: Pt): Route = withContext(Dispatchers.IO) {
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
                steps.add(Step(instr(type, mod, name, exit), type, mod, name, s.getDouble("distance"), Pt(loc.getDouble(1), loc.getDouble(0)), exit))
            }
        }
        Route(pts, steps, r.getDouble("distance"), r.getDouble("duration"))
    }

    private fun instr(type: String, mod: String, name: String, exit: Int): String {
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
    val TYPES = listOf("Radar mobile", "Contrôle", "Accident", "Danger sur la route")

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
