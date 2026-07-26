import SwiftUI
import Shared

/// Backup settings: a plain grouped `List` (iOS settings idiom) over the shared `BackupViewModel`.
/// A master toggle (gated on Photos permission), a "Back up now" action, live progress, and the
/// per-folder selection picker. State comes from `BackupModel` (one shared VM); this view renders.
struct BackupView: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = BackupModel()

    var body: some View {
        NavigationStack {
            List {
                backupSection
                if enabled { folderSection }
            }
            .accessibilityIdentifier("backup-screen")
            .navigationTitle("Backup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("backup-done")
                }
            }
        }
        .tint(PhotosColor.primary(scheme))
        .task {
            model.start()
            model.loadFolders()
        }
    }

    private var enabled: Bool { model.state?.enabled ?? false }

    // MARK: - Backup toggle + status + action

    private var backupSection: some View {
        Section {
            Toggle("Back up photos & videos", isOn: Binding(
                get: { enabled },
                set: { model.onToggle($0) }
            ))
            .accessibilityIdentifier("backup-toggle")

            Button("Back up now") { model.onBackupNow() }
                .disabled(!enabled)
                .accessibilityIdentifier("backup-now")
        } footer: {
            statusFooter
        }
    }

    @ViewBuilder
    private var statusFooter: some View {
        if model.permissionDenied {
            Text("Photos access is off. Enable it in Settings to back up your library.")
                .foregroundColor(PhotosColor.error(scheme))
        } else if let error = model.state?.lastError {
            Text(error)
                .foregroundColor(PhotosColor.error(scheme))
        } else if let s = model.state, s.running {
            Text(progressText(done: s.done, total: s.total, name: s.currentName))
        } else if enabled {
            Text("New photos and videos back up in the background.")
        } else {
            Text("Turn on to back up new photos and videos to your Homebase library.")
        }
    }

    private func progressText(done: Int32, total: Int32, name: String?) -> String {
        let progress = total > 0 ? "Backing up \(done) of \(total)" : "Backing up"
        if let name, !name.isEmpty { return "\(progress) — \(name)" }
        return progress
    }

    // MARK: - Folder picker

    private var folderSection: some View {
        // The header carries the cross-platform `backup-folders` id — a modifier on the Section
        // itself would smear onto every row.
        Section(header: Text("Folders").accessibilityIdentifier("backup-folders")) {
            let folders = model.state?.folders ?? []
            if folders.isEmpty {
                Text("No device folders found.")
                    .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
            } else {
                ForEach(folders, id: \.folderId) { folder in
                    Toggle(isOn: Binding(
                        get: { folder.selected },
                        set: { _ in model.onFolderToggled(folder.folderId) }
                    )) {
                        VStack(alignment: .leading, spacing: PhotosMetrics.space2) {
                            Text(folder.name)
                                .foregroundColor(PhotosColor.onSurface(scheme))
                            Text("\(folder.photoCount) items")
                                .font(PhotosFont.caption)
                                .foregroundColor(PhotosColor.onSurfaceVariant(scheme))
                        }
                    }
                    .accessibilityIdentifier("backup-folder-\(folder.folderId)")
                }
            }
        }
    }
}
