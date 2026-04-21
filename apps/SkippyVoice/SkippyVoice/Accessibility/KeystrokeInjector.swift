import CoreGraphics
import AppKit

/// Fires keypresses and clipboard pastes to the frontmost application via CGEvent.
/// Requires Accessibility permission: System Settings → Privacy & Security → Accessibility.
enum KeystrokeInjector {

    private static let returnKeyCode: CGKeyCode = 0x24   // Return
    private static let vKeyCode: CGKeyCode = 0x09        // V (for Cmd+V paste)
    private static let aKeyCode: CGKeyCode = 0x00        // A (for Cmd+A select all)
    private static let deleteKeyCode: CGKeyCode = 0x33   // Backspace/Delete

    static var hasPermission: Bool { AXIsProcessTrusted() }

    static func requestPermission() {
        let options = [kAXTrustedCheckOptionPrompt.takeRetainedValue() as String: true]
        AXIsProcessTrustedWithOptions(options as CFDictionary)
    }

    /// Posts Return — submits the prompt.
    static func pressReturn() {
        guard hasPermission else { requestPermission(); return }
        post(virtualKey: returnKeyCode, flags: [])
    }

    /// Posts Shift+Return — inserts a newline without submitting.
    static func pressShiftReturn() {
        guard hasPermission else { requestPermission(); return }
        post(virtualKey: returnKeyCode, flags: .maskShift)
    }

    /// Pastes text into the frontmost app via the system clipboard, then optionally submits.
    /// Saves and restores the previous clipboard contents.
    static func pasteText(_ text: String, thenSubmit: Bool = true) {
        guard hasPermission else { requestPermission(); return }
        guard !text.isEmpty else { return }

        let pb = NSPasteboard.general
        let previousContents = pb.string(forType: .string)

        pb.clearContents()
        pb.setString(text, forType: .string)

        post(virtualKey: vKeyCode, flags: .maskCommand)

        if thenSubmit {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.06) {
                pressReturn()
                restoreClipboard(previousContents)
            }
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.06) {
                restoreClipboard(previousContents)
            }
        }
    }

    /// Selects all text in the focused field and deletes it.
    static func clearFocusedField() {
        guard hasPermission else { requestPermission(); return }
        post(virtualKey: aKeyCode, flags: .maskCommand)   // Cmd+A
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.04) {
            post(virtualKey: deleteKeyCode, flags: [])    // Delete
        }
    }

    // MARK: - Private

    private static func post(virtualKey: CGKeyCode, flags: CGEventFlags) {
        let src = CGEventSource(stateID: .hidSystemState)
        let down = CGEvent(keyboardEventSource: src, virtualKey: virtualKey, keyDown: true)
        let up   = CGEvent(keyboardEventSource: src, virtualKey: virtualKey, keyDown: false)
        down?.flags = flags
        up?.flags   = flags
        down?.post(tap: .cghidEventTap)
        up?.post(tap: .cghidEventTap)
    }

    private static func restoreClipboard(_ previous: String?) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            let pb = NSPasteboard.general
            pb.clearContents()
            if let prev = previous { pb.setString(prev, forType: .string) }
        }
    }
}
