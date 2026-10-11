import Foundation
import LocalAuthentication
import Security

/// The recovery words, as ONE generic-password Keychain item:
///  - `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`: only on this device (never in iCloud Keychain
///    or an unencrypted backup), only while the device has a passcode — iOS deletes it if the passcode
///    is removed;
///  - access control `.userPresence`: every read needs Face ID / Touch ID or the device passcode.
/// The Keychain encrypts the item with keys held by the Secure Enclave; the words are never stored
/// anywhere else, and the app only reads them to show them or to sign.
enum Keychain {
    private static let service = "com.kilombino.kilowallet.recovery-words"
    private static let account = "wallet-0"

    private static func baseQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    static func save(_ secret: String) -> String? {
        var cfErr: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly, .userPresence, &cfErr) else {
            return "could not create the access control (\(cfErr?.takeRetainedValue().localizedDescription ?? "?"))"
        }
        var q = baseQuery()
        q[kSecValueData as String] = Data(secret.utf8)
        q[kSecAttrAccessControl as String] = access
        let status = SecItemAdd(q as CFDictionary, nil)
        if status == errSecSuccess { return nil }
        if status == errSecDuplicateItem { return "A wallet already exists. Back it up and remove it explicitly before creating another." }
        #if targetEnvironment(simulator)
        // The Simulator usually has no passcode, so a passcode-bound item cannot be created there.
        // Simulator builds only: keep it this-device-only without the passcode requirement.
        var sim = baseQuery()
        sim[kSecValueData as String] = Data(secret.utf8)
        sim[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        if SecItemAdd(sim as CFDictionary, nil) == errSecSuccess { return nil }
        #endif
        if status == errSecNotAvailable || status == errSecAuthFailed || status == -25293 {
            return "Set a device passcode (Settings → Face ID & Passcode) to keep a wallet's words on this iPhone."
        }
        return "Keychain error \(status)"
    }

    /// Prompts for Face ID / Touch ID / passcode, off the main thread; calls back on the main thread.
    static func load(reason: String, completion: @escaping (String?, String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let ctx = LAContext()
            ctx.localizedReason = reason
            var q = baseQuery()
            q[kSecReturnData as String] = true
            q[kSecMatchLimit as String] = kSecMatchLimitOne
            q[kSecUseAuthenticationContext as String] = ctx
            var out: CFTypeRef?
            let status = SecItemCopyMatching(q as CFDictionary, &out)
            let result: (String?, String?)
            switch status {
            case errSecSuccess:
                if let d = out as? Data, let s = String(data: d, encoding: .utf8) { result = (s, nil) }
                else { result = (nil, "The stored words could not be read.") }
            case errSecUserCanceled: result = (nil, "Cancelled: nothing was unlocked.")
            case errSecAuthFailed: result = (nil, "Not recognised: nothing was unlocked.")
            case errSecItemNotFound: result = (nil, "No recovery words on this device.")
            default: result = (nil, "Keychain error \(status)")
            }
            DispatchQueue.main.async { completion(result.0, result.1) }
        }
    }

    /// Whether the item exists, without prompting.
    static func has() -> Bool {
        let ctx = LAContext()
        ctx.interactionNotAllowed = true
        var q = baseQuery()
        q[kSecUseAuthenticationContext as String] = ctx
        let status = SecItemCopyMatching(q as CFDictionary, nil)
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    static func delete() -> String? {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound ? nil : "Keychain error \(status)"
    }
}
