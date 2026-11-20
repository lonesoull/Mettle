package app.mettle.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.mettle.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater. Mettle is not on the Play Store, so it checks the GitHub
 * "latest release" and installs newer signed APKs itself.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/lonesoull/Mettle/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Asset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long)
    @Serializable private data class Release(@SerialName("tag_name") val tag: String, val assets: List<Asset> = emptyList())

    data class Available(val versionCode: Int, val versionName: String, val apkUrl: String, val sizeBytes: Long)

    /** Returns a newer release, or null if up to date / offline / not checkable. */
    suspend fun check(): Available? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val release = json.decodeFromString<Release>(body)
            val code = release.tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@runCatching null
            if (code > BuildConfig.VERSION_CODE) Available(code, release.tag.removePrefix("v"), apk.url, apk.size) else null
        }.getOrNull()
    }

    /**
     * Downloads the APK straight into an install session and commits it.
     * [onProgress] gets 0..1. Android shows its own confirmation when it requires one.
     */
    suspend fun install(context: Context, update: Available, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            conn.inputStream.use { input ->
                session.openWrite("mettle.apk", 0, total).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Receives the install outcome; opens Android's confirmation screen when it asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            else -> Unit   // success restarts the app on the new version; failures are retried next launch
        }
    }
}
