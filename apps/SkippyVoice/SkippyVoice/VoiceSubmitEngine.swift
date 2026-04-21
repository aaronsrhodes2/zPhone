import Foundation
import AVFoundation

/// Coordinates SilenceDetector + CommandRecognizer + FrontmostAppWatcher + KeystrokeInjector.
///
/// Flow:
///   1. SilenceDetector and CommandRecognizer share the same AVAudioEngine mic tap.
///   2. CommandRecognizer transcribes speech continuously via SFSpeechRecognizer.
///   3. On silence after speech, the transcript is pasted into the frontmost target app
///      and Return is injected — no keyboard required by the user.
///
/// Modes:
///   .normal      — silence → paste transcript → Return (submit)
///   .holdForMore — silence → paste segment → Shift+Return; "send it" → Return
@Observable
class VoiceSubmitEngine {
    static let shared = VoiceSubmitEngine()

    enum Mode {
        case normal
        case holdForMore
    }

    let detector  = SilenceDetector()
    let commands  = CommandRecognizer()
    let watcher   = FrontmostAppWatcher()
    let micDevices = AudioDeviceManager()

    var mode: Mode = .normal
    var isEnabled: Bool = true { didSet { isEnabled ? tryStart() : stopListening() } }
    var silenceDuration: Double {
        get { detector.silenceDuration }
        set { detector.silenceDuration = newValue }
    }
    var isListening: Bool = false
    var lastSubmitAt: Date? = nil
    var lastCommand: String? = nil
    var didSubmitFlash: Bool = false   // pulses true briefly on each submit — drives LED flash
    var targetApps: [TargetApp] = TargetApps.defaults

    var hasAccessibilityPermission: Bool { KeystrokeInjector.hasPermission }

    var frontmostIsTarget: Bool {
        guard let id = watcher.frontmostBundleID else { return false }
        return targetApps.contains { $0.bundleID == id }
    }

    private init() {
        detector.silenceDuration = 2.5

        detector.onSilenceThresholdReached = { [weak self] in
            self?.handleSilence()
        }

        commands.onCommand = { [weak self] command in
            self?.handleCommand(command)
        }
    }

    func start() {
        watcher.start()
        commands.start()
        tryStart()
    }

    // MARK: - Private

    /// Hot-swap the active microphone. Restarts the audio engine on the new device.
    func selectMic(_ device: AudioDevice) {
        micDevices.selectedDeviceID = device.id
        guard isListening else { return }
        stopListening()
        tryStart()
    }

    private func tryStart() {
        guard isEnabled, !isListening else { return }
        do {
            detector.onBuffer = { [weak self] buffer in
                self?.commands.feed(buffer: buffer)
            }
            try detector.start(deviceManager: micDevices)
            isListening = true
        } catch {
            print("SilenceDetector start error: \(error)")
        }
    }

    private func stopListening() {
        detector.stop()
        commands.stop()
        isListening = false
    }

    private func handleSilence() {
        guard isEnabled, frontmostIsTarget else { return }
        guard hasAccessibilityPermission else { KeystrokeInjector.requestPermission(); return }

        let transcript = commands.currentTranscript
        guard !transcript.isEmpty else { return }

        switch mode {
        case .normal:
            commands.clearTranscript()
            KeystrokeInjector.pasteText(transcript, thenSubmit: true)
            lastSubmitAt = Date()
            flashSubmit()

        case .holdForMore:
            // Paste this segment as a soft newline, keep mode active for next segment
            commands.clearTranscript()
            KeystrokeInjector.pasteText(transcript, thenSubmit: false)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
                KeystrokeInjector.pressShiftReturn()
            }
        }
    }

    private func handleCommand(_ command: CommandRecognizer.Command) {
        switch command {
        case .holdForMore:
            mode = .holdForMore
            lastCommand = "hold for more"
            detector.silenceDuration = 1.5

        case .sendIt:
            // Paste any remaining transcript, then submit
            let transcript = commands.currentTranscript
            commands.clearTranscript()
            if frontmostIsTarget {
                if transcript.isEmpty {
                    KeystrokeInjector.pressReturn()
                } else {
                    KeystrokeInjector.pasteText(transcript, thenSubmit: true)
                }
            }
            lastSubmitAt = Date()
            exitHoldMode()
            lastCommand = "send it"

        case .neverMind:
            commands.clearTranscript()
            if frontmostIsTarget { KeystrokeInjector.clearFocusedField() }
            exitHoldMode()
            lastCommand = "never mind"
        }
    }

    private func exitHoldMode() {
        mode = .normal
        detector.silenceDuration = 2.5
    }

    private func flashSubmit() {
        didSubmitFlash = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            self.didSubmitFlash = false
        }
    }
}
