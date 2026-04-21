import Foundation
import CoreLocation

/// Drives the glasses HUD with live sensor data.
///
/// On macOS: GPS via WiFi positioning, clock via Timer.
/// Compass heading is unavailable (Macs have no magnetometer) — shows "--°".
@Observable
final class HUDViewModel: NSObject, CLLocationManagerDelegate {
    static let shared = HUDViewModel()

    // Clock
    var currentTime: Date = Date()

    // Compass — static on Mac (no magnetometer)
    var headingDegrees: Double = -1     // negative = unavailable
    var cardinalDirection: String = "--"

    // GPS
    var latitude: Double = 0
    var longitude: Double = 0
    var locationAccuracy: Double = -1   // negative = unavailable

    private let location = CLLocationManager()
    private var timer: Timer?

    override init() {
        super.init()
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyBest
    }

    func start() {
        location.requestWhenInUseAuthorization()
        location.startUpdatingLocation()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.currentTime = Date()
        }
    }

    func stop() {
        location.stopUpdatingLocation()
        timer?.invalidate()
        timer = nil
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        latitude = loc.coordinate.latitude
        longitude = loc.coordinate.longitude
        locationAccuracy = loc.horizontalAccuracy
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways:
            manager.startUpdatingLocation()
        default:
            break
        }
    }
}
