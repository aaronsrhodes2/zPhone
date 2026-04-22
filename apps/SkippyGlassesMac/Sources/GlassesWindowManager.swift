import AppKit
import SwiftUI
import CoreGraphics

/// Finds the external display (VITURE) and covers it with a borderless NSWindow
/// at .screenSaver level — above wallpaper, Dock, and all normal app windows.
///
/// Three-layer detection strategy:
///   1. CoreGraphics reconfiguration callback — fires at IOKit level, before NSScreen updates
///   2. NSApplication.didChangeScreenParametersNotification — NSScreen-level notification
///   3. 0.5 s polling timer — final backstop for slow DP Alt Mode cables
///
/// All log output goes to both NSLog and /tmp/skippy-gwm.log so reconnect
/// events are visible regardless of unified log permission issues.
@MainActor
final class GlassesWindowManager {
    static let shared = GlassesWindowManager()

    private(set) var glassesConnected = false
    private var glassesWindow: NSWindow?
    private var reconcileTimer: Timer?
    private var tickCount = 0

    // MARK: - Dual logging

    private func gwmLog(_ msg: String) {
        NSLog("[GWM] %@", msg)
        let line = "\(Date()) [GWM] \(msg)\n"
        guard let data = line.data(using: .utf8),
              let fh = FileHandle(forUpdatingAtPath: "/tmp/skippy-gwm.log")
        else { return }
        fh.seekToEndOfFile()
        fh.write(data)
        fh.closeFile()
    }

    // MARK: - Lifecycle

    func start() {
        FileManager.default.createFile(atPath: "/tmp/skippy-gwm.log", contents: nil)
        gwmLog("start — screens=\(NSScreen.screens.count)")

        claimExternalDisplay()

        // Layer 1 — NSApplication notification
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(screensChanged),
            name: NSApplication.didChangeScreenParametersNotification,
            object: nil
        )

        // Layer 2 — CoreGraphics IOKit-level callback fires before NSScreen.screens updates.
        // Unmanaged passthrough gives the C callback a way to call back to us.
        CGDisplayRegisterReconfigurationCallback({ displayId, flags, userInfo in
            guard let userInfo else { return }
            let mgr = Unmanaged<GlassesWindowManager>.fromOpaque(userInfo).takeUnretainedValue()
            DispatchQueue.main.async {
                Task { @MainActor in mgr.handleCGDisplay(id: displayId, flags: flags) }
            }
        }, Unmanaged.passUnretained(self).toOpaque())

        // Layer 3 — 0.5 s polling backstop; logs every 2 s so we can see screen count live
        reconcileTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.tickCount += 1
                if self.tickCount % 4 == 0 {
                    self.gwmLog("tick — screens=\(NSScreen.screens.count) connected=\(self.glassesConnected)")
                }
                self.claimExternalDisplay()
            }
        }
    }

    func stop() {
        NotificationCenter.default.removeObserver(self)
        reconcileTimer?.invalidate()
        reconcileTimer = nil
        glassesWindow?.close()
        glassesWindow = nil
        glassesConnected = false
        try? FileManager.default.removeItem(atPath: "/tmp/skippy-glasses-display.id")
    }

    // MARK: - Display ID export (for `make mac-shot`)

    /// Writes the VITURE's CGDirectDisplayID to /tmp so `make mac-shot` can
    /// capture exactly that display via `screencapture -l<id>`.
    private func exportDisplayID(for screen: NSScreen) {
        guard let num = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber
        else { return }
        let id = num.uint32Value
        try? "\(id)\n".write(toFile: "/tmp/skippy-glasses-display.id",
                             atomically: true, encoding: .utf8)
        gwmLog("exported display id=\(id) for screenshot tool")
    }

    // MARK: - Event handlers

    @objc private func screensChanged() {
        gwmLog("screensChanged — screens=\(NSScreen.screens.count)")
        claimExternalDisplay()
    }

    private func handleCGDisplay(id: CGDirectDisplayID, flags: CGDisplayChangeSummaryFlags) {
        // beginConfigurationFlag fires BEFORE the change — skip it; we want the post-change event.
        guard !flags.contains(.beginConfigurationFlag) else { return }
        gwmLog("CG event id=\(id) flags=0x\(String(flags.rawValue, radix: 16)) screens=\(NSScreen.screens.count)")

        if flags.contains(.addFlag) || flags.contains(.enabledFlag) {
            claimExternalDisplay()
            // Retry ladder — NSScreen.screens may lag up to several seconds behind CG events
            Task { @MainActor in
                for ms in [200, 500, 1_000, 2_000, 4_000] {
                    guard !self.glassesConnected else { break }
                    try? await Task.sleep(for: .milliseconds(ms))
                    self.gwmLog("CG retry \(ms)ms — screens=\(NSScreen.screens.count) connected=\(self.glassesConnected)")
                    self.claimExternalDisplay()
                }
            }
        } else if flags.contains(.removeFlag) || flags.contains(.disabledFlag) {
            glassesWindow?.close()
            glassesWindow = nil
            glassesConnected = false
            gwmLog("display removed — HUD closed")
        }
    }

    // MARK: - Core hijack

    private func claimExternalDisplay() {
        guard let target = externalScreen() else {
            if glassesConnected { gwmLog("external screen gone — closing HUD") }
            glassesWindow?.close()
            glassesWindow = nil
            glassesConnected = false
            try? FileManager.default.removeItem(atPath: "/tmp/skippy-glasses-display.id")
            return
        }

        // Already covering the right screen AND visible? Nothing to do.
        if let w = glassesWindow, w.screen == target, w.isVisible {
            exportDisplayID(for: target)   // keep file fresh in case it was cleared
            return
        }

        gwmLog("claiming \(target.localizedName) \(target.frame)")
        glassesWindow?.close()
        glassesWindow = nil

        let win = NSWindow(
            contentRect: target.frame,
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )

        win.level                = .screenSaver   // 2001 — above everything on external display
        win.backgroundColor      = .black
        win.isOpaque             = true
        win.hasShadow            = false
        win.isReleasedWhenClosed = false

        // KEY FIX: hidesOnDeactivate defaults to true, which hides the window whenever
        // another app is active. SkippyGlassesMac is always "inactive" (LSUIElement
        // background app), so the window was hiding immediately after orderFrontRegardless().
        win.hidesOnDeactivate    = false

        win.collectionBehavior   = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        win.setFrame(target.frame, display: true)
        win.contentView = NSHostingView(rootView: GlassesHUDView())

        // orderFrontRegardless() bypasses the "app must be active" requirement for
        // LSUIElement background apps.
        win.orderFrontRegardless()

        glassesWindow    = win
        glassesConnected = true
        exportDisplayID(for: target)
        gwmLog("HUD shown on \(target.localizedName) — visible=\(win.isVisible) assignedScreen=\(win.screen?.localizedName ?? "nil")")
    }

    /// Returns the first non-main screen — the VITURE when plugged in.
    private func externalScreen() -> NSScreen? {
        NSScreen.screens.first { $0 != NSScreen.main }
    }
}
