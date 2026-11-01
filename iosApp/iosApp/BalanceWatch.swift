import Foundation
import BackgroundTasks
import UserNotifications
import KilowalletApp

/// Balance alerts with the app closed (Android's WatchService, as far as iOS allows): a
/// BGAppRefreshTask that iOS runs when it decides (no fixed interval, about 30 s each) asks the
/// shared Kotlin code (BalanceWatch.kt) what changed and shows each change as a local notification.
enum BalanceWatch {
    static let taskId = "com.kilombino.kilowallet.watch"

    /// Registered once at launch (iOSApp.init).
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            schedule()
            var finished = false
            task.expirationHandler = { if !finished { finished = true; task.setTaskCompleted(success: false) } }
            WatchBackgroundKt.balanceWatchInBackground(bridge: Bridge.shared) { ok in
                DispatchQueue.main.async {
                    if !finished { finished = true; task.setTaskCompleted(success: ok.boolValue) }
                }
            }
        }
    }

    static var enabled: Bool { UserDefaults.standard.string(forKey: "kw.watch.notify") == "1" }

    /// The next run, at the earliest in 15 minutes (iOS picks the time).
    static func schedule() {
        guard enabled else { return }
        let req = BGAppRefreshTaskRequest(identifier: taskId)
        req.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(req)
    }

    static func setOn(_ on: Bool) {
        if on {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
            schedule()
        } else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskId)
        }
    }

    static func notify(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}
