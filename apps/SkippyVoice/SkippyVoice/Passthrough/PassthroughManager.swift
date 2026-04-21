import AppKit
import AVFoundation
import CoreLocation
import SwiftUI

/// Manages the fullscreen passthrough window on the VITURE glasses display.
/// Opens a camera-feed window on the external screen so the glasses look transparent,
/// with only the HUD (compass + clock) drawn on top.
@Observable
class PassthroughManager: NSObject, CLLocationManagerDelegate {
    static let shared = PassthroughManager()

    var isActive: Bool = false
    var glassesScreen: NSScreen? { externalScreen() }

    // HUD data
    var headingDegrees: Double = 0
    var cardinalDirection: String = "N"
    var currentTime: Date = Date()

    private var window: NSWindow?
    var captureSession: AVCaptureSession?
    private let locationManager = CLLocationManager()
    private var clockTimer: Timer?
    private var screenObserver: Any?

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.headingFilter = 1
        screenObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            self?.handleScreenChange()
        }
    }

    func start() {
        guard !isActive, let screen = externalScreen() else { return }
        openWindow(on: screen)
        startCamera()
        startHUD()
        isActive = true
    }

    func stop() {
        window?.close()
        window = nil
        captureSession?.stopRunning()
        captureSession = nil
        clockTimer?.invalidate()
        isActive = false
    }

    func toggle() {
        isActive ? stop() : start()
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        headingDegrees = newHeading.magneticHeading
        cardinalDirection = cardinal(for: newHeading.magneticHeading)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedAlways ||
           manager.authorizationStatus == .authorized {
            manager.startUpdatingHeading()
        }
    }

    // MARK: - Private

    private func openWindow(on screen: NSScreen) {
        let rect = screen.frame
        let win = NSWindow(
            contentRect: rect,
            styleMask: [.borderless],
            backing: .buffered,
            defer: false,
            screen: screen
        )
        win.level = NSWindow.Level(rawValue: Int(CGWindowLevelKey.screenSaverWindow.rawValue))
        win.backgroundColor = .clear
        win.isOpaque = false
        win.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        win.ignoresMouseEvents = true

        let host = NSHostingView(rootView: PassthroughRootView(manager: self))
        host.frame = CGRect(origin: .zero, size: rect.size)
        win.contentView = host
        win.setFrame(rect, display: true)
        win.makeKeyAndOrderFront(nil)
        self.window = win
    }

    private func startCamera() {
        let session = AVCaptureSession()
        session.sessionPreset = .high

        guard let device = vitureCamera() ?? AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else { return }

        session.addInput(input)
        captureSession = session
        DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
    }

    private func startHUD() {
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingHeading()
        clockTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.currentTime = Date()
        }
    }

    /// Find the VITURE camera by name; fall back to first external camera.
    private func vitureCamera() -> AVCaptureDevice? {
        let devices = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.external, .builtInWideAngleCamera],
            mediaType: .video,
            position: .unspecified
        ).devices
        return devices.first { $0.localizedName.localizedCaseInsensitiveContains("UVC") ||
                               $0.localizedName.localizedCaseInsensitiveContains("VITURE") }
            ?? devices.first { !$0.localizedName.localizedCaseInsensitiveContains("FaceTime") }
    }

    /// First non-main screen — the VITURE glasses when in extended mode.
    private func externalScreen() -> NSScreen? {
        NSScreen.screens.first { $0 != NSScreen.main }
    }

    private func handleScreenChange() {
        guard isActive else { return }
        if externalScreen() == nil {
            stop()
        } else if window == nil, let screen = externalScreen() {
            openWindow(on: screen)
        }
    }

    private func cardinal(for degrees: Double) -> String {
        let dirs = ["N","NNE","NE","ENE","E","ESE","SE","SSE",
                    "S","SSW","SW","WSW","W","WNW","NW","NNW"]
        return dirs[Int((degrees + 11.25) / 22.5) % 16]
    }
}
