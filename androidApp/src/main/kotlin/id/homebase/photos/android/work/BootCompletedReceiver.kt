package id.homebase.photos.android.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Content-trigger jobs can't be persisted across reboot — re-arm on boot. Unconditional:
 * BackupWorker itself gates on the shared enabled flag, so a disabled backup stays silent.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) MediaWatchScheduler.schedule(context)
    }
}
