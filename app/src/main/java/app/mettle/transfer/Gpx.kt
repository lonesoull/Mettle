package app.mettle.transfer

import android.content.Context
import android.net.Uri
import android.util.Xml
import app.mettle.MettleApplication
import app.mettle.data.db.ActivityType
import app.mettle.workout.Sample
import app.mettle.workout.WorkoutSaver
import org.xmlpull.v1.XmlPullParser
import java.io.File
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ParsedGpx(val name: String?, val type: ActivityType, val samples: List<Sample>)

object Gpx {
    /** Reads a .gpx (or .gpx.gz) track: points with time, latitude, longitude and elevation. */
    fun parse(input: InputStream, gz: Boolean): ParsedGpx {
        val p = Xml.newPullParser()
        p.setInput(if (gz) GZIPInputStream(input) else input, null)
        val samples = ArrayList<Sample>()
        var name: String? = null; var typeText = ""
        var lat = 0.0; var lon = 0.0; var ele: Double? = null; var time: Long? = null
        var inPt = false; var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "trkpt") { inPt = true; lat = p.getAttributeValue(null, "lat").toDouble(); lon = p.getAttributeValue(null, "lon").toDouble(); ele = null; time = null }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text?.trim().orEmpty()
                    if (t.isEmpty()) continue
                    when {
                        inPt && tag == "ele" -> ele = t.toDoubleOrNull()
                        inPt && tag == "time" -> time = runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
                        !inPt && tag == "name" && name == null -> name = t
                        !inPt && tag == "type" -> typeText = t.lowercase()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "trkpt") { inPt = false; time?.let { samples += Sample(it, lat, lon, 5f, ele, null, null) } }
                    tag = ""
                }
            }
        }
        val type = when {
            "run" in typeText || "run" in (name ?: "").lowercase() || typeText == "9" -> ActivityType.RUN
            "hik" in typeText || "hik" in (name ?: "").lowercase() -> ActivityType.HIKE
            else -> ActivityType.WALK
        }
        return ParsedGpx(name, type, samples)
    }

    /** Imports GPX files (single files or a whole Strava export .zip). Returns (imported, skipped). */
    suspend fun import(context: Context, uris: List<Uri>, onProgress: (String) -> Unit): Pair<Int, Int> {
        var ok = 0; var skipped = 0
        suspend fun one(name: String, stream: InputStream) {
            val lower = name.lowercase()
            if (!(lower.endsWith(".gpx") || lower.endsWith(".gpx.gz"))) { skipped++; return }
            val g = runCatching { parse(stream, lower.endsWith(".gz")) }.getOrNull()
            if (g == null || g.samples.size < 10) { skipped++; return }
            WorkoutSaver.saveImported(context, g.samples, g.type, g.name ?: WorkoutSaver.defaultTitle(g.type, g.samples.first().tMs))
            ok++; onProgress("Imported $ok…")
        }
        for (u in uris) {
            val display = u.lastPathSegment ?: "file"
            context.contentResolver.openInputStream(u)?.use { s ->
                if (display.lowercase().endsWith(".zip")) {
                    java.util.zip.ZipInputStream(s).use { z ->
                        var e: ZipEntry? = z.nextEntry
                        while (e != null) { if (!e.isDirectory) one(e.name, NonClosing(z)); e = z.nextEntry }
                    }
                } else one(display, s)
            }
        }
        return ok to skipped
    }

    private class NonClosing(private val inner: InputStream) : InputStream() {
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
        override fun close() {}
    }

    /** Writes every saved workout with a route as GPX into one zip; returns the file. */
    suspend fun exportAll(context: Context): File {
        val db = (context.applicationContext as MettleApplication).database
        val out = File(context.cacheDir, "exports").apply { mkdirs() }.let { File(it, "mettle-workouts.zip") }
        ZipOutputStream(out.outputStream()).use { zip ->
            db.activities().betweenNow(0, Long.MAX_VALUE).forEach { a ->
                val s = WorkoutSaver.readStreams(context, a.id) ?: return@forEach
                if (s.lat.isEmpty()) return@forEach
                zip.putNextEntry(ZipEntry(fileName(a)))
                zip.write(gpxText(a, s).toByteArray()); zip.closeEntry()
            }
        }
        return out
    }

    private fun fileName(a: app.mettle.data.db.ActivityEntity) =
        "${Instant.ofEpochMilli(a.startedAt).toString().take(10)}-${a.title.replace(Regex("[^A-Za-z0-9]+"), "-")}-${a.id.take(6)}.gpx"

    /** GPX 1.1 with elevation, time and (when recorded) heart rate — opens in Strava, Garmin, Google Earth. */
    fun gpxText(a: app.mettle.data.db.ActivityEntity, s: app.mettle.workout.Streams): String {
        val esc = { x: String -> x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Mettle\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
            "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        sb.append("<trk><name>").append(esc(a.title)).append("</name><type>").append(a.type.name.lowercase()).append("</type><trkseg>\n")
        for (i in s.lat.indices) {
            sb.append("<trkpt lat=\"").append(s.lat[i]).append("\" lon=\"").append(s.lon[i]).append("\">")
            s.alt.getOrNull(i)?.let { sb.append("<ele>").append(String.format(java.util.Locale.US, "%.1f", it)).append("</ele>") }
            sb.append("<time>").append(Instant.ofEpochMilli(a.startedAt + s.t[i] * 1000L)).append("</time>")
            s.hr?.getOrNull(i)?.let { sb.append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>").append(it).append("</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>") }
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    /** One workout as a .gpx file ready to share. Null if it has no GPS route. */
    suspend fun exportOne(context: Context, activityId: String): File? {
        val db = (context.applicationContext as MettleApplication).database
        val a = db.activities().get(activityId) ?: return null
        val s = WorkoutSaver.readStreams(context, a.id)?.takeIf { it.lat.isNotEmpty() } ?: return null
        return File(context.cacheDir, "exports").apply { mkdirs() }.let { File(it, fileName(a)) }.also { it.writeText(gpxText(a, s)) }
    }

    /** Daily totals as CSV (date, steps, floors up). */
    suspend fun exportDaysCsv(context: Context): File {
        val db = (context.applicationContext as MettleApplication).database
        val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, "mettle-daily.csv")
        val rows = db.day().dailyTotals().first()
        out.writeText("date,steps,floors_up,walking_minutes\n" + rows.joinToString("\n") { "${it.day},${it.steps},${it.floorsUp},${it.walkMin}" })
        return out
    }
}
