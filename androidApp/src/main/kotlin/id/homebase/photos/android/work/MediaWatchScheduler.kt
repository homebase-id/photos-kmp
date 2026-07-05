package id.homebase.photos.android.work

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore

/**
 * Event-driven backup trigger: a JobScheduler job that fires shortly after new rows land in
 * MediaStore (images or video), then re-arms itself — content-trigger jobs are one-shot by
 * design. Execution stays on the existing WorkManager path; this only *triggers* it.
 */
object MediaWatchScheduler {

    private const val JOB_ID = 4243

    fun schedule(context: Context) {
        context.getSystemService(JobScheduler::class.java).schedule(jobInfo(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    internal fun jobInfo(context: Context): JobInfo =
        JobInfo.Builder(JOB_ID, ComponentName(context, MediaWatchJobService::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .setTriggerContentUpdateDelay(5_000)   // let bursts (screenshots, bursts) settle
            .setTriggerContentMaxDelay(30_000)     // upload starts within ~30s of a new photo
            .build()
}
