package id.homebase.photos.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager scheduling for photo backup. Enabling the toggle kicks an expedited one-shot (back up
 * now) plus a 6h periodic catch-up; disabling cancels both by their unique names. D5 constraint:
 * any network, no charging gate.
 */
object BackupScheduler {

    private const val ONE_SHOT_WORK = "photos-backup-now"
    private const val PERIODIC_WORK = "photos-backup-periodic"
    private const val PERIOD_HOURS = 6L

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enable(context: Context) {
        val workManager = WorkManager.getInstance(context)

        val oneShot = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.KEEP, oneShot)

        val periodic = PeriodicWorkRequestBuilder<BackupWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }

    fun disable(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(ONE_SHOT_WORK)
        workManager.cancelUniqueWork(PERIODIC_WORK)
    }
}
