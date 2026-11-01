import SwiftUI
import UIKit
import KilowalletApp

/// Hosts the Compose UI (shared Kotlin, `app/src/commonMain`) and hands it the Swift services.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(bridge: Bridge.shared, demo: Self.demoScreen())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    /// CI screenshot mode (the `KW_DEMO` environment variable), Debug builds only. Not a launch
    /// argument: LND (linked in) parses the process's arguments as its own flags and stops on any
    /// it doesn't know.
    static func demoScreen() -> String? {
        #if DEBUG
        if let s = ProcessInfo.processInfo.environment["KW_DEMO"], !s.isEmpty { return s }
        #endif
        return nil
    }
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all) // Compose handles insets (safe area, keyboard) itself
            .background(Color.black)
    }
}
