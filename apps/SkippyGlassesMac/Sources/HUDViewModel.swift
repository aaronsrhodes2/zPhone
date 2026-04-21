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
    var currentLocation: CLLocation? = nil   // used by MacNavigationEngine for step tracking

    // Battery
    var batteryPercent: Int? = nil
    var batteryCharging: Bool = true

    // Navigation
    let navEngine = MacNavigationEngine()

    private let location = CLLocationManager()
    private var timer: Timer?
    private var batteryTimer: Timer?

    override init() {
        super.init()
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyBest
    }

    func start() {
        location.requestWhenInUseAuthorization()
        location.startUpdatingLocation()

        // Clock — every second
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.currentTime = Date()
        }

        // Battery — read immediately then every 60 s
        refreshBattery()
        batteryTimer = Timer.scheduledTimer(withTimeInterval: 60.0, repeats: true) { [weak self] _ in
            self?.refreshBattery()
        }
    }

    func stop() {
        location.stopUpdatingLocation()
        timer?.invalidate();        timer = nil
        batteryTimer?.invalidate(); batteryTimer = nil
    }

    private func refreshBattery() {
        batteryPercent  = MacBattery.percent()
        batteryCharging = MacBattery.isCharging()
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        currentLocation  = loc
        latitude         = loc.coordinate.latitude
        longitude        = loc.coordinate.longitude
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
