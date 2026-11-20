package app.mettle.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.mettle.data.remote.Backend
import app.mettle.ui.Fmt
import app.mettle.ui.components.Section
import app.mettle.ui.components.Stat
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable data class FeedItem(
    val id: String, @SerialName("user_id") val userId: String, @SerialName("display_name") val name: String,
    val type: String, val title: String, @SerialName("started_at") val startedAt: String,
    @SerialName("moving_s") val movingS: Int = 0, @SerialName("distance_m") val distanceM: Int = 0,
    @SerialName("floors_up") val floorsUp: Int = 0, @SerialName("avg_pace_s_km") val pace: Double? = null,
    @SerialName("route_geojson") val route: String? = null, @SerialName("kudos_count") val kudos: Long = 0,
    @SerialName("my_kudos") val myKudos: Boolean = false,
)
@Serializable private data class KudosRow(@SerialName("activity_id") val activityId: String, @SerialName("user_id") val userId: String)

suspend fun loadGroupFeed(): List<FeedItem> = runCatching {
    val me = Backend.client.auth.currentUserOrNull()?.id
    Backend.client.postgrest.rpc("get_feed", kotlinx.serialization.json.buildJsonObject { put("p_limit", kotlinx.serialization.json.JsonPrimitive(20)) })
        .decodeList<FeedItem>().filter { it.userId != me }
}.getOrDefault(emptyList())

/** Someone else's shared workout, with a route sketch and kudos. */
@Composable
fun GroupFeedCard(item: FeedItem) {
    val scope = rememberCoroutineScope()
    var mine by remember { mutableStateOf(item.myKudos) }
    var count by remember { mutableStateOf(item.kudos) }
    val pts = remember(item.route) {
        runCatching { Json.parseToJsonElement(item.route!!).jsonObject["coordinates"]!!.jsonArray.map { c -> c.jsonArray.let { it[1].jsonPrimitive.double to it[0].jsonPrimitive.double } } }.getOrDefault(emptyList())
    }
    Section {
        Text("${item.name} · " + Instant.parse(item.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE h:mm a")),
            style = MettleType.Label, color = MettleColors.Sub)
        Text(item.title, style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.fillMaxWidth()) {
            if (item.type == "stairs") Stat("Floors up", "${item.floorsUp}", Modifier.weight(1f))
            else { Stat("Distance", Fmt.km2(item.distanceM.toLong()), Modifier.weight(1f), unit = "km"); Stat("Pace", Fmt.pace(item.pace), Modifier.weight(1f), unit = "/km") }
            Stat("Time", Fmt.duration(item.movingS.toLong()), Modifier.weight(1f))
        }
        if (pts.size >= 2) {
            val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }; val minLon = pts.minOf { it.second }; val maxLon = pts.maxOf { it.second }
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val sx = size.width / (maxLon - minLon).coerceAtLeast(1e-6); val sy = size.height / (maxLat - minLat).coerceAtLeast(1e-6)
                val s = minOf(sx, sy) * 0.9; val ox = (size.width - (maxLon - minLon) * s) / 2; val oy = (size.height - (maxLat - minLat) * s) / 2
                val path = Path()
                pts.forEachIndexed { i, (la, lo) ->
                    val x = (ox + (lo - minLon) * s).toFloat(); val y = (size.height - oy - (la - minLat) * s).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, MettleColors.Orange, style = Stroke(6f, cap = StrokeCap.Round))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (mine) "👍 Kudos given" else "👍 Give kudos", style = MaterialTheme.typography.titleSmall,
                color = if (mine) MettleColors.Orange else MettleColors.Ink2,
                modifier = Modifier.clickable {
                    val was = mine; mine = !was; count += if (was) -1 else 1
                    scope.launch {
                        runCatching {
                            val me = Backend.client.auth.currentUserOrNull()!!.id
                            if (was) Backend.client.from("kudos").delete { filter { eq("activity_id", item.id); eq("user_id", me) } }
                            else Backend.client.from("kudos").insert(KudosRow(item.id, me))
                        }.onFailure { mine = was; count += if (was) 1 else -1 }
                    }
                })
            if (count > 0) Text("$count", style = MettleType.Label, color = MettleColors.Sub)
        }
    }
}
