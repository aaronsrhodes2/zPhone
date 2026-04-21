import Foundation

/// Tracks which AR app is currently active on the glasses display.
/// The iPad control surface writes to this; GlassesRootView reads from it.
@Observable
class GlassesAppState {
    static let shared = GlassesAppState()

    enum ActiveApp {
        case none
        case translation
        // Future: .pacMan, .sunBlock, .yeyBuddy, .starMap
    }

    var activeApp: ActiveApp = .none

    func launch(_ app: ActiveApp) {
        // Stop previous app's engine if needed
        if activeApp == .translation, app != .translation {
            TranslationEngine.shared.stop()
        }

        activeApp = app

        switch app {
        case .translation:
            TranslationEngine.shared.start()
        case .none:
            TranslationEngine.shared.stop()
        }
    }
}
