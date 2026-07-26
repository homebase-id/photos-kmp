import SwiftUI
import Shared

/// Owns the shared `SettingsViewModel` across SwiftUI struct re-inits (same `@StateObject` + SKIE
/// `for await` pattern as `BackupModel`). Mirrors state and forwards `refresh()`; logout stays in
/// the view on a detached `Task` — never on a scope the authState flip tears down.
@MainActor
final class SettingsModel: ObservableObject {
    let vm = PhotosModuleKt.settingsViewModel()

    /// nil until the first shared emission — avoids constructing a SettingsUiState in Swift.
    @Published private(set) var state: SettingsUiState?

    private var observeTask: Task<Void, Never>?

    /// Idempotent: wires the state subscription exactly once.
    func start() {
        guard observeTask == nil else { return }
        // Capture the AsyncSequence (not self) so the task never retains the model — the weak
        // self re-check each iteration lets `deinit` fire and cancel it (no leaked flow).
        let states = vm.state
        observeTask = Task { [weak self] in
            for await s in states {
                guard let self else { return }
                self.state = s
            }
        }
    }

    func refresh() { vm.refresh() }

    deinit {
        observeTask?.cancel()
    }
}

/// Settings: a grouped `List` presented as a sheet from the timeline's account button (the
/// Collections-only `router.path` can't host it). Account header, Backup entry (the screen's new
/// home), About, and Sign out — which keeps the logout confirmation copy the timeline used to own.
struct SettingsView: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = SettingsModel()
    @State private var showLogoutDialog = false

    var body: some View {
        NavigationStack {
            List {
                accountSection
                Section {
                    NavigationLink {
                        BackupView()
                    } label: {
                        Label("Backup", systemImage: "arrow.clockwise.icloud")
                            .foregroundColor(PhotosColor.onSurface(scheme))
                    }
                    .accessibilityIdentifier("settings-backup")
                }
                Section {
                    HStack {
                        Text("About")
                            .foregroundColor(PhotosColor.onSurface(scheme))
                        Spacer()
                        Text(appVersion)
                            .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("settings-about")
                }
                Section {
                    Button("Sign out", role: .destructive) { showLogoutDialog = true }
                        .accessibilityIdentifier("settings-signout")
                }
            }
            .accessibilityIdentifier("settings-root")
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("settings-done")
                }
            }
        }
        .tint(PhotosColor.primary(scheme))
        // Same copy + id the timeline dialog carried before Batch G. Confirm → the shared suspend
        // logout() on a detached Task (survives this sheet's teardown when authState flips); the
        // RootModel gate swaps back to the login screen.
        .confirmationDialog(
            "Log out?",
            isPresented: $showLogoutDialog,
            titleVisibility: .visible
        ) {
            Button("Log out", role: .destructive) {
                Task { try? await PhotosModuleKt.youAuthFlowManager().logout() }
            }
            .accessibilityIdentifier("logout-confirm")
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You'll need to sign in again to see your photos.")
        }
        .task {
            model.start()
            model.refresh()
        }
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    // MARK: - Account header

    private var accountSection: some View {
        Section {
            HStack(spacing: PhotosMetrics.space16) {
                ZStack {
                    Circle()
                        .fill(PhotosColor.primaryContainer(scheme))
                    Text(model.state?.initials ?? "")
                        .font(PhotosFont.titleLarge)
                        .foregroundColor(PhotosColor.onPrimaryContainer(scheme))
                }
                .frame(width: 56, height: 56)

                VStack(alignment: .leading, spacing: PhotosMetrics.space2) {
                    Text(model.state?.displayName ?? model.state?.identity ?? "")
                        .font(PhotosFont.body)
                        .fontWeight(.semibold)
                        .foregroundColor(PhotosColor.onSurface(scheme))
                    if let identity = model.state?.identity {
                        Text(identity)
                            .font(PhotosFont.bodyMedium)
                            .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                    }
                }
            }
            .padding(.vertical, PhotosMetrics.space4)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("settings-account")
        }
    }
}
