package id.homebase.photos.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import id.homebase.photos.backup.BackgroundBackup
import org.koin.core.context.GlobalContext

/**
 * Runs one idempotent background backup pass by delegating to the shared [BackgroundBackup] (the same
 * sequence a future iOS BGTask handler will call): gate on enabled → enqueue → drain the outbox,
 * suspending until the uploads actually land. Used both as the expedited one-shot (toggle-on) and the
 * 6h periodic worker — [BackupScheduler] owns scheduling. This class is the Android trigger only.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runner = GlobalContext.get().get<BackgroundBackup>()
        return try {
            // run() returns false if the outbox didn't fully drain in budget (offline/slow) — retry.
            if (runner.run()) Result.success()
            else if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } catch (t: Throwable) {
            // Transient failures (network/outbox) get a few retries before giving up this pass.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /**
     * Only invoked for the expedited one-shot on API < 31, where WorkManager runs it as a
     * foreground service. A quiet, ongoing notification keeps the backup pass alive.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Photo backup", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Backing up photos")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val CHANNEL_ID = "photos-backup"
        const val NOTIFICATION_ID = 4242
    }
}
