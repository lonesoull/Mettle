package app.mettle.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes: if all-day tracking is switched on but the tracker isn't running
 * (stopped by an update, Motorola's battery manager, or a crash), start it again.
 * Android allows this restart when Mettle's battery setting is "Unrestricted".
 */
class TrackingWatchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (TrackingService.isEnabled(applicationContext) && !TrackingState.running) {
            Diag.log(applicationContext, "Watchdog: tracker wasn't running, restarting")
            runCatching { TrackingService.start(applicationContext) }.onFailure { Diag.log(applicationContext, "Watchdog restart failed: ${it.javaClass.simpleName}") }
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "tracking-watchdog", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TrackingWatchdog>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
