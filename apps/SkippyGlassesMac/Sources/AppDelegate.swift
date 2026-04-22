import AppKit

class AppDelegate: NSObject, NSApplicationDelegate {

    func applicationDidFinishLaunching(_ notification: Notification) {
        // All permission requests in one place — fires system dialogs only on first launch.
        PermissionsManager.shared.requestAll()

        HUDViewModel.shared.start()
        GlassesWindowManager.shared.start()
        startVoice()
    }

    func applicationWillTerminate(_ notification: Notification) {
        VoiceEngine.shared.stop()
        HUDViewModel.shared.stop()
        GlassesWindowManager.shared.stop()
    }

    // MARK: - Voice

    private func startVoice() {
        let hud = HUDViewModel.shared

        VoiceEngine.shared.onNavigate = { destination in
            guard let loc = hud.currentLocation else {
                DiagnosticsLog.shared.log(.error, "nav callback: no GPS fix yet")
                return
            }
            DiagnosticsLog.shared.log(.nav, "callback: \(destination)")
            Task {
                await hud.navEngine.navigateTo(destination, mode: .walking, from: loc)
            }
        }

        VoiceEngine.shared.onCancel = {
            DiagnosticsLog.shared.log(.nav, "callback: cancel")
            hud.navEngine.cancel()
        }

        VoiceEngine.shared.start()
        DiagnosticsLog.shared.log(.sys, "voice engine started")
    }
}
