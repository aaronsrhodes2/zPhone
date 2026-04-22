import AVFoundation
import Speech

/// Layer 4 — Voice / Input for SkippyGlassesMac.
///
/// Continuously listens to the microphone via the same AVAudioEngine tap
/// that SilenceDetector and CommandRecognizer share (single audio stream,
/// two consumers — no double-capture penalty).
///
/// When the Captain stops talking (silence threshold reached), the accumulated
/// transcript is parsed for Skippy commands and the appropriate callback fires.
///
/// ── Supported commands (speak naturally, no exact phrasing required) ──────
///   "navigate to [destination]"          → onNavigate(destination)
///   "cancel" / "stop navigation"         → onCancel()
///
/// ── Adding new commands ───────────────────────────────────────────────────
///   Parse in handleTranscript() below.  The full lowercased transcript is
///   available; use contains() / range(of:) to match phrases.
/// ─────────────────────────────────────────────────────────────────────────
@Observable
final class VoiceEngine {
    static let shared = VoiceEngine()

    // MARK: - Sub-components (exposed so menu bar can show mic level etc.)
    let detector   = SilenceDetector()
    let recognizer = CommandRecognizer()
    let micDevices = AudioDeviceManager()

    // MARK: - State
    private(set) var isListening = false
    private(set) var lastHeard: String = ""     // last transcript — useful for debugging

    // MARK: - Callbacks (wired by AppDelegate)
    var onNavigate: ((String) -> Void)?          // destination string
    var onCancel:   (() -> Void)?

    // MARK: - Lifecycle

    func start() {
        // SFSpeechRecognizer authorization is requested in CommandRecognizer.start();
        // PermissionsManager also requests it up-front at launch.
        recognizer.start()

        // Share the audio engine tap: SilenceDetector captures frames and
        // forwards each buffer to CommandRecognizer for live transcription.
        detector.onBuffer = { [weak self] buffer in
            self?.recognizer.feed(buffer: buffer)
        }

        // When the Captain stops talking, parse what was said.
        detector.onSilenceThresholdReached = { [weak self] in
            self?.handleTranscript()
        }

        do {
            try detector.start(deviceManager: micDevices)
            isListening = true
        } catch {
            print("[VoiceEngine] failed to start: \(error)")
        }
    }

    func stop() {
        detector.stop()
        recognizer.stop()
        isListening = false
    }

    /// Hot-swap the active microphone without stopping recognition.
    func selectMic(_ device: AudioDevice) {
        micDevices.selectedDeviceID = device.id
        guard isListening else { return }
        stop()
        start()
    }

    // MARK: - Command parsing

    private func handleTranscript() {
        let raw  = recognizer.currentTranscript
        let text = raw.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        recognizer.clearTranscript()
        guard !text.isEmpty else { return }

        lastHeard = text
        DiagnosticsLog.shared.log(.voice, "heard: \(text)")

        // Restart recognizer immediately so the next utterance gets a clean request —
        // prevents stale audio from the consumed command bleeding forward.
        recognizer.restart()

        // ── Navigate ──────────────────────────────────────────────────────────
        // "navigate to Starbucks downtown" / "hey skippy navigate to the park"
        if let range = text.range(of: "navigate to ") {
            let destination = String(text[range.upperBound...])
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !destination.isEmpty {
                DiagnosticsLog.shared.log(.voice, "→ navigate: \(destination)")
                onNavigate?(destination)
                return
            }
        }

        // ── Cancel ────────────────────────────────────────────────────────────
        // "cancel" / "stop navigation" / "cancel navigation" / "never mind"
        if text.contains("cancel") || text.contains("stop navigation") || text.contains("never mind") {
            DiagnosticsLog.shared.log(.voice, "→ cancel")
            onCancel?()
            return
        }

        // ── Unrecognised — log for future command expansion ───────────────────
        DiagnosticsLog.shared.log(.voice, "no match: \(text)")
    }
}
