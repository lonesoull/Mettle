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

    /** Guards against two installs at once (app start + background worker). */
    @Volatile var installing = false

    suspend fun installOnce(context: Context, update: Available, onProgress: (Float) -> Unit) {
        if (installing) return
        installing = true
        try { install(context, update, onProgress) } catch (e: Throwable) { installing = false; throw e }
    }

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

/** True while Mettle is on screen (set by MainActivity). */
object AppVisibility { @Volatile var foreground = false }

/**
 * Receives the install outcome. If Android needs a one-time confirmation it is
 * shown directly when Mettle is open, otherwise as a notification.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Updater.installing = false
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        @Suppress("DEPRECATION")
        val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (AppVisibility.foreground) {
            runCatching { context.startActivity(confirm) }
        } else {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (nm.getNotificationChannel("updates") == null) {
                nm.createNotificationChannel(android.app.NotificationChannel("updates", "App updates", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val pi = PendingIntent.getActivity(context, 42, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(42, androidx.core.app.NotificationCompat.Builder(context, "updates")
                .setSmallIcon(app.mettle.R.drawable.ic_stat_mettle)
                .setContentTitle("Mettle update ready")
                .setContentText("Tap to finish installing. Later updates install by themselves.")
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }
}

/** Checks for and installs updates in the background, even when the app is closed. */
class UpdateWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT >= 26 && !applicationContext.packageManager.canRequestPackageInstalls()) return Result.success()
        val update = Updater.check() ?: return Result.success()
        return runCatching { Updater.installOnce(applicationContext, update) {} ; Result.success() }.getOrElse { Result.retry() }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "auto-update", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                androidx.work.PeriodicWorkRequestBuilder<UpdateWorker>(3, java.util.concurrent.TimeUnit.HOURS).setConstraints(constraints).build(),
            )
        }
    }
}
