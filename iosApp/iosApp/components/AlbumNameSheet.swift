import SwiftUI

/// Name-entry sheet for album creation (C3). One field, Cancel/Create, and a spinner while the
/// write is in flight. Shared by the Collections `+`, the Create tab, and the add-to-album
/// picker's "New album" — the copy and the `identifier` are the only things that vary.
///
/// `onSubmit` returns whether the write landed: true dismisses, false keeps the sheet up with
/// the typed name intact (the host shows the reason as a toast).
struct AlbumNameSheet: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    var title: String = "New album"
    var confirmLabel: String = "Create"
    var identifier: String = "create-album-dialog"
    let onSubmit: @MainActor (String) async -> Bool

    @State private var name = ""
    @State private var isWorking = false
    @FocusState private var focused: Bool

    private var trimmed: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Album name", text: $name)
                    .font(PhotosFont.body)
                    .focused($focused)
                    .submitLabel(.done)
                    .autocorrectionDisabled()
                    .onSubmit(submit)
                    .accessibilityIdentifier("album-name-field")
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("album-name-cancel")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if isWorking {
                        ProgressView()
                    } else {
                        Button(confirmLabel, action: submit)
                            .fontWeight(.semibold)
                            .disabled(trimmed.isEmpty)
                            .accessibilityIdentifier("album-name-confirm")
                    }
                }
            }
            .disabled(isWorking)
        }
        .tint(PhotosColor.primary(scheme))
        .presentationDetents([.height(200)])
        .accessibilityIdentifier(identifier)
        .onAppear { focused = true }
    }

    private func submit() {
        let value = trimmed
        guard !value.isEmpty, !isWorking else { return }
        Task { @MainActor in
            isWorking = true
            let landed = await onSubmit(value)
            isWorking = false
            if landed { dismiss() }
        }
    }
}
