import Foundation
import UIKit
import LocalAuthentication
import AVFoundation
import KilowalletApp

/// The platform services the shared Kotlin code asks for (see app/src/iosMain/.../IosBridge.kt).
final class Bridge: NSObject, IosBridge {
    static let shared = Bridge()

    /// While a pool round runs, leaving the app keeps it going for the little time iOS allows
    /// (a background task); there is no long-running background mode. Back on screen, it goes on.
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    private override init() {
        super.init()
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self = self, self.backgroundTask == .invalid else { return }
            self.backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Kilojoin rounds") { [weak self] in
                self?.endBackgroundTask()
            }
        }
        nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.endBackgroundTask()
        }
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid { UIApplication.shared.endBackgroundTask(backgroundTask); backgroundTask = .invalid }
    }

    // MARK: Keychain

    func keychainSave(secret: String) -> String? { Keychain.save(secret) }

    func keychainLoad(reason: String, completion: @escaping (String?, String?) -> Void) {
        Keychain.load(reason: reason, completion: completion)
    }

    func keychainHas() -> Bool { Keychain.has() }

    func keychainDelete() { Keychain.delete() }

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

    // MARK: Price feed

    /// One HTTPS GET (the price feed): no cookies, no cache, a 10 s timeout; the body of a 200 answer or nil.
    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 10
        c.timeoutIntervalForResource = 15
        c.httpCookieStorage = nil
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    func httpGet(url: String, completion: @escaping (String?) -> Void) {
        guard let u = URL(string: url), u.scheme == "https" else { completion(nil); return }
        var req = URLRequest(url: u)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        Self.session.dataTask(with: req) { data, response, _ in
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  let data = data, let body = String(data: data, encoding: .utf8) else { completion(nil); return }
            completion(body)
        }.resume()
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

    // MARK: Pools (Kilojoin): the nostr relay

    func openWebSocket(url: String, listener: IosSocketListener) -> IosSocket {
        RelaySocket(url: url, listener: listener)
    }

    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

/// One WebSocket to the pools' nostr relay (relay.kilombino.com), for the shared Kotlin code: an
/// ephemeral URLSession (no cookies, no cache), wss only, the system's certificate checks. The
/// initializer blocks until the socket is open or failed, so it is never called on the main thread.
final class RelaySocket: NSObject, IosSocket, URLSessionWebSocketDelegate {
    private let listener: IosSocketListener
    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    private let opened = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var didOpen = false
    private var finished = false
    private var openError: String?

    init(url: String, listener: IosSocketListener) {
        self.listener = listener
        super.init()
        guard let u = URL(string: url), u.scheme == "wss" else { openError = "only wss:// relays"; return }
        let c = URLSessionConfiguration.ephemeral
        c.httpCookieStorage = nil
        c.urlCache = nil
        c.timeoutIntervalForRequest = 30
        session = URLSession(configuration: c, delegate: self, delegateQueue: nil)
        var req = URLRequest(url: u)
        req.setValue("Kilowallet", forHTTPHeaderField: "User-Agent")
        let t = session.webSocketTask(with: req)
        t.maximumMessageSize = 8_000_000
        task = t
        t.resume()
        if opened.wait(timeout: .now() + 20) == .timedOut {
            lock.lock(); if openError == nil && !didOpen { openError = "the relay did not answer" }; lock.unlock()
        }
        if openError != nil { t.cancel(); session.invalidateAndCancel(); return }
        receive()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        lock.lock(); didOpen = true; lock.unlock()
        opened.signal()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        finish(nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let wasOpen = didOpen
        if !wasOpen { openError = error?.localizedDescription ?? "the connection closed" }
        lock.unlock()
        if wasOpen { finish(error?.localizedDescription) } else { opened.signal() }
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let text): self.listener.onText(text: text)
                case .data(let data): if let text = String(data: data, encoding: .utf8) { self.listener.onText(text: text) }
                @unknown default: break
                }
                self.receive()
            case .failure(let error):
                self.finish(error.localizedDescription)
            }
        }
    }

    private func finish(_ error: String?) {
        lock.lock()
        let first = !finished
        finished = true
        lock.unlock()
        if first { listener.onClosed(error: error) }
    }

    func error() -> String? { openError }

    func send(text: String) -> Bool {
        guard let t = task, !finished else { return false }
        let done = DispatchSemaphore(value: 0)
        var ok = false
        t.send(.string(text)) { error in ok = (error == nil); done.signal() }
        if done.wait(timeout: .now() + 15) == .timedOut { return false }
        return ok
    }

    func ping() -> Bool {
        guard let t = task, !finished else { return false }
        t.sendPing { [weak self] error in if error == nil { self?.listener.onPong() } }
        return true
    }

    func close() {
        lock.lock(); finished = true; lock.unlock()
        task?.cancel(with: .normalClosure, reason: nil)
        session?.invalidateAndCancel()
    }
}
