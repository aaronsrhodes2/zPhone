import SwiftUI

/// Shown inside the HUD preview sheet on iPad when glasses aren't connected.
struct GlassesNotConnectedView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "sunglasses")
                .font(.system(size: 64))
                .foregroundStyle(.white.opacity(0.4))
            Text("Connect VITURE via USB-C\nto see the live display.")
                .font(.body)
                .foregroundStyle(.white.opacity(0.6))
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }
}

#Preview {
    GlassesNotConnectedView()
}
