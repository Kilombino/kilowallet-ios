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
            BalanceWatch.schedule()
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

    func openConnectionVia(host: String, port: Int32, tls: Bool, pinnedFingerprint: String?, socksPort: Int32) -> IosConnection {
        ElectrumConnection(host: host, port: port, tls: tls, pinnedFingerprint: pinnedFingerprint, socksPort: socksPort)
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

    func scanQrParts(listener: IosQrPartListener) {
        DispatchQueue.main.async {
            guard let top = Self.topViewController() else { listener.onEnd(); return }
            QrScannerViewController.presentParts(from: top, onPart: { listener.onPart(text: $0) }, onEnd: { listener.onEnd() })
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

    // MARK: Backup file (FileBridge.swift)

    func saveFile(name: String, data: Data, completion: @escaping (String?) -> Void) {
        FileBridge.save(name: name, data: data, completion: completion)
    }

    func openFile(completion: @escaping (Data?) -> Void) {
        FileBridge.open(completion: completion)
    }

    // MARK: Lightning (LND linked in: LndmobileEngine.swift)

    func lndAvailable() -> Bool { true }

    func lndStart(args: String, listener: IosLndListener) { LndmobileEngine.start(args, listener) }

    func lndCall(method: String, request: Data?, listener: IosLndListener) { LndmobileEngine.call(method, request, listener) }

    func lndStream(method: String, request: Data?, listener: IosLndListener) { LndmobileEngine.stream(method, request, listener) }

    /// Application Support/lnd, kept out of iCloud and device backups (a restored copy of a node's
    /// channel state would be dangerous: the channel backup and the words are the way back).
    func lndDir() -> String {
        let fm = FileManager.default
        var url = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("lnd", isDirectory: true)
        try? fm.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url.path
    }

    func lndPasswordLoad() -> String? { LndPassword.load() }

    func lndPasswordSave(hex: String) -> String? { LndPassword.save(hex) }

    func lndPasswordDelete() { LndPassword.delete() }

    /// One HTTPS request (the LSP, the explorer), blocking the calling (background) thread: no
    /// cookies, no cache, a 30 s timeout. Status 0 with the error when there was no answer.
    func httpRequest(method: String, url: String, body: String?) -> IosHttpResult {
        httpRequestVia(method: method, url: url, body: body, socksPort: 0)
    }

    /// The same through the app's Tor when [socksPort] > 0 (Android: the LSP and the explorer go
    /// through Tor when it is already running).
    func httpRequestVia(method: String, url: String, body: String?, socksPort: Int32) -> IosHttpResult {
        guard let u = URL(string: url), u.scheme == "https" else { return HttpResult(0, "not an https URL") }
        var req = URLRequest(url: u, timeoutInterval: 30)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("Kilowallet", forHTTPHeaderField: "User-Agent")
        if let body = body {
            req.httpBody = Data(body.utf8)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let done = DispatchSemaphore(value: 0)
        var result = HttpResult(0, "no answer")
        let session = socksPort > 0 ? Self.torSession(socksPort) : Self.session
        session.dataTask(with: req) { data, response, error in
            if let http = response as? HTTPURLResponse {
                result = HttpResult(Int32(http.statusCode), data.flatMap { String(data: $0, encoding: .utf8) } ?? "")
            } else {
                result = HttpResult(0, error?.localizedDescription ?? "no answer")
            }
            done.signal()
        }.resume()
        done.wait()
        return result
    }

    /// One ephemeral session per Tor port (the port stays the same for the app's run).
    private static var torSessions: [Int32: URLSession] = [:]
    private static let torLock = NSLock()
    private static func torSession(_ port: Int32) -> URLSession {
        torLock.lock(); defer { torLock.unlock() }
        if let s = torSessions[port] { return s }
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 60
        c.timeoutIntervalForResource = 90
        c.httpCookieStorage = nil
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        TorProxy.apply(to: c, port: port, isolation: nil)
        let s = URLSession(configuration: c)
        torSessions[port] = s
        return s
    }

    private final class HttpResult: NSObject, IosHttpResult {
        private let code: Int32; private let text: String
        init(_ code: Int32, _ text: String) { self.code = code; self.text = text }
        func status() -> Int32 { code }
        func body() -> String { text }
    }

    /// While a Lightning payment is on its way, leaving the app keeps the node going for the little
    /// time iOS allows (about 30 s); the payment is followed again when the app comes back.
    func beginBackgroundWork(name: String) -> Int64 {
        var id: UIBackgroundTaskIdentifier = .invalid
        id = UIApplication.shared.beginBackgroundTask(withName: name) {
            UIApplication.shared.endBackgroundTask(id)
        }
        return id == .invalid ? 0 : Int64(id.rawValue)
    }

    func endBackgroundWork(id: Int64) {
        if id != 0 { UIApplication.shared.endBackgroundTask(UIBackgroundTaskIdentifier(rawValue: Int(id))) }
    }

    // MARK: Pools (Kilojoin): the nostr relay

    // MARK: Ark (ArkEngine.swift: KilombinoArk.xcframework)

    func arkAvailable() -> Bool { true }

    func arkStart(datadir: String, port: Int32, mnemonic: String?, passphrase: String) -> String {
        ArkEngine.start(datadir: datadir, port: port, mnemonic: mnemonic, passphrase: passphrase)
    }

    func arkStop() { ArkEngine.stop() }

    func arkDir() -> String {
        let fm = FileManager.default
        var url = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("ark", isDirectory: true)
        try? fm.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url.path
    }

    func arkWordsLoad() -> String? { ArkWords.load() }
    func arkWordsSave(text: String) -> String? { ArkWords.save(text) }
    func arkWordsDelete() { ArkWords.delete() }

    /// The engine on 127.0.0.1 (plain http, nothing else), or the chain source over https.
    func arkHttp(method: String, url: String, body: String?, bearer: String?, timeoutMs: Int32) -> IosHttpResult {
        guard let u = URL(string: url), u.scheme == "https" || (u.scheme == "http" && u.host == "127.0.0.1") else {
            return HttpResult(0, "not an allowed URL")
        }
        var req = URLRequest(url: u, timeoutInterval: max(5, Double(timeoutMs) / 1000))
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("Kilowallet", forHTTPHeaderField: "User-Agent")
        if let t = bearer { req.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }
        if let body = body {
            req.httpBody = Data(body.utf8)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let done = DispatchSemaphore(value: 0)
        var result = HttpResult(0, "no answer")
        Self.arkSession.dataTask(with: req) { data, response, error in
            if let http = response as? HTTPURLResponse {
                result = HttpResult(Int32(http.statusCode), data.flatMap { String(data: $0, encoding: .utf8) } ?? "")
            } else {
                result = HttpResult(0, error?.localizedDescription ?? "no answer")
            }
            done.signal()
        }.resume()
        done.wait()
        return result
    }

    /// Long calls (a renewal waits for a round): no cache, no cookies, up to 5 minutes.
    private static let arkSession: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForResource = 330
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    func arkRemind(atEpochSeconds: Int64, title: String, body: String) {
        ArkReminder.set(at: atEpochSeconds, title: title, body: body)
    }

    func arkScheduleBackground() { ArkReminder.scheduleBackground() }

    func openWebSocket(url: String, listener: IosSocketListener) -> IosSocket {
        RelaySocket(url: url, listener: listener)
    }

    func openWebSocketVia(url: String, listener: IosSocketListener, socksPort: Int32, isolation: String?) -> IosSocket {
        RelaySocket(url: url, listener: listener, socksPort: socksPort, isolation: isolation)
    }

    // MARK: Balance alerts with the app closed (BalanceWatch.swift)

    func watchNotify(title: String, body: String) { BalanceWatch.notify(title: title, body: body) }
    func watchSchedule(on: Bool) { BalanceWatch.setOn(on) }

    // MARK: Tor (ArtiEngine.swift: KilombinoTor.xcframework)

    func torAvailable() -> Bool { true }
    func torStart(dir: String, logPath: String, port: Int32) -> String? { ArtiEngine.start(dir: dir, logPath: logPath, port: port) }
    func torStop() { ArtiEngine.stop() }
    func torState() -> Int32 { ArtiEngine.state() }
    func torDir() -> String { ArtiEngine.dir() }
    func torLogPath() -> String { ArtiEngine.logPath() }
    func torFreePort() -> Int32 { ArtiEngine.freePort() }
    func torSocksAnswers(port: Int32) -> Bool { ArtiEngine.socksAnswers(port: port) }

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

    /// [socksPort] > 0: through the app's Tor, on the circuit named [isolation] (TorProxy).
    init(url: String, listener: IosSocketListener, socksPort: Int32 = 0, isolation: String? = nil) {
        self.listener = listener
        super.init()
        guard let u = URL(string: url), u.scheme == "wss" else { openError = "only wss:// relays"; return }
        let c = URLSessionConfiguration.ephemeral
        c.httpCookieStorage = nil
        c.urlCache = nil
        c.timeoutIntervalForRequest = socksPort > 0 ? 60 : 30
        TorProxy.apply(to: c, port: socksPort, isolation: isolation)
        session = URLSession(configuration: c, delegate: self, delegateQueue: nil)
        var req = URLRequest(url: u)
        req.setValue("Kilowallet", forHTTPHeaderField: "User-Agent")
        let t = session.webSocketTask(with: req)
        t.maximumMessageSize = 8_000_000
        task = t
        t.resume()
        if opened.wait(timeout: .now() + (socksPort > 0 ? 60 : 20)) == .timedOut {
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
