package app.mettle.ui.admin

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.maps.android.compose.rememberMarkerState
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.mettle.data.remote.Backend
import app.mettle.space.KnownPlace
import app.mettle.sync.DayTrackFile
import app.mettle.ui.Fmt
import app.mettle.ui.components.Section
import app.mettle.ui.components.Stat
import app.mettle.ui.components.TopBar
import app.mettle.ui.space.ReplayPoint
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleIcons
import app.mettle.ui.theme.MettleType
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

@Serializable data class MemberProfile(val id: String, @SerialName("display_name") val name: String, val role: String = "member")
@Serializable data class MemberLive(
    @SerialName("user_id") val userId: String, @SerialName("display_name") val name: String,
    val lat: Double? = null, val lon: Double? = null, @SerialName("altitude_m") val altitudeM: Double? = null,
    val floor: Int? = null, @SerialName("accuracy_m") val accuracyM: Float? = null, val state: String? = null,
    @SerialName("battery_pct") val battery: Int? = null, val charging: Boolean? = null,
    @SerialName("tracking_ok") val trackingOk: Boolean = true, val problem: String? = null,
    @SerialName("position_source") val source: String? = null, @SerialName("updated_at") val updatedAt: String,
    @SerialName("phone_capabilities") val capabilities: JsonObject? = null,
)
@Serializable data class MemberDay(
    @SerialName("user_id") val userId: String, val day: String, val steps: Int = 0, @SerialName("distance_m") val distanceM: Int = 0,
    @SerialName("active_min") val activeMin: Int = 0, @SerialName("floors_up") val floorsUp: Int = 0, @SerialName("floors_down") val floorsDown: Int = 0,
    @SerialName("sitting_min") val sittingMin: Int = 0, @SerialName("tracked_min") val trackedMin: Int = 0,
)
@Serializable data class MemberActivity(
    val id: String, val title: String, val type: String, @SerialName("started_at") val startedAt: String,
    @SerialName("distance_m") val distanceM: Int = 0, @SerialName("moving_s") val movingS: Int = 0, @SerialName("floors_up") val floorsUp: Int = 0,
)
@Serializable data class MemberGap(@SerialName("start_at") val startAt: String, @SerialName("end_at") val endAt: String? = null, val reason: String)
@Serializable data class AckRow(val status: String = "acknowledged", @SerialName("acknowledged_at") val at: String = Instant.now().toString())
@Serializable data class MemberStay(val label: String? = null, val lat: Double, val lon: Double)

private val json = Json { ignoreUnknownKeys = true }
private fun ago(iso: String): String {
    val m = Duration.between(Instant.parse(iso), Instant.now()).toMinutes()
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
}
private fun problemText(p: String?) = when (p) {
    null -> null; "battery_saver" -> "Battery saver is on"; "battery_optimised" -> "Battery not set to Unrestricted"
    "tracking_paused" -> "Tracking switched off"; else -> p.replace('_', ' ')
}

/** Admin-only overview: live map and every member's day. */
@Composable
fun AdminScreen(onMember: (String, String) -> Unit, onBack: () -> Unit) {
    var members by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
    var live by remember { mutableStateOf<List<MemberLive>>(emptyList()) }
    var today by remember { mutableStateOf<List<MemberDay>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var alerts by remember { mutableStateOf<List<app.mettle.safety.AlertView>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(refresh) {
        while (true) {
            runCatching {
                alerts = Backend.client.from("alerts_live").select {
                    filter { gt("created_at", Instant.now().minus(Duration.ofDays(2)).toString()) }; order("created_at", Order.DESCENDING); limit(20)
                }.decodeList()
                members = Backend.client.from("profiles").select().decodeList()
                live = Backend.client.from("member_live").select().decodeList()
                val d = LocalDate.now(ZoneId.systemDefault()).toString()
                today = Backend.client.from("daily_summaries").select { filter { eq("day", d) } }.decodeList()
                error = null
            }.onFailure { error = "Couldn't refresh (${it.message?.take(60)}). Retrying…" }
            delay(30_000)
        }
    }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(12.9716, 77.5946), 11f) }
    val located = live.filter { it.lat != null && it.lon != null }
    LaunchedEffect(located.size) {
        if (located.size >= 2) runCatching {
            val b = LatLngBounds.builder(); located.forEach { b.include(LatLng(it.lat!!, it.lon!!)) }
            camera.move(CameraUpdateFactory.newLatLngBounds(b.build(), 120))
        } else located.firstOrNull()?.let { camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat!!, it.lon!!), 14f)) }
    }
    // Map is pinned above the list (not inside it) so scrolling never re-creates or fights the map.
    Column(Modifier.fillMaxSize().background(MettleColors.Band)) {
    Column(Modifier.background(MettleColors.Surface)) {
        TopBar("Admin · group", onBack = onBack)
        if (app.mettle.ui.workout.MapsConfig.enabled) {
            GoogleMap(Modifier.fillMaxWidth().height(260.dp).semantics { contentDescription = "Map of group members" },
                cameraPositionState = camera,
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, rotationGesturesEnabled = false, tiltGesturesEnabled = false)) {
                located.forEach { m ->
                    key(m.userId) {
                        Marker(state = rememberMarkerState(position = LatLng(m.lat!!, m.lon!!)).also { it.position = LatLng(m.lat!!, m.lon!!) }, title = m.name,
                            snippet = "${m.state ?: "–"} · ${m.battery?.let { "$it%" } ?: ""} · ${ago(m.updatedAt)}")
                    }
                }
            }
        } else Box(Modifier.fillMaxWidth().height(260.dp).background(MettleColors.Band))
        error?.let { Text(it, style = MettleType.Label, color = MettleColors.WarnText, modifier = Modifier.padding(12.dp)) }
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(0.dp)) }
        if (alerts.isNotEmpty()) item {
            Section {
                Text("Alerts · last 2 days", style = MaterialTheme.typography.titleLarge)
                alerts.forEach { a ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${a.name ?: "Member"} · " + when (a.kind) { "fall" -> "possible fall"; "sos" -> "SOS"; "no_movement" -> "no movement"
                                "arrive" -> "arrived"; "leave" -> "left"; else -> a.kind } + (a.detail?.get("place")?.jsonPrimitive?.content?.let { " $it" } ?: ""),
                                style = MaterialTheme.typography.titleSmall, color = if (a.kind in setOf("fall", "sos", "no_movement")) MettleColors.WarnText else MettleColors.Ink)
                            Text(ago(a.createdAt) + if (a.status == "acknowledged") " · handled" else "", style = MettleType.Label, color = MettleColors.Sub)
                        }
                        if (a.status != "acknowledged" && a.kind in setOf("fall", "sos", "no_movement")) TextButton(onClick = {
                            scope.launch {
                                runCatching { Backend.client.from("alerts").update(AckRow()) { filter { eq("id", a.id) } } }
                                refresh++
                            }
                        }) { Text("Handled", color = MettleColors.Orange) }
                    }
                }
            }
        }
        items(members, key = { it.id }) { p ->
            val l = live.firstOrNull { it.userId == p.id }
            val d = today.firstOrNull { it.userId == p.id }
            Section(Modifier.clickable { onMember(p.id, p.name) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(when { l == null -> MettleColors.Muted; l.trackingOk -> MettleColors.Good; else -> MettleColors.Warn }))
                    Text(p.name + if (p.role == "admin") " · Admin" else "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(l?.let { ago(it.updatedAt) } ?: "never synced", style = MettleType.Label, color = MettleColors.Sub)
                }
                Text(listOfNotNull(l?.state?.replaceFirstChar { it.uppercase() }, l?.battery?.let { "battery $it%" + if (l.charging == true) " (charging)" else "" },
                    l?.floor?.let { "floor $it" }).joinToString(" · ").ifBlank { "No live status yet" }, style = MaterialTheme.typography.bodyMedium, color = MettleColors.Ink2)
                problemText(l?.problem)?.let { Text("⚠ $it", style = MaterialTheme.typography.bodyMedium, color = MettleColors.WarnText) }
                Row(Modifier.fillMaxWidth()) {
                    Stat("Steps", "%,d".format(d?.steps ?: 0), Modifier.weight(1f))
                    Stat("Active", "${d?.activeMin ?: 0}", Modifier.weight(1f), unit = "min")
                    Stat("Floors", "${d?.floorsUp ?: 0}", Modifier.weight(1f))
                    Stat("Km", Fmt.km(d?.distanceM?.toLong() ?: 0), Modifier.weight(1f))
                }
            }
        }
    }
    }
}

/** One member: today, recent workouts, tracking gaps, phone capabilities, day replay. */
@Composable
fun MemberScreen(userId: String, name: String, onReplay: () -> Unit, onBack: () -> Unit) {
    val data by produceState<Triple<List<MemberActivity>, List<MemberGap>, MemberLive?>?>(null, userId) {
        value = runCatching {
            val acts = Backend.client.from("activities").select {
                filter { eq("user_id", userId) }; order("started_at", Order.DESCENDING); limit(15)
            }.decodeList<MemberActivity>()
            val gaps = Backend.client.from("tracking_gaps").select {
                filter { eq("user_id", userId) }; order("start_at", Order.DESCENDING); limit(10)
            }.decodeList<MemberGap>()
            val live = Backend.client.from("member_live").select { filter { eq("user_id", userId) } }.decodeList<MemberLive>().firstOrNull()
            Triple(acts, gaps, live)
        }.getOrDefault(Triple(emptyList(), emptyList(), null))
    }
    val zone = ZoneId.systemDefault()
    LazyColumn(Modifier.fillMaxSize().background(MettleColors.Band), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.background(MettleColors.Surface)) {
                TopBar(name, onBack = onBack)
                TextButton(onClick = onReplay, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Replay $name's day ›", color = MettleColors.Orange) }
            }
        }
        val (acts, gaps, live) = data ?: Triple(emptyList(), emptyList(), null)
        item {
            Section {
                Text("Recent workouts", style = MaterialTheme.typography.titleLarge)
                if (acts.isEmpty()) Text("None yet", style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub)
                acts.forEach { a ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.titleSmall)
                            Text(Instant.parse(a.startedAt).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")), style = MettleType.Label, color = MettleColors.Sub)
                        }
                        Text(if (a.type == "stairs") "▲ ${a.floorsUp} fl" else "${Fmt.km2(a.distanceM.toLong())} km · ${Fmt.duration(a.movingS.toLong())}",
                            style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        item {
            Section {
                Text("Tracking health", style = MaterialTheme.typography.titleLarge)
                if (gaps.isEmpty()) Text("No gaps recorded", style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub)
                gaps.forEach { g ->
                    val start = Instant.parse(g.startAt); val end = g.endAt?.let { Instant.parse(it) }
                    Text("${start.atZone(zone).format(DateTimeFormatter.ofPattern("EEE h:mm a"))} · off ${end?.let { Fmt.hoursMinutes(Duration.between(start, it).seconds) + " h" } ?: "now"} · " +
                        (if (g.reason == "phone_off") "phone was off" else if (g.reason == "app_stopped") "app was stopped" else g.reason.replace('_', ' ')),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { ArriveLeaveRules(userId, name) }
        live?.capabilities?.let { caps ->
            item {
                Section {
                    Text("Phone: ${caps["model"]?.jsonPrimitive?.content ?: "unknown"}", style = MaterialTheme.typography.titleLarge)
                    listOf("barometer" to "Barometer (accurate floors & lifts)", "step_counter" to "Step counter chip", "gyroscope" to "Gyroscope",
                        "wifi_rtt" to "Wi-Fi distance (RTT)", "gnss_raw" to "Raw GPS measurements", "bluetooth_le" to "Bluetooth LE (heart-rate straps)",
                        "camera_flash" to "Camera flash (fingertip heart rate)").forEach { (k, label) ->
                        val has = caps[k]?.jsonPrimitive?.booleanOrNull == true
                        Text((if (has) "✓ " else "✗ ") + label, style = MaterialTheme.typography.bodyMedium, color = if (has) MettleColors.Ink else MettleColors.Muted)
                    }
                }
            }
        }
    }
}

/** Downloads a member's day track (admin only) for the replay screen. */
suspend fun loadMemberDay(userId: String, date: LocalDate): List<ReplayPoint> = withContext(Dispatchers.IO) {
    val bytes = runCatching { Backend.client.storage.from("tracks").downloadAuthenticated("$userId/$date.json.gz") }.getOrNull() ?: return@withContext emptyList()
    val f = GZIPInputStream(bytes.inputStream()).bufferedReader().use { json.decodeFromString(DayTrackFile.serializer(), it.readText()) }
    f.t.indices.map { i -> ReplayPoint(f.t[i] * 1000, f.lat[i], f.lon[i], f.alt.getOrNull(i), f.hAcc.getOrNull(i), f.state.getOrElse(i) { "unknown" }) }
}

suspend fun loadMemberPlaces(userId: String): List<KnownPlace> = runCatching {
    Backend.client.from("stays_live").select { filter { eq("user_id", userId); neq("label", "null") }; limit(50) }.decodeList<MemberStay>()
        .filter { it.label != null }.distinctBy { it.label }.map { KnownPlace(it.label!!, it.lat, it.lon) }
}.getOrDefault(emptyList())

/** Admin: "tell me when this member arrives at / leaves" one of their places. */
@Composable
private fun ArriveLeaveRules(userId: String, name: String) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    val data by produceState<Pair<List<app.mettle.safety.PlaceView>, List<app.mettle.safety.GeofenceRule>>?>(null, userId, version) {
        value = runCatching {
            val me = Backend.client.auth.currentUserOrNull()?.id
            val places = Backend.client.from("places_live").select { filter { eq("user_id", userId) } }.decodeList<app.mettle.safety.PlaceView>()
            val rules = Backend.client.from("geofence_rules").select { filter { eq("subject_id", userId); eq("owner_id", me ?: "") } }.decodeList<app.mettle.safety.GeofenceRule>()
            places.filter { it.lat != null } to rules
        }.getOrNull()
    }
    Section {
        Text("Arrive / leave alerts", style = MaterialTheme.typography.titleLarge)
        val (places, rules) = data ?: (emptyList<app.mettle.safety.PlaceView>() to emptyList())
        if (places.isEmpty()) Text("$name has no known places yet (Home and Work are learned after a few days of tracking).",
            style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub)
        places.forEach { p ->
            val rule = rules.firstOrNull { it.placeId == p.id }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                listOf("Arrive" to true, "Leave" to false).forEach { (label, arrive) ->
                    val on = rule != null && (if (arrive) rule.onArrive else rule.onLeave)
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                val me = Backend.client.auth.currentUserOrNull()!!.id
                                val newArrive = if (arrive) !on else rule?.onArrive ?: false
                                val newLeave = if (!arrive) !on else rule?.onLeave ?: false
                                val r = app.mettle.safety.GeofenceRule(rule?.id ?: java.util.UUID.randomUUID().toString(), me, userId, p.id, newArrive, newLeave, newArrive || newLeave)
                                Backend.client.from("geofence_rules").upsert(r) { onConflict = "id" }
                            }
                            version++
                        }
                    }) { Text((if (on) "✓ " else "") + label, color = if (on) MettleColors.Good else MettleColors.Orange) }
                }
            }
        }
        Text("Alerts reach you within about a minute while Mettle's tracking runs on your phone.", style = MettleType.Label, color = MettleColors.Sub)
    }
}
