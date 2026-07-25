import SwiftUI
import UIKit

/// UIActivityViewController bridge — ShareLink can't carry a heterogeneous payload
/// (file URL for originals/video, UIImage for the thumbnail fallback).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
