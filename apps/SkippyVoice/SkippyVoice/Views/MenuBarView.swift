import SwiftUI

struct MenuBarView: View {
    @State private var engine = VoiceSubmitEngine.shared
    @State private var passthrough = PassthroughManager.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {

            // ── Header ──────────────────────────────────────────
            HStack {
                Text("SkippyVoice")
                    .font(.headline)
                Spacer()
                Toggle("", isOn: $engine.isEnabled)
                    .toggleStyle(.switch)
                    .labelsHidden()
            }
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 10)

            Divider()

            // ── VITURE passthrough ───────────────────────────────
            Button {
                passthrough.toggle()
            } label: {
                HStack {
                    Image(systemName: passthrough.isActive ? "eye.fill" : "eye")
                        .foregroundStyle(passthrough.isActive
                            ? Color(red: 0, green: 0.85, blue: 1) : .secondary)
                    Text(passthrough.isActive ? "Passthrough On" : "Passthrough Off")
                        .font(.subheadline)
                    Spacer()
                    if passthrough.glassesScreen == nil {
                        Text("No glasses")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
            .buttonStyle(.plain)
            .disabled(passthrough.glassesScreen == nil && !passthrough.isActive)

            Divider()

            // ── Status ──────────────────────────────────────────
            VStack(alignment: .leading, spacing: 6) {
                StatusRow(
                    label: "Listening",
                    value: engine.isListening ? "Active" : "Off",
                    color: engine.isListening ? .green : .secondary
                )
                StatusRow(
                    label: "Frontmost",
                    value: engine.watcher.frontmostAppName ?? "—",
                    color: engine.frontmostIsTarget ? .green : .secondary
                )
                StatusRow(
                    label: "Accessibility",
                    value: engine.hasAccessibilityPermission ? "Granted" : "Required",
                    color: engine.hasAccessibilityPermission ? .green : .red
                )
                StatusRow(
                    label: "Mode",
                    value: engine.mode == .holdForMore ? "Hold for more" : "Normal",
                    color: engine.mode == .holdForMore ? .orange : .secondary
                )
                if let cmd = engine.lastCommand {
                    StatusRow(label: "Last command", value: "\"\(cmd)\"", color: .secondary)
                }
                if let last = engine.lastSubmitAt {
                    StatusRow(
                        label: "Last submit",
                        value: last.formatted(date: .omitted, time: .shortened),
                        color: .secondary
                    )
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            // ── Level meter ─────────────────────────────────────
            if engine.isListening {
                Divider()
                LevelMeterView(rms: engine.detector.currentRMS,
                               threshold: engine.detector.silenceThreshold,
                               isSpeech: engine.detector.isSpeechDetected)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
            }

            Divider()

            // ── Mic selection ────────────────────────────────────
            VStack(alignment: .leading, spacing: 6) {
                Text("Microphone")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Picker("", selection: Binding(
                    get: { engine.micDevices.selectedDeviceID },
                    set: { id in
                        if let device = engine.micDevices.inputDevices.first(where: { $0.id == id }) {
                            engine.selectMic(device)
                        }
                    }
                )) {
                    ForEach(engine.micDevices.inputDevices) { device in
                        Text(device.name).tag(device.id)
                    }
                }
                .labelsHidden()
                .pickerStyle(.menu)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            Divider()

            // ── Settings ─────────────────────────────────────────
            VStack(alignment: .leading, spacing: 8) {
                Text("Silence threshold")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack {
                    Slider(value: $engine.silenceDuration, in: 0.5...5.0, step: 0.5)
                    Text(String(format: "%.1fs", engine.silenceDuration))
                        .font(.caption.monospacedDigit())
                        .frame(width: 36)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            Divider()

            // ── Accessibility prompt ─────────────────────────────
            if !engine.hasAccessibilityPermission {
                Button("Grant Accessibility Access…") {
                    KeystrokeInjector.requestPermission()
                }
                .buttonStyle(.borderedProminent)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

                Divider()
            }

            // ── Quit ─────────────────────────────────────────────
            Button("Quit SkippyVoice") {
                NSApplication.shared.terminate(nil)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .frame(width: 280)
    }
}

// MARK: - Subviews

struct StatusRow: View {
    let label: String
    let value: String
    let color: Color

    var body: some View {
        HStack {
            Text(label)
                .foregroundStyle(.secondary)
                .font(.caption)
            Spacer()
            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(color)
        }
    }
}

struct LevelMeterView: View {
    let rms: Float
    let threshold: Float
    let isSpeech: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Level")
                .font(.caption)
                .foregroundStyle(.secondary)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(.quaternary)
                    RoundedRectangle(cornerRadius: 3)
                        .fill(isSpeech ? Color.green : Color.secondary)
                        .frame(width: geo.size.width * CGFloat(min(rms * 20, 1.0)))
                    // Threshold marker
                    Rectangle()
                        .fill(.red.opacity(0.8))
                        .frame(width: 1.5)
                        .offset(x: geo.size.width * CGFloat(threshold * 20))
                }
            }
            .frame(height: 8)
        }
    }
}
