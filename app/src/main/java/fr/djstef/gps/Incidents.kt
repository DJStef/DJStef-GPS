package fr.djstef.gps

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Incident de circulation (TomTom Traffic Incident Details v5). */
data class Incident(
    val id: String,
    val pt: Pt,
    val cat: Int,
    val magnitude: Int,
    val desc: String,
    val from: String,
    val to: String,
    val roads: String,
    val lengthM: Int,
    val delayS: Int
) {
    val label: String
        get() = when (cat) {
            1 -> "Accident"
            2 -> "Brouillard"
            3 -> "Danger"
            4 -> "Pluie"
            5 -> "Verglas"
            6 -> "Bouchon"
            7 -> "Voie fermée"
            8 -> "Route fermée"
            9 -> "Travaux"
            10 -> "Vent fort"
            11 -> "Inondation"
            14 -> "Véhicule en panne"
            else -> "Incident"
        }
    val color: String
        get() = when (cat) {
            1 -> "#C62828"
            6 -> if (magnitude >= 3) "#C62828" else "#FF9F1C"
            7, 8 -> "#B71C1C"
            9 -> "#F9A825"
            14 -> "#FF9F1C"
            else -> "#546E7A"
        }
    /** Faut-il l'annoncer à voix haute quand il est sur la route ? */
    val speakable: Boolean get() = cat == 1 || cat == 7 || cat == 8 || cat == 14 || (cat == 6 && magnitude >= 2)
}

object Incidents {
    suspend fun fetch(key: String, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Incident> =
        withContext(Dispatchers.IO) {
            val fields = "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay," +
                "events{description,iconCategory},from,to,length,delay,roadNumbers}}}"
            val url = "https://api.tomtom.com/traffic/services/5/incidentDetails" +
                "?key=${URLEncoder.encode(key, "UTF-8")}" +
                "&bbox=$minLon,$minLat,$maxLon,$maxLat" +
                "&fields=${URLEncoder.encode(fields, "UTF-8")}" +
                "&language=fr-FR&timeValidityFilter=present"
            val arr = JSONObject(Http.get(url, 20000)).optJSONArray("incidents") ?: return@withContext emptyList()
            val out = ArrayList<Incident>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val g = o.getJSONObject("geometry")
                    val c = g.getJSONArray("coordinates")
                    val first = if (g.getString("type") == "Point") c else c.getJSONArray(0)
                    val p = o.getJSONObject("properties")
                    val ev = p.optJSONArray("events")
                    val desc = ev?.optJSONObject(0)?.optString("description", "") ?: ""
                    val roads = p.optJSONArray("roadNumbers")?.let { r -> (0 until r.length()).joinToString(" ") { r.getString(it) } } ?: ""
                    out.add(
                        Incident(
                            p.optString("id", "i$i"),
                            Pt(first.getDouble(1), first.getDouble(0)),
                            p.optInt("iconCategory", 0),
                            p.optInt("magnitudeOfDelay", 0),
                            desc,
                            p.optString("from", ""),
                            p.optString("to", ""),
                            roads,
                            p.optInt("length", 0),
                            p.optInt("delay", 0)
                        )
                    )
                } catch (_: Exception) {
                }
            }
            // On ignore les incidents « inconnus » sans description
            out.filter { it.cat != 0 || it.desc.isNotBlank() }
        }
}
