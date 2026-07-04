package id.homebase.api.sync

import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.TestUploader
import id.homebase.api.sync.database.createInMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tripwire for BLOCKER 1: the outbox send-gate must come online from a production
 * code path, or every enqueued backup upload early-returns "offline" forever.
 *
 * chat-kmp flips `OutboxSync.setOnline` from AuthConnectionCoordinator's WebSocket
 * connection lifecycle, which we deliberately did NOT copy (REST-only sync). This
 * pins the replacement semantic: [DriveSyncManager.start] (post-auth, idempotent,
 * on every [id.homebase.photos.data.PhotosRepositoryImpl.sync] call) brings the
 * outbox online and drains it; [DriveSyncManager.stop] (the logout path) takes it
 * offline. If a refactor drops the wiring, this test goes red.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveSyncManagerOutboxOnlineTest {

    private fun outboxTest(body: suspend TestScope.(DatabaseManager) -> Unit) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager({ createInMemoryDatabase() }, dispatcher = d, readDispatcher = d)
        try {
            body(db)
        } finally {
            db.close()
        }
    }

    private suspend fun credentials(): CredentialsManager {
        val cm = CredentialsManager()
        cm.setActiveCredentials(
            ApiCredentials.create(
                domain = OdinId("test.homebase.id"),
                clientAccessToken = "fake-token",
                sharedSecret = SecureByteArray(ByteArray(16)),
            )
        )
        return cm
    }

    private fun manager(
        db: DatabaseManager,
        creds: CredentialsManager,
        eventBus: EventBus,
        outbox: OutboxSync,
        scope: kotlinx.coroutines.CoroutineScope,
    ): DriveSyncManager {
        val httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        return DriveSyncManager(
            driveQueryProvider = DriveQueryProvider(httpClient, creds),
            credentialsManager = creds,
            eventBus = eventBus,
            scope = scope,
            databaseManager = db,
            mandatoryDrives = emptyMap(), // no drive needs to mount to exercise the gate
            outboxSync = outbox,
        )
    }

    @Test
    fun startBringsOutboxOnlineAndDrainsQueue_stopTakesItOffline() = outboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        val outbox = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope,
        )

        // A backup row queued while offline: it must sit durably, un-sent.
        db.outbox.insert(
            driveId = Uuid.random(),
            uniqueId = Uuid.random(),
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )
        assertFalse(outbox.isCurrentlyOnline(), "outbox starts offline (no production caller has run)")
        assertFalse(outbox.send(), "send() must no-op while offline")
        advanceUntilIdle()
        assertEquals(1L, db.outbox.count(), "the row stays queued while offline")
        assertEquals(0, uploader.uploaded.size)

        // The drain is finished only when the last worker emits the terminal Completed
        // event; a bare advanceUntilIdle() can return before the worker drains (see the
        // OutboxSyncTest class kdoc — the established pattern in this codebase is to await
        // Completed). Set the collector up BEFORE start() kicks send() so it can't be missed.
        val completedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>().first().totalCount
        }
        testScheduler.runCurrent()

        // The production path: start() flips online and kicks the drain.
        val mgr = manager(db, credentials(), eventBus, outbox, backgroundScope)
        mgr.start()
        advanceUntilIdle()
        val completedCount = completedDeferred.await()

        assertTrue(outbox.isCurrentlyOnline(), "start() must bring the outbox online")
        assertEquals(1, completedCount, "the drain reported exactly one sent item")
        assertEquals(0L, db.outbox.count(), "the queued row drains once online")
        assertEquals(1, uploader.uploaded.size, "the row actually sent")

        // Logout path (YouAuthFlowManager.logout() → stop()) takes it back offline.
        mgr.stop()
        assertFalse(outbox.isCurrentlyOnline(), "stop() must take the outbox offline")

        outbox.clearCheckout(timeoutMs = 5_000) // drain before db.close(); see OutboxSyncTest kdoc
    }
}
