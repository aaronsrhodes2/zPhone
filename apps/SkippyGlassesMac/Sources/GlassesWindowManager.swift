import AppKit
import SwiftUI

/// Finds the external display (VITURE) and covers it with a borderless NSWindow
/// at .screenSaver level — above wallpaper, Dock, and all normal app windows.
///
/// Listens for NSApplication.didChangeScreenParametersNotification so the window
/// appears automatically when the VITURE is plugged in and closes when removed.
@MainActor
final class GlassesWindowManager {
    static let shared = GlassesWindowManager()

    private(set) var glassesConnected = false
    private var glassesWindow: NSWindow?

    func start() {
        claimExternalDisplay()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(screensChanged),
            name: NSApplication.didChangeScreenParametersNotification,
            object: nil
        )
    }

    func stop() {
        NotificationCenter.default.removeObserver(self)
        glassesWindow?.close()
        glassesWindow = nil
        glassesConnected = false
    }

    @objc private func screensChanged() {
        claimExternalDisplay()
    }

    // MARK: - Core hijack

    private func claimExternalDisplay() {
        guard let target = externalScreen() else {
            // VITURE removed (or was never plugged in) — close the window
            glassesWindow?.close()
            glassesWindow = nil
            glassesConnected = false
            return
        }

        // Already covering the right screen? Nothing to do.
        if let existing = glassesWindow,
           existing.screen == target { return }

        glassesWindow?.close()

        let window = NSWindow(
            contentRect: target.frame,
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )

        // screenSaver level: above wallpaper, Dock, menubar on the external display.
        // Primary Mac display is unaffected.
        window.level = .screenSaver

        window.backgroundColor = .black
        window.isOpaque = true
        window.hasShadow = false

        // Don't appear in Mission Control, don't cycle via Cmd-Tab
        window.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]

        // Cover the exact frame of the external screen
        window.setFrame(target.frame, display: true)

        window.contentView = NSHostingView(rootView: GlassesHUDView())
        window.makeKeyAndOrderFront(nil)

        glassesWindow = window
        glassesConnected = true
    }

    /// Returns the first non-main screen — the VITURE when plugged in.
    /// If the Captain has multiple external screens the VITURE should be first
    /// in NSScreen.screens after the main display (macOS adds them in connection order).
    private func externalScreen() -> NSScreen? {
        NSScreen.screens.first { $0 != NSScreen.main }
    }
}
