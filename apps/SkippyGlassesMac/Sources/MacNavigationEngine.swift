import Foundation
import CoreLocation

/// Navigation engine for SkippyGlassesMac.
///
/// Fetches a Google Maps Directions route, tracks GPS every 4 s, advances through
/// steps, and maintains a `GroundDotTrail` — the world-anchored dot positions
/// painted onto the real ground at specific GPS coordinates.
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
        let polylinePoints: [(lat: Double, lng: Double)]   // full path for this step
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
    var isFetchingRoute: Bool = false   // true while the Directions API request is in flight
    var error: String? = nil

    /// The world-anchored dot trail — observed by GlassesHUDView for rendering.
    let trail = GroundDotTrail()

    // ── Internals ─────────────────────────────────────────────────────────────

    private let mapsApiKey = "AIzaSyDA0emd35aDzwyGu9Z_KPwrOnqr3B9BwpQ"
    private static let advanceWalkingM: Double = 15
    private static let advanceDrivingM: Double = 40
    private var trackingTask: Task<Void, Never>?

    // ── Public API ────────────────────────────────────────────────────────────

    func navigateTo(_ destination: String, mode: TravelMode, from location: CLLocation) async {
        DiagnosticsLog.shared.log(.nav, "navigateTo: \(destination) (\(mode == .walking ? "walk" : "drive"))")
        cancel()
        error = nil
        isFetchingRoute = true
        defer { isFetchingRoute = false }

        let origin = "\(location.coordinate.latitude),\(location.coordinate.longitude)"
        let modeParam = mode == .walking ? "walking" : "driving"
        guard let encodedDest = destination.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string:
                "https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=\(origin)" +
                "&destination=\(encodedDest)" +
                "&mode=\(modeParam)" +
                "&key=\(mapsApiKey)")
        else {
            DiagnosticsLog.shared.log(.error, "URL build failed: \(destination)")
            return
        }

        DiagnosticsLog.shared.log(.net, "GET directions → \(destination)")

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            DiagnosticsLog.shared.log(.net, "↳ \(status) \(data.count)B")

            guard let newState = parseDirections(data: data, destination: destination, mode: mode),
                  !newState.steps.isEmpty else {
                self.error = "No route found to \"\(destination)\""
                DiagnosticsLog.shared.log(.error, "parse: no route in response")
                return
            }
            self.state = newState
            self.isNavigating = true

            // Paint dots onto the world at GPS coordinates along the first step's polyline.
            if let firstStep = newState.steps.first {
                trail.repaint(
                    polyline: firstStep.polylinePoints,
                    fromLat: location.coordinate.latitude,
                    fromLng: location.coordinate.longitude
                )
                DiagnosticsLog.shared.log(.nav, "route: \(newState.steps.count) steps, \(trail.dots.count) dots")
            }

            startTracking()
        } catch {
            self.error = "Network error — check connection"
            DiagnosticsLog.shared.log(.error, "network: \(error.localizedDescription)")
        }
    }

    func cancel() {
        trackingTask?.cancel()
        trackingTask = nil
        state = nil
        isNavigating = false
        trail.clear()
    }

    // ── Tracking ──────────────────────────────────────────────────────────────

    private func startTracking() {
        trackingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(4))
                guard !Task.isCancelled else { break }
                await MainActor.run { self?.tick() }
            }
        }
    }

    @MainActor
    private func tick() {
        guard let current = state,
              let step    = current.currentStep,
              let loc     = HUDViewModel.shared.currentLocation else { return }

        let lat = loc.coordinate.latitude
        let lng = loc.coordinate.longitude

        // Eat any dots the Captain has walked past.
        trail.eatConsumed(fromLat: lat, fromLng: lng)

        // Advance to next step if close enough to current step's endpoint.
        let stepEnd   = CLLocation(latitude: step.endLat, longitude: step.endLng)
        let dist      = loc.distance(from: stepEnd)
        let threshold = current.mode == .walking ? Self.advanceWalkingM : Self.advanceDrivingM

        if dist <= threshold {
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
                // Repaint dots along the new step's polyline.
                trail.repaint(
                    polyline: current.steps[next].polylinePoints,
                    fromLat: lat,
                    fromLng: lng
                )
            }
        } else if trail.dots.count < 3 {
            // Trail is nearly empty (dots eaten) — repaint with remaining polyline.
            trail.repaint(polyline: step.polylinePoints, fromLat: lat, fromLng: lng)
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private func parseDirections(data: Data, destination: String, mode: TravelMode) -> NavState? {
        guard let json   = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let status = json["status"] as? String, status == "OK",
              let routes = json["routes"] as? [[String: Any]], let route = routes.first,
              let legs   = route["legs"]  as? [[String: Any]], let leg   = legs.first
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

            // Extract full polyline; fall back to straight line if missing.
            let polyline: [(lat: Double, lng: Double)]
            if let pl = s["polyline"] as? [String: Any],
               let encoded = pl["points"] as? String,
               !encoded.isEmpty {
                polyline = PolylineDecoder.decode(encoded)
            } else {
                let startLoc = s["start_location"] as? [String: Any]
                let sLat = startLoc?["lat"] as? Double ?? endLat
                let sLng = startLoc?["lng"] as? Double ?? endLng
                polyline = [(sLat, sLng), (endLat, endLng)]
            }

            return NavStep(
                instruction: stripHtml(html),
                maneuver: maneuver,
                distanceMeters: dist,
                durationSeconds: dur,
                endLat: endLat,
                endLng: endLng,
                polylinePoints: polyline
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

    // ── Bearing helper (used as synthetic heading on Mac — no magnetometer) ──

    /// Compass bearing in degrees from one GPS coordinate to another.
    /// 0° = north, 90° = east, 180° = south, 270° = west.
    static func bearing(
        fromLat: Double, fromLng: Double,
        toLat: Double, toLng: Double
    ) -> Double {
        let dNorth = (toLat - fromLat) * 111_139
        let dEast  = (toLng - fromLng) * 111_139 * cos(fromLat * .pi / 180)
        let deg = atan2(dEast, dNorth) * 180 / .pi
        return deg < 0 ? deg + 360 : deg
    }

    // ── WorldProjection ───────────────────────────────────────────────────────

    /// Projects a world GPS position onto screen coordinates given current GPS and compass heading.
    ///
    /// Uses a flat-Earth ENU approximation (accurate to < 0.1% within 200 m) plus
    /// standard perspective projection with the VITURE's horizontal field of view.
    ///
    /// Returns `nil` if the dot is behind the viewer, too close, or off-screen.
    static func worldProject(
        dotLat: Double, dotLng: Double,
        fromLat: Double, fromLng: Double,
        headingDeg: Double,
        screenSize: CGSize,
        hFovDeg: Double = 40.0,       // VITURE Luma Ultra H-FOV — tune once confirmed
        eyeHeightM: Double = 1.65     // Captain's eye height above the ground
    ) -> (point: CGPoint, scale: Double)? {

        // 1. ENU offset (metres from current position)
        let dNorth = (dotLat - fromLat) * 111_139
        let dEast  = (dotLng - fromLng) * 111_139 * cos(fromLat * .pi / 180)

        // 2. Rotate into camera frame (forward = depth axis, right = horizontal axis)
        let h = headingDeg * .pi / 180
        let forward = dNorth * cos(h) + dEast * sin(h)
        let right   = -dNorth * sin(h) + dEast * cos(h)

        guard forward > 0.5 else { return nil }   // behind or within 0.5 m

        // 3. Perspective projection
        let focalLen = (screenSize.width / 2) / tan(hFovDeg * .pi / 360)
        let screenX  = screenSize.width  / 2 + focalLen * right   / forward
        let screenY  = screenSize.height / 2 + focalLen * eyeHeightM / forward

        // Allow a 10% horizontal margin for dots near the edges; clip vertically.
        let hMargin = screenSize.width * 0.10
        guard screenX > -hMargin, screenX < screenSize.width + hMargin,
              screenY > 0,        screenY < screenSize.height else { return nil }

        let scale = 1.0 / forward   // larger = closer, smaller = farther
        return (CGPoint(x: screenX, y: screenY), scale)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static func maneuverArrow(_ maneuver: String) -> String {
        switch maneuver {
        case "turn-left":         return "↰"
        case "turn-right":        return "↱"
        case "turn-sharp-left":   return "↩"
        case "turn-sharp-right":  return "↪"
        case "turn-slight-left":  return "↖"
        case "turn-slight-right": return "↗"
        case "straight", "merge": return "⬆"
        case "ramp-left",  "fork-left":  return "↙"
        case "ramp-right", "fork-right": return "↘"
        case "roundabout-left":  return "↺"
        case "roundabout-right": return "↻"
        case "u-turn-left":  return "↩"
        case "u-turn-right": return "↪"
        case "ferry":        return "⛴"
        default:             return "⬆"
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

// MARK: - GroundDotTrail

/// Maintains a set of GPS-anchored dot positions painted on the virtual ground.
///
/// Dots are placed at fixed world distances along the route polyline.
/// They do not move — the projection engine makes them appear world-locked
/// by recomputing their screen positions each frame from the Captain's GPS + heading.
@Observable
final class GroundDotTrail {

    struct Dot: Identifiable {
        let id = UUID()
        let lat: Double
        let lng: Double
    }

    /// World distances at which dots are painted (metres ahead along the route).
    /// Exponential spacing: dense near feet (perspective looks like ground),
    /// sparse near horizon.
    static let dotDistancesM: [Double] = [2, 4, 7, 12, 20, 33, 55, 90]

    /// Distance within which a dot is considered "eaten" (Captain has passed it).
    static let eatRadiusM: Double = 2.0

    private(set) var dots: [Dot] = []

    /// Recompute all dot world positions along `polyline`, starting from `(fromLat, fromLng)`.
    /// Call this when navigation begins or the route changes.
    func repaint(polyline: [(lat: Double, lng: Double)], fromLat: Double, fromLng: Double) {
        guard !polyline.isEmpty else { dots = []; return }

        let poly = polyline.count == 1
            ? [polyline[0], polyline[0]]  // degenerate — guard against single-point
            : polyline

        dots = Self.dotDistancesM.compactMap { dist in
            guard let pt = Self.walkPolyline(poly, fromLat: fromLat, fromLng: fromLng, distanceM: dist)
            else { return nil }
            return Dot(lat: pt.lat, lng: pt.lng)
        }
    }

    /// Remove any dots within `eatRadiusM` of the Captain's current position.
    func eatConsumed(fromLat: Double, fromLng: Double) {
        dots = dots.filter { dot in
            Self.metersBetween(
                lat1: fromLat, lng1: fromLng,
                lat2: dot.lat, lng2: dot.lng
            ) > Self.eatRadiusM
        }
    }

    func clear() { dots = [] }

    // MARK: - Polyline walking (private geometry)

    /// Returns the GPS coordinate at `distanceM` metres ahead along `polyline`,
    /// starting from the closest point on the polyline to `(fromLat, fromLng)`.
    private static func walkPolyline(
        _ polyline: [(lat: Double, lng: Double)],
        fromLat: Double, fromLng: Double,
        distanceM: Double
    ) -> (lat: Double, lng: Double)? {
        guard polyline.count >= 2 else { return polyline.first }

        // Find the segment + t parameter closest to the Captain's current position.
        var bestSeg = 0
        var bestT: Double = 0
        var bestDist = Double.infinity

        for i in 0..<(polyline.count - 1) {
            let t = clampedT(a: polyline[i], b: polyline[i+1], p: (fromLat, fromLng))
            let pt = lerp(polyline[i], polyline[i+1], t: t)
            let d  = metersBetween(lat1: fromLat, lng1: fromLng, lat2: pt.lat, lng2: pt.lng)
            if d < bestDist { bestDist = d; bestSeg = i; bestT = t }
        }

        // Walk forward distanceM from that closest point.
        var remaining = distanceM
        var seg = bestSeg
        var t   = bestT

        while remaining > 1e-3 {
            guard seg < polyline.count - 1 else { break }
            let a = polyline[seg], b = polyline[seg + 1]
            let segLen   = metersBetween(lat1: a.lat, lng1: a.lng, lat2: b.lat, lng2: b.lng)
            let toEndLen = segLen * (1.0 - t)

            if remaining <= toEndLen {
                let newT = t + remaining / segLen
                return lerp(a, b, t: min(newT, 1.0))
            }

            remaining -= toEndLen
            seg += 1
            t = 0
        }

        return polyline.last
    }

    private static func clampedT(
        a: (lat: Double, lng: Double),
        b: (lat: Double, lng: Double),
        p: (Double, Double)
    ) -> Double {
        let dlat = b.lat - a.lat, dlng = b.lng - a.lng
        let len2 = dlat*dlat + dlng*dlng
        guard len2 > 1e-18 else { return 0 }
        return max(0, min(1, ((p.0 - a.lat)*dlat + (p.1 - a.lng)*dlng) / len2))
    }

    private static func lerp(
        _ a: (lat: Double, lng: Double),
        _ b: (lat: Double, lng: Double),
        t: Double
    ) -> (lat: Double, lng: Double) {
        (a.lat + t*(b.lat - a.lat), a.lng + t*(b.lng - a.lng))
    }

    static func metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let dN = (lat2 - lat1) * 111_139
        let dE = (lng2 - lng1) * 111_139 * cos(lat1 * .pi / 180)
        return sqrt(dN*dN + dE*dE)
    }
}
