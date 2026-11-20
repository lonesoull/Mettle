package app.mettle.space

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Learned Home / Work locations, kept on the phone. */
object Places {
    fun save(context: Context, places: List<KnownPlace>) {
        val arr = JSONArray(); places.forEach { arr.put(JSONObject().put("label", it.label).put("lat", it.lat).put("lon", it.lon)) }
        context.getSharedPreferences("places", Context.MODE_PRIVATE).edit().putString("known", arr.toString()).apply()
    }
    fun known(context: Context): List<KnownPlace> {
        val arr = JSONArray(context.getSharedPreferences("places", Context.MODE_PRIVATE).getString("known", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> KnownPlace(o.getString("label"), o.getDouble("lat"), o.getDouble("lon")) } }
    }
}
