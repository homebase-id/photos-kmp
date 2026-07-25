import BackgroundTasks
import Foundation
import Shared

/// The iOS background-backup trigger — the analog of Android's WorkManager `BackupWorker` +
/// `BackupScheduler`. All the real work (session restore, enabled-gate, enqueue, outbox drain)
/// lives in the shared `BackgroundBackup.run()`; this is only the platform trigger. Two paths, both
/// calling the same shared `run()`:
///   • `BGProcessingTask` — opportunistic auto/periodic (the only way iOS wakes a backgrounded app).
///   • `BGContinuedProcessingTask` (iOS 26) — user taps "Back up now", then the pass keeps running
///     with a system progress bar even if they leave the app. Foreground-initiated only.
/// No AppDelegate — `register()`/`schedule()` are driven from `iOSApp.init()`.
enum BackgroundBackupTrigger {
    static let periodicId = "id.homebase.photos.backup"
    static let continuedId = "id.homebase.photos.backup.now"

    /// Register both handlers. MUST run before the app finishes launching → call once from `iOSApp.init()`.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: periodicId, using: nil) { task in
            handlePeriodic(task as! BGProcessingTask)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: continuedId, using: nil) { task in
            handleContinued(task as! BGContinuedProcessingTask)
        }
    }

    // MARK: - Opportunistic auto/periodic (BGProcessingTask)

    /// Queue the next auto pass. ponytail: best-effort only — iOS treats `earliestBeginDate` as a
    /// floor and picks the real timing, so there's no exact 6h cadence like Android's PeriodicWork;
    /// we just re-arm on launch and after every run.
    static func schedule() {
        let request = BGProcessingTaskRequest(identifier: periodicId)
        request.requiresNetworkConnectivity = true          // mirrors Android NetworkType.CONNECTED
        request.requiresExternalPower = false               // no charging gate (D5)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 60 * 60)  // ~6h floor
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handlePeriodic(_ task: BGProcessingTask) {
        let work = Task {
            // ponytail: 25s upload ceiling — a BGProcessingTask window is short; whatever doesn't
            // land drains next pass / on next foreground open.
            let ok = await runBackup(uploadTimeoutMs: 25_000)
            await MainActor.run { task.setTaskCompleted(success: ok) }
            schedule()                                       // re-arm the next pass
        }
        task.expirationHandler = { work.cancel() }
    }

    // MARK: - User-initiated "Back up now" (BGContinuedProcessingTask, iOS 26)

    /// Submit from the foreground. The system starts it (near-)immediately and shows a progress bar;
    /// the pass survives the user backgrounding the app. `.queue` = start ASAP if not immediately.
    static func backupNow() {
        let request = BGContinuedProcessingTaskRequest(
            identifier: continuedId,
            title: "Backing up photos",
            subtitle: "Uploading to your Homebase library"
        )
        request.strategy = .queue
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handleContinued(_ task: BGContinuedProcessingTask) {
        let work = Task {
            // Generous budget: the whole point is that a user-started upload completes. The system
            // still reclaims it under pressure via the expiration handler.
            let ok = await runBackup(uploadTimeoutMs: 9 * 60_000, progress: task.progress)
            await MainActor.run { task.setTaskCompleted(success: ok) }
        }
        task.expirationHandler = { work.cancel() }
    }

    // MARK: - Shared runner

    /// Runs the shared pass, optionally mirroring shared `BackupState` (done/total) into the task's
    /// `Progress` so the system UI shows real progress. done/total come from the singleton
    /// `BackupManager`, so a throwaway `backupViewModel()` is fine to observe.
    private static func runBackup(uploadTimeoutMs: Int64, progress: Progress? = nil) async -> Bool {
        var observer: Task<Void, Never>?
        if let progress {
            let states = PhotosModuleKt.backupViewModel().state
            observer = Task {
                for await s in states {
                    progress.totalUnitCount = Int64(s.total)
                    progress.completedUnitCount = Int64(s.done)
                }
            }
        }
        // SKIE surfaces the Kotlin Boolean as Swift `Bool` or `KotlinBoolean` (NSNumber) — `as? Bool`
        // unwraps both.
        let result = try? await PhotosModuleKt.backgroundBackup().run(uploadTimeoutMs: uploadTimeoutMs)
        observer?.cancel()
        return (result as? Bool) ?? false
    }
}
