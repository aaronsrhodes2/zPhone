import AppKit
import Foundation

/// Launches the Android emulator (SkippyS23 AVD) from the Mac menu bar.
///
/// Looks for the emulator binary in the standard Android SDK location.
/// If it's already running this is a silent no-op (emulator ignores duplicate starts).
enum EmulatorLauncher {

    static func start() {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        let emulatorBin = "\(home)/Library/Android/sdk/emulator/emulator"

        guard FileManager.default.isExecutableFile(atPath: emulatorBin) else {
            // SDK not at the default path — fall back to opening Android Studio
            let url = URL(fileURLWithPath: "/Applications/Android Studio.app")
            if FileManager.default.fileExists(atPath: url.path) {
                NSWorkspace.shared.openApplication(
                    at: url,
                    configuration: NSWorkspace.OpenConfiguration()
                )
            }
            return
        }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: emulatorBin)
        process.arguments = ["-avd", "SkippyS23", "-gpu", "host", "-memory", "4096"]

        // Detach from this process so it keeps running if SkippyGlassesMac quits
        process.standardOutput = FileHandle.nullDevice
        process.standardError  = FileHandle.nullDevice

        try? process.run()   // fire-and-forget
    }
}
