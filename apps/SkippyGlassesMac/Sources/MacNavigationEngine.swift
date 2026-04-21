import Foundation
import CoreLocation

/// Navigation engine for SkippyGlassesMac.
///
/// Mirrors the Android NavigationEngine: fetches a Google Maps Directions route,
/// then tracks GPS position every 4 s, advancing through steps as the Captain moves.
///
/// Trigger from the menu bar: ⌘N starts a test walk to Johnny's Market.
/// ⌘. cancels. Voice trigger (future) calls navigateTo(_:mode:) directly.
@Observable
final class MacNavigationEngine {

    enum TravelMode { case walking, driving }

    struct NavStep {
        let instruction: String
        let maneuver: String
        let distanceMeters: Int
        let durationSeconds: Int
        let endLat: Double
        let endLng: Double
    }

    struct NavState {
        let steps: [NavStep]
        let currentStepIndex: Int
        let totalDistanceMeters: Int
        let totalDurationSeconds: Int
        let destination: String
        let mode: TravelMode

        var currentStep: NavStep? {
            steps.indices.contains(currentStepIndex) ? steps[currentStepIndex] : nil
        }
        var remainingDurationSeconds: Int {
            steps.dropFirst(currentStepIndex).reduce(0) { $0 + $1.durationSeconds }
        }
        var remainingDistanceMeters: Int {
            steps.dropFirst(currentStepIndex).reduce(0) { $0 + $1.distanceMeters }
        }
    }

    // ── Observables ───────────────────────────────────────────────────────────

    var state: NavState? = nil
    var isNavigating: Bool = false
    var error: String? = nil

    // ── Internals ─────────────────────────────────────────────────────────────

    // Key is the same one used by SkippyDroid — same Maps account.
    // TODO: move to xcconfig / keychain before any public build.
    private let mapsApiKey = "AIzaSyDA0emd35aDzwyGu9Z_KPwrOnqr3B9BwpQ"

    private static let advanceWalkingM: Double = 15
    private static let advanceDrivingM: Double = 40

    private var trackingTask: Task<Void, Never>?

    // ── Public API ────────────────────────────────────────────────────────────

    /// Fetch a route and begin navigation.
    /// - Parameters:
    ///   - destination: Free-text address or place name.
    ///   - mode: .walking or .driving
    ///   - from: Starting CLLocation (use HUDViewModel.shared.currentLocation).
    func navigateTo(_ destination: String, mode: TravelMode, from location: CLLocation) async {
        cancel()
        error = nil

        let origin = "\(location.coordinate.latitude),\(location.coordinate.longitude)"
        let modeParam = mode == .walking ? "walking" : "driving"
        guard let encodedDest = destination.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string:
                "https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=\(origin)" +
                "&destination=\(encodedDest)" +
                "&mode=\(modeParam)" +
                "&key=\(mapsApiKey)")
        else { return }

        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard let newState = parseDirections(data: data, destination: destination, mode: mode),
                  !newState.steps.isEmpty else {
                self.error = "No route found to \"\(destination)\""
                return
            }
            self.state       = newState
            self.isNavigating = true
            startTracking()
        } catch {
            self.error = "Network error — check connection"
        }
    }

    func cancel() {
        trackingTask?.cancel()
        trackingTask = nil
        state        = nil
        isNavigating = false
    }

    // ── Tracking ──────────────────────────────────────────────────────────────

    private func startTracking() {
        trackingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(4))
                guard !Task.isCancelled else { break }
                await MainActor.run { self?.advanceIfClose() }
            }
        }
    }

    @MainActor
    private func advanceIfClose() {
        guard let current = state,
              let step    = current.currentStep,
              let loc     = HUDViewModel.shared.currentLocation else { return }

        let stepEnd   = CLLocation(latitude: step.endLat, longitude: step.endLng)
        let dist      = loc.distance(from: stepEnd)
        let threshold = current.mode == .walking ? Self.advanceWalkingM : Self.advanceDrivingM

        guard dist <= threshold else { return }

        let next = current.currentStepIndex + 1
        if next >= current.steps.count {
            cancel()    // arrived
        } else {
            state = NavState(
                steps: current.steps,
                currentStepIndex: next,
                totalDistanceMeters: current.totalDistanceMeters,
                totalDurationSeconds: current.totalDurationSeconds,
                destination: current.destination,
                mode: current.mode
            )
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private func parseDirections(data: Data, destination: String, mode: TravelMode) -> NavState? {
        guard let json     = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let status   = json["status"] as? String, status == "OK",
              let routes   = json["routes"]  as? [[String: Any]], let route = routes.first,
              let legs     = route["legs"]   as? [[String: Any]], let leg   = legs.first
        else { return nil }

        let totalDist = (leg["distance"] as? [String: Any])?["value"] as? Int ?? 0
        let totalDur  = (leg["duration"] as? [String: Any])?["value"] as? Int ?? 0

        guard let stepsJson = leg["steps"] as? [[String: Any]] else { return nil }

        let steps: [NavStep] = stepsJson.compactMap { s in
            guard let dist   = (s["distance"] as? [String: Any])?["value"] as? Int,
                  let dur    = (s["duration"] as? [String: Any])?["value"] as? Int,
                  let endLoc = s["end_location"] as? [String: Any],
                  let endLat = endLoc["lat"] as? Double,
                  let endLng = endLoc["lng"] as? Double,
                  let html   = s["html_instructions"] as? String
            else { return nil }
            let maneuver = s["maneuver"] as? String ?? "straight"
            return NavStep(
                instruction: stripHtml(html),
                maneuver: maneuver,
                distanceMeters: dist,
                durationSeconds: dur,
                endLat: endLat,
                endLng: endLng
            )
        }

        return NavState(
            steps: steps,
            currentStepIndex: 0,
            totalDistanceMeters: totalDist,
            totalDurationSeconds: totalDur,
            destination: destination,
            mode: mode
        )
    }

    private func stripHtml(_ html: String) -> String {
        var s = html
        while let r = s.range(of: "<[^>]+>", options: .regularExpression) {
            s.replaceSubrange(r, with: "")
        }
        return s.trimmingCharacters(in: .whitespaces)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /// Compass bearing from `from` to `(endLat, endLng)`, relative to `headingDeg`.
    /// Returns [0, 360): 0° = ahead, 90° = right, 270° = left.
    static func relativeBearing(
        from loc: CLLocation,
        endLat: Double, endLng: Double,
        headingDeg: Double
    ) -> Double {
        let lat1  = loc.coordinate.latitude  * .pi / 180
        let lat2  = endLat * .pi / 180
        let dLng  = (endLng - loc.coordinate.longitude) * .pi / 180

        let y = sin(dLng) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        let bearing = atan2(y, x) * 180 / .pi
        let absolute = (bearing + 360).truncatingRemainder(dividingBy: 360)
        return (absolute - headingDeg + 360).truncatingRemainder(dividingBy: 360)
    }

    static func maneuverArrow(_ maneuver: String) -> String {
        switch maneuver {
        case "turn-left":         return "↰"
        case "turn-right":        return "↱"
        case "turn-sharp-left":   return "↩"
        case "turn-sharp-right":  return "↪"
        case "turn-slight-left":  return "↖"
        case "turn-slight-right": return "↗"
        case "straight", "merge": return "⬆"
        case "ramp-left",  "fork-left":       return "↙"
        case "ramp-right", "fork-right":      return "↘"
        case "roundabout-left":   return "↺"
        case "roundabout-right":  return "↻"
        case "u-turn-left":       return "↩"
        case "u-turn-right":      return "↪"
        case "ferry":             return "⛴"
        default:                  return "⬆"
        }
    }

    static func formatDist(_ meters: Int) -> String {
        meters < 1000 ? "\(meters) m" : String(format: "%.1f km", Double(meters) / 1000)
    }

    static func formatDuration(_ seconds: Int) -> String {
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        return h > 0 ? "\(h)h \(m)min" : "\(m)min"
    }
}
