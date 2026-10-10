import SwiftUI
import UIKit
import KilowalletApp

/// Hosts the Compose UI (shared Kotlin, `app/src/commonMain`) and hands it the Swift services.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(bridge: Bridge.shared, demo: Self.demoScreen())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    /// CI screenshot mode (`-kwDemo <screen>` launch argument), Debug builds only.
    static func demoScreen() -> String? {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-kwDemo"), i + 1 < args.count { return args[i + 1] }
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
