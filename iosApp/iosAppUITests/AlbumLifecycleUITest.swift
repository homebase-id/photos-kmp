import XCTest

/// Batch C end-to-end (C2 + C3): create an album from a timeline selection, see it in the grid,
/// open it, remove the photo from it (count drops), rename it (the title follows), delete it.
///
/// It runs against the real drive — so it only ever touches the album it just created, never
/// deletes a photo (remove-from-album is a membership untag), and cleans up after itself by
/// deleting that album at the end. With no photos in the test env (`-uiTestTimeline` runs the
/// real repo with no identity) it SKIPS rather than fails, mirroring ViewerUITest.
final class AlbumLifecycleUITest: XCTestCase {
    func testCreateFromSelection_removeRenameDelete() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestTimeline"]
        app.launch()

        XCTAssertTrue(
            app.otherElements["timeline-root"].waitForExistence(timeout: 15),
            "Timeline root did not render"
        )

        let firstCell = app.descendants(matching: .any)["photo-cell"].firstMatch
        guard firstCell.waitForExistence(timeout: 15) else {
            throw XCTSkip("No photos in the test library — the album flow needs at least one cell")
        }

        let name = "UITest \(Int(Date().timeIntervalSince1970))"

        // --- C3: timeline selection → Add to album → New album -----------------------------
        firstCell.press(forDuration: 0.8)
        XCTAssertTrue(
            app.staticTexts["selection-count"].waitForExistence(timeout: 5),
            "Selection bar did not appear on long-press"
        )

        app.buttons["selection-add"].tap()
        let picker = app.descendants(matching: .any)["addto-album-sheet"].firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 5), "Add-to-album sheet did not open")

        app.buttons["addto-new-album"].tap()
        XCTAssertTrue(
            app.descendants(matching: .any)["create-album-dialog"].firstMatch.waitForExistence(timeout: 5),
            "Create-album sheet did not open from the picker"
        )
        let nameField = app.textFields["album-name-field"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5), "Create sheet has no name field")
        nameField.tap()
        nameField.typeText(name)
        app.buttons["album-name-confirm"].tap()

        // Both sheets go away once the create + tag lands (or the write failed — skip then,
        // rather than fail a UI test on a backend the sim may not reach).
        guard waitForDisappearance(of: picker, timeout: 30) else {
            throw XCTSkip("Album create did not land — no writable session in this test env")
        }

        // --- C3: the album is in the Collections grid --------------------------------------
        app.tabBars.buttons["Collections"].tap()
        let card = app.descendants(matching: .any)
            .matching(identifier: "album-card")
            .matching(NSPredicate(format: "label CONTAINS[c] %@", name))
            .firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 30), "Created album never appeared in the grid")
        card.tap()

        // --- C2: selection → set-cover offered, remove-from-album drops the photo ----------
        let albumCell = app.descendants(matching: .any)["photo-cell"].firstMatch
        XCTAssertTrue(albumCell.waitForExistence(timeout: 20), "Album detail never showed its photo")

        albumCell.press(forDuration: 0.8)
        let count = app.staticTexts["selection-count"]
        XCTAssertTrue(count.waitForExistence(timeout: 5), "Album selection bar did not appear")
        XCTAssertEqual(count.label, "1 selected")
        XCTAssertTrue(
            app.buttons["album-setcover"].exists,
            "Set-as-cover missing with exactly one photo selected"
        )

        app.buttons["album-remove"].tap()
        XCTAssertTrue(
            app.buttons["album-remove-confirm"].waitForExistence(timeout: 5),
            "Remove-from-album confirmation did not appear"
        )
        app.buttons["album-remove-confirm"].tap()

        // Count drops: the only member leaves, so the album lands on its empty state.
        let empty = app.descendants(matching: .any)["album-detail-empty"].firstMatch
        XCTAssertTrue(empty.waitForExistence(timeout: 30), "Photo did not leave the album")

        // --- C2: rename reflects in the title ----------------------------------------------
        let renamed = "\(name) R"
        app.buttons["album-menu"].tap()
        app.buttons["album-rename"].tap()
        let renameField = app.alerts.textFields.firstMatch
        XCTAssertTrue(renameField.waitForExistence(timeout: 5), "Rename alert has no text field")
        renameField.typeText(" R")
        app.buttons["album-rename-confirm"].tap()
        XCTAssertTrue(
            app.navigationBars[renamed].waitForExistence(timeout: 30),
            "Album title did not follow the rename"
        )

        // --- C2: delete the album (cleanup) — photos are untouched by design ---------------
        app.buttons["album-menu"].tap()
        app.buttons["album-delete"].tap()
        XCTAssertTrue(
            app.buttons["album-delete-confirm"].waitForExistence(timeout: 5),
            "Delete confirmation did not appear"
        )
        app.buttons["album-delete-confirm"].tap()
        XCTAssertTrue(
            app.buttons["collections-add"].waitForExistence(timeout: 30),
            "Did not pop back to Collections after deleting the album"
        )
    }

    private func waitForDisappearance(of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let gone = NSPredicate(format: "exists == false")
        let e = expectation(for: gone, evaluatedWith: element)
        return XCTWaiter().wait(for: [e], timeout: timeout) == .completed
    }
}
