import SwiftUI

/// Pinch-zoom + pan + double-tap-toggle for any content (viewer still pages today).
/// Range 1x–5x. Reports zoom through `isZoomed` so the host can gate paging/dismiss
/// gestures; the host setting the binding back to false resets the transform.
struct ZoomableModifier: ViewModifier {
    @Binding var isZoomed: Bool

    private static let minScale: CGFloat = 1
    private static let maxScale: CGFloat = 5
    private static let doubleTapScale: CGFloat = 2.5

    @State private var steadyScale: CGFloat = 1
    @State private var steadyOffset: CGSize = .zero
    @GestureState private var pinchScale: CGFloat = 1
    @GestureState private var panOffset: CGSize = .zero

    func body(content: Content) -> some View {
        // Mild rubber-band past the bounds while the pinch is live; onEnded clamps hard.
        let scale = min(max(steadyScale * pinchScale, 0.8), Self.maxScale * 1.2)
        content
            .scaleEffect(scale)
            .offset(
                x: steadyOffset.width + panOffset.width,
                y: steadyOffset.height + panOffset.height
            )
            .highPriorityGesture(doubleTap)
            .simultaneousGesture(pinch)
            // Masked off entirely at 1x so the TabView keeps its page swipe.
            .highPriorityGesture(pan, including: isZoomed ? .all : .subviews)
            .onChange(of: isZoomed) { _, zoomed in
                if !zoomed && steadyScale != 1 { reset() }
            }
            .animation(.spring(response: 0.3, dampingFraction: 0.85), value: steadyScale)
    }

    private var pinch: some Gesture {
        MagnifyGesture()
            .updating($pinchScale) { value, state, _ in
                state = value.magnification
            }
            .onEnded { value in
                steadyScale = min(max(steadyScale * value.magnification, Self.minScale), Self.maxScale)
                if steadyScale <= 1.01 {
                    reset()
                } else {
                    isZoomed = true
                }
            }
    }

    private var pan: some Gesture {
        DragGesture()
            .updating($panOffset) { value, state, _ in
                state = value.translation
            }
            .onEnded { value in
                steadyOffset.width += value.translation.width
                steadyOffset.height += value.translation.height
            }
    }

    private var doubleTap: some Gesture {
        TapGesture(count: 2).onEnded {
            if steadyScale > 1 {
                reset()
            } else {
                steadyScale = Self.doubleTapScale
                isZoomed = true
            }
        }
    }

    private func reset() {
        steadyScale = 1
        steadyOffset = .zero
        isZoomed = false
    }
}

extension View {
    func zoomable(isZoomed: Binding<Bool>) -> some View {
        modifier(ZoomableModifier(isZoomed: isZoomed))
    }
}
