import Foundation

/// Layer 0-1 observability — cross-cutting event log for voice, navigation, and network.
///
/// Every significant event in the pipeline registers here so the Captain can see the
/// full flow directly on the glasses display (no terminal required):
///
///   VOICE heard: navigate to coffee shop
///   VOICE → navigate: coffee shop
///   NAV   callback: coffee shop
///   NAV   navigateTo: coffee shop
///   NET   GET directions → coffee shop
///   NET   ↳ 200 4312B
///   NAV   route: 6 steps, 8 dots
///
/// Any broken link in the chain is immediately visible — "heard X but never reached nav"
/// means the parser missed; "NAV callback but no NET" means location was nil; etc.
///
/// Also pipes every event to NSLog so `make mac-logs` stays useful as a permanent record.
@Observable
final class DiagnosticsLog {
    static let shared = DiagnosticsLog()

    enum Kind: String {
        case voice = "VOICE"   // speech recognition, command parse
        case nav   = "NAV"     // navigation engine lifecycle
        case net   = "NET"     // network requests and responses
        case error = "ERR"     // errors at any layer
        case sys   = "SYS"     // general system events (window, permissions, etc.)
    }

    struct Event: Identifiable {
        let id      = UUID()
        let time    = Date()
        let kind: Kind
        let message: String
    }

    private(set) var events: [Event] = []
    private let maxEvents = 12

    func log(_ kind: Kind, _ message: String) {
        let event = Event(kind: kind, message: message)
        var updated = events
        updated.append(event)
        if updated.count > maxEvents {
            updated.removeFirst(updated.count - maxEvents)
        }
        events = updated
        NSLog("[%@] %@", kind.rawValue, message)
    }

    func clear() { events = [] }
}
