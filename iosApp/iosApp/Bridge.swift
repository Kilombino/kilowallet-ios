import Foundation
import UIKit
import LocalAuthentication
import AVFoundation
import KilowalletApp

/// The platform services the shared Kotlin code asks for (see app/src/iosMain/.../IosBridge.kt).
final class Bridge: NSObject, IosBridge {
    static let shared = Bridge()

    // MARK: Keychain

    func keychainSave(walletId: String, secret: String) -> String? { Keychain.save(walletId: walletId, secret: secret) }

    func keychainLoad(walletId: String, reason: String, completion: @escaping (String?, String?) -> Void) {
        Keychain.load(walletId: walletId, reason: reason, completion: completion)
    }

    func keychainStatus(walletId: String) -> String { Keychain.status(walletId: walletId) }

    func keychainDelete(walletId: String) -> String? { Keychain.delete(walletId: walletId) }

    func unlockName() -> String {
        let ctx = LAContext()
        var err: NSError?
        if ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err) {
            switch ctx.biometryType {
            case .faceID: return "Face ID"
            case .touchID: return "Touch ID"
            default: break
            }
        }
        return "passcode"
    }

    // MARK: Electrum

    func openConnection(host: String, port: Int32, tls: Bool, pinnedFingerprint: String?) -> IosConnection {
        ElectrumConnection(host: host, port: port, tls: tls, pinnedFingerprint: pinnedFingerprint)
    }

    // MARK: QR

    func qrPng(text: String) -> Data? { QrCode.png(text) }

    func canScanQr() -> Bool { AVCaptureDevice.default(for: .video) != nil }

    func scanQr(completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard let top = Self.topViewController() else { completion(nil); return }
            QrScannerViewController.present(from: top, completion: completion)
        }
    }

    // MARK: Misc

    func appVersion() -> String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(v) (\(b))"
    }

    func markReady(name: String) {
        #if DEBUG
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? "ready".write(to: dir.appendingPathComponent("kw-ready-\(name)"), atomically: true, encoding: .utf8)
        #endif
    }

    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
