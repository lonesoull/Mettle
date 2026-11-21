package app.mettle.tracking

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A small on-phone log of what the all-day tracker did (started, stopped, gaps, GPS mode,
 * detected activity). Shown on the Tracking check screen and included in "Send diagnostics".
 * Keeps the last 400 events; never leaves the phone unless the user shares it.
 */
object Diag {
    private const val PREFS = "diag"
    private const val MAX = 400
    private val lock = Any()

    fun log(c: Context, event: String) {
        runCatching {
            synchronized(lock) {
                val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val arr = JSONArray(p.getString("log", "[]"))
                arr.put(JSONObject().put("t", System.currentTimeMillis()).put("e", event))
                val trimmed = if (arr.length() > MAX) JSONArray().also { out -> for (i in arr.length() - MAX until arr.length()) out.put(arr.get(i)) } else arr
                p.edit().putString("log", trimmed.toString()).apply()
            }
        }
    }

    fun events(c: Context): List<Pair<Long, String>> = runCatching {
        val arr = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getLong("t") to o.getString("e") } }
    }.getOrDefault(emptyList())

    /** Small key/value facts (last fix, last activity…) for the check screen. */
    fun put(c: Context, key: String, value: String) = runCatching {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("k_$key", value).putLong("t_$key", System.currentTimeMillis()).apply()
    }
    fun get(c: Context, key: String): Pair<String, Long>? {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("k_$key", null)?.let { it to p.getLong("t_$key", 0L) }
    }
}
