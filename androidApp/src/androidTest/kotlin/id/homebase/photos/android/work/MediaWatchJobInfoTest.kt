package id.homebase.photos.android.work

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the media-watch [android.app.job.JobInfo] wiring: both MediaStore trigger URIs, the
 * debounce window, and the target service — the contract the event-driven backup relies on.
 */
@RunWith(AndroidJUnit4::class)
class MediaWatchJobInfoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun watchesImagesAndVideoContentUris() {
        val uris = MediaWatchScheduler.jobInfo(context).triggerContentUris!!.map { it.uri }

        assertEquals(2, uris.size)
        assertTrue(uris.contains(MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        assertTrue(uris.contains(MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
    }

    @Test
    fun debounceWindowIsFiveToThirtySeconds() {
        val info = MediaWatchScheduler.jobInfo(context)

        assertEquals(5_000L, info.triggerContentUpdateDelay)
        assertEquals(30_000L, info.triggerContentMaxDelay)
    }

    @Test
    fun targetsMediaWatchJobService() {
        val info = MediaWatchScheduler.jobInfo(context)

        assertEquals(MediaWatchJobService::class.java.name, info.service.className)
        assertEquals(context.packageName, info.service.packageName)
    }
}
