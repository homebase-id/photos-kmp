package id.homebase.api.sync.database

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Truth table for [outboxDrained] — the predicate awaitDrained() polls to decide a background
 *  worker can return. The rest of awaitDrained() is a delay/timeout loop with no branching logic. */
class OutboxDrainedTest {

    @Test
    fun drainedWhenNoWorkersAndNothingDue() =
        assertTrue(outboxDrained(activeThreads = 0, nextDueMs = null, nowMs = 1_000))

    @Test
    fun notDrainedWhileAWorkerIsActive() =
        assertFalse(outboxDrained(activeThreads = 1, nextDueMs = null, nowMs = 1_000))

    @Test
    fun notDrainedWhenARowIsDueNow() =
        // a row scheduled at-or-before now is still waiting to ship → not drained
        assertFalse(outboxDrained(activeThreads = 0, nextDueMs = 900, nowMs = 1_000))

    @Test
    fun drainedWhenOnlyAFutureRetryRemains() =
        // a backed-off retry due later rides the next trigger — this pass is done
        assertTrue(outboxDrained(activeThreads = 0, nextDueMs = 5_000, nowMs = 1_000))
}
