import Foundation

/// Apps that SkippyVoice will auto-submit for.
/// Add bundle IDs here to expand coverage.
struct TargetApps {
    static let defaults: [TargetApp] = [
        TargetApp(name: "Claude Desktop", bundleID: "com.anthropic.claudefordesktop"),
        TargetApp(name: "Cursor",         bundleID: "com.todesktop.230313mzl4w4u92"),
    ]
}

struct TargetApp: Identifiable, Codable, Equatable {
    var id: String { bundleID }
    let name: String
    let bundleID: String
}
