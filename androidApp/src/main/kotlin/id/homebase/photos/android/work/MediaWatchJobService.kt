package id.homebase.photos.android.work

import android.app.job.JobParameters
import android.app.job.JobService

/** Fires when MediaStore changes: kicks one backup pass via WorkManager and re-arms the watch. */
class MediaWatchJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        BackupScheduler.backupNow(this)
        MediaWatchScheduler.schedule(this) // one-shot by design — re-arm for the next change
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
