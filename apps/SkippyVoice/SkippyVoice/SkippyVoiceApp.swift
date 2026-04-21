import SwiftUI

@main
struct SkippyVoiceApp: App {
    @State private var engine = VoiceSubmitEngine.shared

    init() {
        // Start listening immediately on launch — no click required.
        VoiceSubmitEngine.shared.start()
    }

    var body: some Scene {
        MenuBarExtra {
            MenuBarView()
        } label: {
            LEDBarLabel(engine: engine)
        }
        .menuBarExtraStyle(.window)
    }
}
