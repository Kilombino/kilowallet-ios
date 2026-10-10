import Foundation
import Network
import CryptoKit
import Security
import KilowalletApp

/// A newline-delimited TLS (or, for a local node, plain TCP) stream to an Electrum server, over
/// Network.framework. Electrum servers use self-signed certificates, so instead of CA validation
/// the leaf certificate's SHA-256 is pinned on first use (the Kotlin side stores the pin): a
/// different certificate later makes the handshake fail before a single byte is sent.
/// All calls block the calling (background) thread; Network.framework runs on its own queue.
final class ElectrumConnection: NSObject, IosConnection {
    private let conn: NWConnection
    private let queue = DispatchQueue(label: "com.kilombino.kilowallet.electrum")
    private var buffer = Data()
    private var fp: String?
    private var changed = false
    private var err: String?
    private var closed = false

    init(host: String, port: Int32, tls: Bool, pinnedFingerprint: String?) {
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = 20
        tcp.noDelay = true
        var tlsOptions: NWProtocolTLS.Options? = nil
        if tls {
            let t = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(t.securityProtocolOptions, .TLSv12)
            tlsOptions = t
        }
        let params = NWParameters(tls: tlsOptions, tcp: tcp)
        conn = NWConnection(host: NWEndpoint.Host(host),
                            port: NWEndpoint.Port(rawValue: UInt16(clamping: port)) ?? 50002,
                            using: params)
        super.init()

        if let t = tlsOptions {
            let pin = pinnedFingerprint?.lowercased()
            sec_protocol_options_set_verify_block(t.securityProtocolOptions, { [weak self] _, trust, complete in
                let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
                var hex: String? = nil
                if let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate], let leaf = chain.first {
                    let der = SecCertificateCopyData(leaf) as Data
                    hex = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
                }
                self?.fp = hex
                if let pin = pin, pin != hex {
                    self?.changed = true
                    complete(false)
                } else {
                    complete(hex != nil)
                }
            }, queue)
        }

        let ready = DispatchSemaphore(value: 0)
        var signalled = false
        func signalOnce() { if !signalled { signalled = true; ready.signal() } }
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: signalOnce()
            case .failed(let e):
                self?.err = self?.changed == true ? "certificate changed" : "Could not connect: \(e.localizedDescription)"
                self?.closed = true
                signalOnce()
            case .waiting(let e):
                // No route, refused, DNS failure: report it instead of waiting for the network forever.
                self?.err = "Could not connect: \(e.localizedDescription)"
                self?.closed = true
                self?.conn.cancel()
                signalOnce()
            case .cancelled:
                self?.closed = true
                signalOnce()
            default: break
            }
        }
        conn.start(queue: queue)
        // A cold TLS handshake with some Electrum servers takes tens of seconds: be generous.
        if ready.wait(timeout: .now() + 45) == .timedOut {
            err = "Timed out connecting to \(host):\(port)"
            conn.cancel()
        }
    }

    func error() -> String? { changed ? "certificate changed" : err }
    func certificateChanged() -> Bool { changed }
    func fingerprint() -> String? { fp }

    func writeLine(line: String) -> String? {
        if closed { return "connection closed" }
        let done = DispatchSemaphore(value: 0)
        var sendErr: String?
        conn.send(content: Data((line + "\n").utf8), completion: .contentProcessed { e in
            if let e = e { sendErr = e.localizedDescription }
            done.signal()
        })
        if done.wait(timeout: .now() + 60) == .timedOut { return "timed out sending" }
        return sendErr
    }

    func readLine() -> String? {
        while true {
            if let nl = buffer.firstIndex(of: 0x0A) {
                let lineData = buffer[buffer.startIndex..<nl]
                buffer.removeSubrange(buffer.startIndex...nl)
                return String(decoding: lineData, as: UTF8.self)
            }
            if closed { return nil }
            let got = DispatchSemaphore(value: 0)
            var chunk: Data?
            var finished = false
            conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, isComplete, e in
                chunk = data
                finished = isComplete || e != nil
                got.signal()
            }
            if got.wait(timeout: .now() + 90) == .timedOut { closed = true; conn.cancel(); return nil }
            if let c = chunk { buffer.append(c) }
            if finished { closed = true }
            if finished && buffer.firstIndex(of: 0x0A) == nil { return nil }
        }
    }

    func close() {
        closed = true
        conn.cancel()
    }
}
