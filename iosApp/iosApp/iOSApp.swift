import SwiftUI

@main
struct iOSApp: App {
    init() {
        // Must be registered before launch ends: iOS's background run to renew Ark coins.
        ArkReminder.register()
        BalanceWatch.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}
