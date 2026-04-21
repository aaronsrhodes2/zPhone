import UIKit
import SwiftUI

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }

        if session.role == .windowExternalDisplayNonInteractive {
            // This fires when the VITURE glasses are connected.
            // Everything rendered in GlassesRootView appears on the glasses display.
            let window = UIWindow(windowScene: windowScene)
            window.rootViewController = UIHostingController(rootView: GlassesRootView())
            self.window = window
            window.makeKeyAndVisible()
            GlassesDisplayManager.shared.isConnected = true
        }
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        GlassesDisplayManager.shared.isConnected = false
    }
}
