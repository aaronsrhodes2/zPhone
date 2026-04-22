import AppKit
import AVFoundation
import CoreLocation
import Speech

/// Single source of truth for every macOS system-permission request.
///
/// Call `PermissionsManager.shared.requestAll()` once at app startup.
/// macOS TCC remembers grants by bundle ID — the system dialogs fire only
/// on first launch (or after `tccutil reset <service> com.skippy.SkippyGlassesMac`).
///
/// ── Adding a new permission ───────────────────────────────────────────────────
/// 1. Add the entitlement to Sources/SkippyGlassesMac.entitlements
/// 2. Add the NSXxxUsageDescription key to Sources/Info.plist
/// 3. Add a private request function below and call it from requestAll()
/// ─────────────────────────────────────────────────────────────────────────────
@MainActor
final class PermissionsManager: NSObject, CLLocationManagerDelegate {
    static let shared = PermissionsManager()

    private let locationManager = CLLocationManager()

    override init() {
        super.init()
        locationManager.delegate = self
    }

    /// Request every permission the app needs — fires one burst of system
    /// dialogs on first launch, then is a permanent silent no-op.
    func requestAll() {
        requestLocation()
        requestMicrophone()
        requestSpeechRecognition()
        // requestCamera()        // future: VITURE camera passthrough
        // requestNotifications() // future: notification feed overlay
    }

    // MARK: - Location

    private func requestLocation() {
        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestAlwaysAuthorization()
        case .denied, .restricted:
            // Can't prompt again — open System Settings so Captain can re-enable
            NSWorkspace.shared.open(
                URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices")!
            )
        default:
            break   // authorizedAlways or authorizedWhenInUse — nothing to do
        }
    }

    // Responds to the user's choice in the system dialog.
    // HUDViewModel has its own CLLocationManager delegate for actual location data;
    // this delegate exists only to close the permission loop if needed.
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {}

    // MARK: - Microphone

    private func requestMicrophone() {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { _ in }
        case .denied, .restricted:
            NSWorkspace.shared.open(
                URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone")!
            )
        default:
            break
        }
    }

    // MARK: - Speech recognition

    private func requestSpeechRecognition() {
        SFSpeechRecognizer.requestAuthorization { status in
            if status == .denied {
                DispatchQueue.main.async {
                    NSWorkspace.shared.open(
                        URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_SpeechRecognition")!
                    )
                }
            }
        }
    }

    // MARK: - Future permissions (stub — add entitlement + plist key before uncommenting)

    // private func requestCamera() {
    //     AVCaptureDevice.requestAccess(for: .video) { _ in }
    // }

    // private func requestNotifications() {
    //     UNUserNotificationCenter.current()
    //         .requestAuthorization(options: [.alert, .sound]) { _, _ in }
    // }
}
