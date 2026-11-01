import Foundation
import BackgroundTasks
import UserNotifications
import KilombinoArk
import KilowalletApp

/// The Ark engine: the Paperclip/bark wallet daemon and its REST API linked into the app
/// (KilombinoArk.xcframework, from the same sources as Kilowallet Android's libkilombino_ark.so; see
/// ark-engine/ENGINE). The shared Kotlin code (core/.../ark/Ark.kt) talks to it over HTTP on
/// 127.0.0.1 with the token `start` returns; this file only passes the strings across.
enum ArkEngine {
    /// The bearer token, or "ERR:<message>". Blocking: never on the main thread.
    static func start(datadir: String, port: Int32, mnemonic: String?, passphrase: String) -> String {
        let out: UnsafeMutablePointer<CChar>? = datadir.withCString { d in
            passphrase.withCString { p in
                if let m = mnemonic {
                    return m.withCString { kilombino_ark_start(d, UInt16(truncatingIfNeeded: port), $0, p) }
                }
                return kilombino_ark_start(d, UInt16(truncatingIfNeeded: port), nil, p)
            }
        }
        guard let out = out else { return "ERR:the engine returned nothing" }
        defer { kilombino_ark_free(out) }
        return String(cString: out)
    }

    static func stop() { kilombino_ark_stop() }
}

/// Ark's words (and passphrase after a newline), as their own generic-password item: readable after
/// the device's first unlock and only on this device, with no Face ID, so the engine can start and
/// renew coins when the app opens. They are Ark's own words, made from the wallet's: they open Ark
/// only, never the on-chain coins (Android keeps them under a Keystore key with no fingerprint).
enum ArkWords {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.kilombino.kilowallet.ark-words",
        kSecAttrAccount as String: "ark-0",
    ]

    static func load() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func save(_ text: String) -> String? {
        delete()
        var q = query
        q[kSecValueData as String] = Data(text.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        return status == errSecSuccess ? nil : "Keychain error \(status)"
    }

    static func delete() { SecItemDelete(query as CFDictionary) }
}

/// The reminder before an Ark coin expires (a local notification) and iOS's background run to renew
/// (BGProcessingTask): iOS does not let the wallet stay up to renew coins by itself.
enum ArkReminder {
    static let taskId = "com.kilombino.kilowallet.ark-renew"
    private static let notificationId = "ark-expiry"

    static func set(at epochSeconds: Int64, title: String, body: String) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [notificationId])
        guard epochSeconds > 0 else { return }
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            let wait = max(60, TimeInterval(epochSeconds) - Date().timeIntervalSince1970)
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: wait, repeats: false)
            center.add(UNNotificationRequest(identifier: notificationId, content: content, trigger: trigger))
        }
    }

    /// Registered once at launch (iOSApp.init): the run starts the engine and renews coins close to expiry.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            guard let task = task as? BGProcessingTask else { return }
            scheduleBackground()
            var finished = false
            task.expirationHandler = { if !finished { finished = true; task.setTaskCompleted(success: false) } }
            ArkBackgroundKt.arkRenewInBackground(bridge: Bridge.shared, waitS: 150) { ok in
                DispatchQueue.main.async {
                    if !finished { finished = true; task.setTaskCompleted(success: ok.boolValue) }
                }
            }
        }
    }

    /// Asks for the next run, about a day from now, with the network (iOS picks the time).
    static func scheduleBackground() {
        let req = BGProcessingTaskRequest(identifier: taskId)
        req.requiresNetworkConnectivity = true
        req.requiresExternalPower = false
        req.earliestBeginDate = Date(timeIntervalSinceNow: 20 * 3600)
        try? BGTaskScheduler.shared.submit(req)
    }
}
