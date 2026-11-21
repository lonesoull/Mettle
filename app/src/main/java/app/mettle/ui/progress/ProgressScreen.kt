package app.mettle.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mettle.data.db.ActivityType
import app.mettle.data.db.MettleDatabase
import app.mettle.data.remote.Backend
import app.mettle.data.remote.Profile
import app.mettle.sync.ProfileCache
import app.mettle.ui.Fmt
import app.mettle.ui.components.Section
import app.mettle.ui.components.Stat
import app.mettle.ui.components.TopBar
import app.mettle.ui.theme.MettleColors
import app.mettle.ui.theme.MettleType
import app.mettle.ui.workout.effortLabel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Composable
fun ProgressScreen(db: MettleDatabase) {
    val ctx = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val start = remember { today.minusDays(83) }
    val acts by db.activities().between(start.atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis() + 1).collectAsState(emptyList())
    val prs by db.bestEfforts().bestPerEffort().collectAsState(emptyList())
    val days by db.day().dailyTotals().collectAsState(emptyList())
    val goal = remember { ProfileCache.stepGoal(ctx) }

    val dayOf = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }
    val load = remember(acts) { (0..83).map { d -> start.plusDays(d.toLong()).let { day -> acts.filter { dayOf(it.startedAt) == day }.sumOf { (it.trainingLoad ?: (it.movingS / 60 * 4)).toDouble() } } } }
    val series = remember(load) {
        var a = 0.0; var c = 0.0
        load.map { l -> a += (l - a) / 7.0; c += (l - c) / 42.0; Triple(c, a, c - a) }
    }

    LazyColumn(Modifier.fillMaxSize().background(MettleColors.Band), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { TopBar("Progress") }
        item {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val week = acts.filter { dayOf(it.startedAt) >= monday }
            val weekDays by db.day().dailyTotals().collectAsState(emptyList())
            val weekSteps = weekDays.filter { LocalDate.parse(it.day) >= monday }.sumOf { it.steps }
            Section(title = "This week") {
                Row(Modifier.fillMaxWidth()) {
                    app.mettle.ui.components.StatBig("Distance", Fmt.km(week.filter { it.type != ActivityType.STAIRS }.sumOf { it.distanceM }.toLong()), Modifier.weight(1f), unit = "km")
                    app.mettle.ui.components.StatBig("Time", Fmt.hoursMinutes(week.sumOf { it.movingS }.toLong()), Modifier.weight(1f), unit = "h")
                    app.mettle.ui.components.StatBig("Steps", "%,d".format(weekSteps), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    val perDay = (0..6).map { d -> monday.plusDays(d.toLong()).let { day -> acts.filter { dayOf(it.startedAt) == day }.sumOf { it.movingS } } }
                    val mx = perDay.maxOrNull()?.coerceAtLeast(1) ?: 1
                    perDay.forEachIndexed { i, v ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height((3 + 40 * v / mx).dp).clip(RoundedCornerShape(4.dp))
                                .background(if (v > 0) MettleColors.Orange else MettleColors.Line))
                            Text(monday.plusDays(i.toLong()).dayOfWeek.name.take(1), style = MettleType.Label,
                                color = if (monday.plusDays(i.toLong()) == today) MettleColors.Ink else MettleColors.Muted, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        item {
            Section {
                Text("Fitness & freshness", style = MaterialTheme.typography.titleLarge)
                val last = series.lastOrNull() ?: Triple(0.0, 0.0, 0.0)
                Row(Modifier.fillMaxWidth()) {
                    Stat("Fitness", "${last.first.toInt()}", Modifier.weight(1f))
                    Stat("Fatigue", "${last.second.toInt()}", Modifier.weight(1f))
                    Stat("Form", (if (last.third >= 0) "+" else "") + last.third.toInt(), Modifier.weight(1f))
                }
                if (acts.isEmpty()) Text("Record workouts and rate how hard they felt. This chart builds from that.", style = MaterialTheme.typography.bodyMedium, color = MettleColors.Sub)
                else {
                    val hi = series.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(10.0)
                    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                        fun line(sel: (Triple<Double, Double, Double>) -> Double, color: androidx.compose.ui.graphics.Color, w: Float) {
                            val path = Path()
                            series.forEachIndexed { i, t -> val x = i * size.width / (series.size - 1); val y = (size.height * (1 - sel(t) / hi)).toFloat()
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                            drawPath(path, color, style = Stroke(w))
                        }
                        line({ it.first }, MettleColors.Ink, 5f)
                        line({ it.second }, MettleColors.Warn, 3f)
                    }
                    Text("Ink: fitness (6 weeks) · Amber: fatigue (1 week). Form = fitness − fatigue; positive means fresh.", style = MettleType.Label, color = MettleColors.Sub)
                }
            }
        }
        item {
            Section {
                Text("Last 12 weeks", style = MaterialTheme.typography.titleLarge)
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val weeks = (11 downTo 0).map { w -> val s = monday.minusWeeks(w.toLong()); acts.filter { dayOf(it.startedAt) >= s && dayOf(it.startedAt) < s.plusWeeks(1) } }
                val km = weeks.map { w -> w.filter { it.type != ActivityType.STAIRS }.sumOf { it.distanceM } / 1000.0 }
                val floors = weeks.map { w -> w.sumOf { it.floorsUp }.toDouble() }
                Text("Distance (km)", style = MettleType.Label, color = MettleColors.Sub)
                Bars(km, MettleColors.Orange)
                if (floors.any { it > 0 }) { Text("Floors climbed in workouts", style = MettleType.Label, color = MettleColors.Sub); Bars(floors, MettleColors.Ink) }
                Text("This week: ${Fmt.km((km.last() * 1000).toLong())} km" + if (floors.last() > 0) " · ${floors.last().toInt()} floors" else "", style = MaterialTheme.typography.titleSmall)
            }
        }
        item {
            Section {
                Text("Training calendar", style = MaterialTheme.typography.titleLarge)
                val maxL = load.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    (0 until 12).forEach { w ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            (0 until 7).forEach { d ->
                                val l = load.getOrNull(w * 7 + d) ?: 0.0
                                Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(
                                    if (l == 0.0) MettleColors.Band else MettleColors.Orange.copy(alpha = (0.25 + 0.75 * l / maxL).toFloat())))
                            }
                        }
                    }
                }
            }
        }
        if (prs.isNotEmpty()) item {
            Section {
                Text("Personal records", style = MaterialTheme.typography.titleLarge)
                prs.sortedBy { listOf("run_400m", "run_1k", "run_1mi", "run_5k", "run_10k", "run_half", "stairs_up_10fl", "stairs_up_50fl", "stairs_down_15fl").indexOf(it.effort) }.forEach { p ->
                    Row(Modifier.fillMaxWidth()) {
                        Text("★ " + effortLabel(p.effort), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = MettleColors.Ink)
                        Text(Fmt.duration(p.timeS.toLong()), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
        if (days.isNotEmpty()) item {
            Section {
                Text("Daily records & streak", style = MaterialTheme.typography.titleLarge)
                val most = days.maxBy { it.steps }
                val mostFloors = days.maxBy { it.floorsUp }
                var streak = 0; var best = 0; var cur = 0
                days.forEach { d -> if (d.steps >= goal) { cur++; best = maxOf(best, cur) } else cur = 0 }
                for (d in days.reversed()) { if (d.steps >= goal) streak++ else if (d.day != today.toString()) break }
                Row(Modifier.fillMaxWidth()) {
                    Stat("Most steps", "%,d".format(most.steps), Modifier.weight(1f))
                    Stat("Most floors", "${mostFloors.floorsUp}", Modifier.weight(1f))
                    Stat("Goal streak", "$streak d", Modifier.weight(1f))
                }
                Text("Best streak: $best days at ${"%,d".format(goal)}+ steps", style = MettleType.Label, color = MettleColors.Sub)
                val cad = days.filter { it.walkMin >= 10 }.map { it.walkSteps.toDouble() / it.walkMin }
                if (cad.size >= 3) {
                    Text("Walking cadence trend (steps/min)", style = MettleType.Label, color = MettleColors.Sub, modifier = Modifier.padding(top = 8.dp))
                    val lo = cad.min() - 2; val hi = cad.max() + 2
                    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                        for (i in 1 until cad.size) drawLine(MettleColors.Ink,
                            Offset((i - 1) * size.width / (cad.size - 1), (size.height * (1 - (cad[i - 1] - lo) / (hi - lo))).toFloat()),
                            Offset(i * size.width / (cad.size - 1), (size.height * (1 - (cad[i] - lo) / (hi - lo))).toFloat()), 4f)
                    }
                    Text("Now ${cad.last().toInt()} · a steady or rising line is a good sign; a sudden drop can mean fatigue or a niggle.", style = MettleType.Label, color = MettleColors.Sub)
                }
            }
        }
    }
}

@Composable
private fun Bars(values: List<Double>, color: androidx.compose.ui.graphics.Color) {
    val max = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Box(Modifier.weight(1f).height((4 + 62 * v / max).dp).clip(RoundedCornerShape(4.dp))
                .background(if (i == values.lastIndex) color else color.copy(alpha = 0.45f)))
        }
    }
}
