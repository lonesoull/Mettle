package app.mettle.insight

import android.content.Context
import app.mettle.MettleApplication
import app.mettle.data.db.ActivityEntity
import app.mettle.data.db.ActivityType
import app.mettle.data.db.LapKind
import app.mettle.data.remote.Backend
import app.mettle.ui.Fmt
import app.mettle.ui.workout.effortLabel
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId

@Serializable private data class FactsBody(val facts: String)

data class Insight(val text: String, val ai: Boolean)

/**
 * "Mettle Insight" for a workout: built-in rules always work (offline, free);
 * Gemini rewrites it when the admin has set it up and usage is under 80% of the limit.
 */
object Insights {
    suspend fun forActivity(context: Context, a: ActivityEntity): Insight {
        val prefs = context.getSharedPreferences("insights", Context.MODE_PRIVATE)
        prefs.getString(a.id, null)?.let { return Insight(it, prefs.getBoolean("${a.id}_ai", false)) }
        val (template, facts) = build(context, a)
        val ai = withTimeoutOrNull(8_000) {
            runCatching {
                val res = Backend.client.functions.invoke("insight", body = FactsBody(facts))
                Json.parseToJsonElement(res.bodyAsText()).jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
        val out = if (!ai.isNullOrBlank()) Insight(ai, true) else Insight(template, false)
        prefs.edit().putString(a.id, out.text).putBoolean("${a.id}_ai", out.ai).apply()
        return out
    }

    /** Rule-based insight plus the plain facts sent to the AI. */
    private suspend fun build(context: Context, a: ActivityEntity): Pair<String, String> {
        val db = (context.applicationContext as MettleApplication).database
        val zone = ZoneId.systemDefault()
        val laps = db.laps().forActivityNow(a.id)
        val efforts = db.bestEfforts().forActivityNow(a.id)
        val monthStart = Instant.ofEpochMilli(a.startedAt).atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sameType = db.activities().betweenNow(monthStart, a.startedAt).filter { it.type == a.type && it.id != a.id }
        val prs = efforts.filter { it.rankAtTime == 1 }.map { effortLabel(it.effort) }
        val splits = laps.filter { it.kind == LapKind.AUTO_KM && it.distanceM == 1000 }
        val sets = laps.filter { it.kind == LapKind.STAIR_SET }
        val facts = buildString {
            append("type=${a.type.name.lowercase()}; ")
            if (a.type == ActivityType.STAIRS) append("floors_up=${a.floorsUp}; floors_down=${a.floorsDown}; sets=${sets.size}; time=${Fmt.duration(a.elapsedS.toLong())}; ")
            else append("distance_km=${Fmt.km2(a.distanceM.toLong())}; moving=${Fmt.duration(a.movingS.toLong())}; pace=${Fmt.pace(a.avgPaceSKm)}/km; gap=${Fmt.pace(a.gapSKm)}/km; climb_m=${a.elevGainM.toInt()}; ")
            if (splits.size >= 2) append("km_splits=${splits.joinToString(",") { Fmt.pace(it.movingS.toDouble()) }}; ")
            if (sets.isNotEmpty()) append("set_climb_times=${sets.joinToString(",") { Fmt.duration((it.climbS ?: 0).toLong()) }}; ")
            if (prs.isNotEmpty()) append("personal_records=${prs.joinToString(",")}; ")
            a.rpe?.let { append("effort_1_10=$it; ") }
            a.avgHr?.let { append("avg_hr=$it; ") }
            append("same_type_this_month=${sameType.size}")
        }
        val template = when {
            prs.isNotEmpty() -> "New personal record: ${prs.joinToString(" and ")}. That's real progress."
            a.type == ActivityType.STAIRS && sets.size >= 3 -> {
                val first = sets.first().climbS ?: 0; val last = sets.last().climbS ?: 0
                if (last <= first) "Strong finish: your last set was as fast as your first, so your stair endurance is building."
                else "${sets.size} sets and ${a.floorsUp} floors up. Your climbs slowed by ${Fmt.duration((last - first).toLong())} from first to last set; try a little more rest between sets."
            }
            splits.size >= 2 && splits.takeLast(splits.size / 2).sumOf { it.movingS } < splits.take(splits.size / 2).sumOf { it.movingS } ->
                "Negative split: your second half was faster than your first. That's a well-paced ${a.type.name.lowercase()}."
            sameType.isNotEmpty() && a.distanceM > sameType.maxOf { it.distanceM } -> "Your longest ${a.type.name.lowercase()} this month: ${Fmt.km2(a.distanceM.toLong())} km."
            a.elevGainM >= 100 -> "A hilly one: ${a.elevGainM.toInt()} m of climbing. Your grade-adjusted pace was ${Fmt.pace(a.gapSKm)} /km."
            else -> "Consistency builds fitness, and this was workout ${sameType.size + 1} of its kind this month."
        }
        return template to facts
    }
}
