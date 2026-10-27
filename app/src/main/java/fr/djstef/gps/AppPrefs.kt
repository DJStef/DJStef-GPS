package fr.djstef.gps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("djstef_gps", Context.MODE_PRIVATE)

    fun bool(k: String, d: Boolean): Boolean = p.getBoolean(k, d)
    fun setBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    fun int(k: String, d: Int): Int = p.getInt(k, d)
    fun setInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    fun float(k: String, d: Float): Float = p.getFloat(k, d)
    fun setFloat(k: String, v: Float) = p.edit().putFloat(k, v).apply()
    fun str(k: String, d: String): String = p.getString(k, d) ?: d
    fun setStr(k: String, v: String) = p.edit().putString(k, v).apply()
    fun remove(k: String) = p.edit().remove(k).apply()

    // Favoris (Domicile / Travail) : "lat,lon,nom"
    fun fav(key: String): Place? {
        val s = p.getString("fav_$key", null) ?: return null
        val parts = s.split("|", limit = 3)
        if (parts.size < 3) return null
        val la = parts[0].toDoubleOrNull() ?: return null
        val lo = parts[1].toDoubleOrNull() ?: return null
        return Place(parts[2], Pt(la, lo))
    }

    fun setFav(key: String, place: Place) =
        p.edit().putString("fav_$key", "${place.pt.lat}|${place.pt.lon}|${place.name}").apply()

    fun clearFav(key: String) = p.edit().remove("fav_$key").apply()

    // Destinations récentes
    fun recents(): List<Place> {
        val s = p.getString("recents", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Place(o.getString("n"), Pt(o.getDouble("la"), o.getDouble("lo")))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecent(place: Place) {
        val list = ArrayList<Place>()
        list.add(place)
        for (r in recents()) if (r.name != place.name) list.add(r)
        val arr = JSONArray()
        for (r in list.take(6)) arr.put(JSONObject().put("n", r.name).put("la", r.pt.lat).put("lo", r.pt.lon))
        p.edit().putString("recents", arr.toString()).apply()
    }
}
