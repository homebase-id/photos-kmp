import XCTest

/// Strict-TDD gate for the iOS fullscreen viewer (plan 004).
///
/// The viewer flow only makes sense with real cells in the grid. The `-uiTestTimeline` seam runs
/// the real repo with no logged-in identity, so the test env may show an empty grid — when no
/// `photo-cell` appears within 10s we XCTSkip rather than fail (mirrors TimelineGridUITest's
/// backend-agnostic stance). With at least one cell, we drive the full open → toggle → dismiss loop.
final class ViewerUITest: XCTestCase {
    func testTapCellOpensViewerTogglesChromeAndDismisses() throws {
        let app = XCUIApplication()
        // Bypass the session gate: a fresh install is logged out, so the auth-gated root would
        // otherwise show the login screen instead of the timeline.
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        // The cell carries the `.isButton` trait, so match by identifier across any element type.
        let firstCell = app.descendants(matching: .any).matching(identifier: "photo-cell").firstMatch
        guard firstCell.waitForExistence(timeout: 10) else {
            throw XCTSkip("No photos in the test env — the viewer flow needs at least one cell.")
        }

        // Tap the cell → the fullscreen viewer presents.
        firstCell.tap()
        let root = app.otherElements["viewer-root"]
        XCTAssertTrue(root.waitForExistence(timeout: 5), "Viewer did not open on cell tap")

        // Chrome starts visible then auto-hides after 3s...
        let close = app.buttons["viewer-close"]
        XCTAssertTrue(close.waitForExistence(timeout: 5), "Close button not visible on open")
        XCTAssertTrue(waitForDisappearance(of: close, timeout: 6), "Chrome did not auto-hide")

        // ...a single center tap toggles it back on.
        root.tap()
        XCTAssertTrue(close.waitForExistence(timeout: 3), "Chrome did not toggle back on tap")

        // A downward swipe dismisses the viewer.
        root.swipeDown()
        XCTAssertTrue(waitForDisappearance(of: root, timeout: 5), "Viewer did not dismiss on swipe-down")
    }

    private func waitForDisappearance(of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let gone = NSPredicate(format: "exists == false")
        let e = expectation(for: gone, evaluatedWith: element)
        return XCTWaiter().wait(for: [e], timeout: timeout) == .completed
    }
}
