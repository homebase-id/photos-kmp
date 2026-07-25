import XCTest

/// Strict-TDD gate for the iOS fullscreen viewer (plan 004 + Batch B).
///
/// The viewer flow only makes sense with real cells in the grid. The `-uiTestTimeline` seam runs
/// the real repo with no logged-in identity, so the test env may show an empty grid — when no
/// `photo-cell` appears within 10s we XCTSkip rather than fail (mirrors TimelineGridUITest's
/// backend-agnostic stance).
final class ViewerUITest: XCTestCase {
    func testTapCellOpensViewerTogglesChromeAndDismisses() throws {
        let app = try launchToViewer()
        let root = app.otherElements["viewer-root"]

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

    /// Batch B: the bottom action bar (Share · Delete · Info), the info sheet, and the delete
    /// confirmation flow. Delete is exercised up to the confirmation alert then CANCELLED —
    /// the `-uiTestTimeline` seam runs against the real library, so confirming would destroy
    /// a real photo.
    func testActionBarInfoSheetAndDeleteConfirmFlow() throws {
        let app = try launchToViewer()
        let root = app.otherElements["viewer-root"]

        // Action bar rides the chrome, visible on open.
        let share = app.buttons["viewer-share"]
        let delete = app.buttons["viewer-delete"]
        let info = app.buttons["viewer-info"]
        XCTAssertTrue(info.waitForExistence(timeout: 5), "Action bar not visible on open")
        XCTAssertTrue(share.exists, "Share button missing from action bar")
        XCTAssertTrue(delete.exists, "Delete button missing from action bar")

        // Info opens the metadata sheet; swipe-down closes it.
        info.tap()
        let sheet = app.descendants(matching: .any).matching(identifier: "viewer-info-sheet").firstMatch
        XCTAssertTrue(sheet.waitForExistence(timeout: 5), "Info sheet did not open")
        sheet.swipeDown(velocity: .fast)
        XCTAssertTrue(waitForDisappearance(of: sheet, timeout: 5), "Info sheet did not close on swipe-down")

        // Chrome may have auto-hidden while the sheet was up — bring it back.
        if !delete.waitForExistence(timeout: 1) {
            root.tap()
            XCTAssertTrue(delete.waitForExistence(timeout: 3), "Chrome did not return for delete")
        }

        // Delete → confirmation alert with the shared library copy → cancel keeps the photo.
        delete.tap()
        let confirm = app.buttons["delete-confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "Delete confirmation did not appear")
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(waitForDisappearance(of: confirm, timeout: 5), "Delete alert did not dismiss on cancel")
        XCTAssertTrue(root.exists, "Viewer closed after cancelling delete")

        // Close via the chrome button.
        if !app.buttons["viewer-close"].waitForExistence(timeout: 1) {
            root.tap()
        }
        app.buttons["viewer-close"].tap()
        XCTAssertTrue(waitForDisappearance(of: root, timeout: 5), "Viewer did not close via close button")
    }

    // MARK: - Helpers

    /// Launches past the session gate, opens the first grid cell, and returns the app with the
    /// viewer presented. Skips when the test env has no photos.
    private func launchToViewer() throws -> XCUIApplication {
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

        firstCell.tap()
        let root = app.otherElements["viewer-root"]
        XCTAssertTrue(root.waitForExistence(timeout: 5), "Viewer did not open on cell tap")
        return app
    }

    private func waitForDisappearance(of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let gone = NSPredicate(format: "exists == false")
        let e = expectation(for: gone, evaluatedWith: element)
        return XCTWaiter().wait(for: [e], timeout: timeout) == .completed
    }
}
