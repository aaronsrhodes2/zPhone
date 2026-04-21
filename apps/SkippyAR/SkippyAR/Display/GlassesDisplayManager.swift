import Foundation

@Observable
class GlassesDisplayManager {
    static let shared = GlassesDisplayManager()
    var isConnected: Bool = false
}
