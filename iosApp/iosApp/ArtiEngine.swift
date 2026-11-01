import Foundation
import Network
import KilombinoTor

/// Tor inside the app: Arti, the Tor Project's Tor in Rust, linked in as KilombinoTor.xcframework
/// (tor-engine/ENGINE: the same tag and features as Kilowallet Android's libarti.so). Android
/// runs Arti's program; iOS lets no app start one, so the same proxy runs on its own thread in
/// the app (tor-engine/kilombino-tor). The shared Kotlin code (core/.../tor/Tor.kt) decides when
/// it runs; this file only passes the calls across. (Not "TorEngine": that is the Kotlin name.)
enum ArtiEngine {
    /// Nil once the SOCKS port listens, else why Tor could not start. Blocking: never on the main thread.
    static func start(dir: String, logPath: String, port: Int32) -> String? {
        let out: UnsafeMutablePointer<CChar>? = dir.withCString { d in
            logPath.withCString { l in kilombino_tor_start(d, l, UInt16(truncatingIfNeeded: port)) }
        }
        guard let out = out else { return nil }
        defer { kilombino_tor_free(out) }
        return String(cString: out)
    }

    static func stop() { kilombino_tor_stop() }

    /// 0: not running. 1: connecting. 2: the SOCKS port carries traffic.
    static func state() -> Int32 { kilombino_tor_state() }

    /// Application Support/arti (cache and state), kept out of iCloud and device backups: it only
    /// holds what Arti learns again by itself.
    static func dir() -> String {
        let fm = FileManager.default
        var url = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("arti", isDirectory: true)
        try? fm.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url.path
    }

    /// Tor's own log, next to its folder (Application Support/tor.log, out of backups like it).
    static func logPath() -> String {
        let fm = FileManager.default
        var url = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("tor.log")
        if !fm.fileExists(atPath: url.path) { fm.createFile(atPath: url.path, contents: nil) }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url.path
    }

    /// A port free on 127.0.0.1 right now (the system picks it).
    static func freePort() -> Int32 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return 0 }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let ok = withUnsafeMutablePointer(to: &addr) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { sp -> Bool in
                bind(fd, sp, len) == 0 && getsockname(fd, sp, &len) == 0
            }
        }
        return ok ? Int32(UInt16(bigEndian: addr.sin_port)) : 0
    }

    /// True when 127.0.0.1:port answers a SOCKS5 greeting (no authentication) as Arti does: after
    /// the app was suspended, iOS may have taken the listening socket back.
    static func socksAnswers(port: Int32) -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: 3, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(truncatingIfNeeded: port).bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let connected = withUnsafePointer(to: &addr) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0 }
        }
        guard connected else { return false }
        let hello: [UInt8] = [5, 1, 0]
        guard send(fd, hello, hello.count, 0) == hello.count else { return false }
        var reply = [UInt8](repeating: 0, count: 2)
        guard recv(fd, &reply, 2, MSG_WAITALL) == 2 else { return false }
        return reply[0] == 5 && reply[1] == 0
    }
}

/// Connections through Tor's SOCKS port. A SOCKS username gives a circuit of its own (Arti, like
/// Tor's IsolateSOCKSAuth, never shares one between different usernames): each pool seat and each
/// output handed in leaves Tor from its own exit, as on Android.
enum TorProxy {
    @available(iOS 17.0, *)
    static func configuration(port: Int32, isolation: String?) -> ProxyConfiguration {
        var p = ProxyConfiguration(socksv5Proxy: NWEndpoint.hostPort(host: "127.0.0.1",
                                                                     port: NWEndpoint.Port(rawValue: UInt16(truncatingIfNeeded: port))!))
        if let iso = isolation { p.applyCredential(username: iso, password: "kilowallet") }
        // Only Tor: no direct connection if the proxy fails.
        p.allowFailover = false
        return p
    }

    /// A URLSession configuration that goes through Tor (iOS 17: the system's SOCKS5 proxy
    /// support; iOS 16: the CFNetwork SOCKS keys).
    static func apply(to c: URLSessionConfiguration, port: Int32, isolation: String?) {
        guard port > 0 else { return }
        if #available(iOS 17.0, *) {
            c.proxyConfigurations = [configuration(port: port, isolation: isolation)]
        } else {
            var d: [AnyHashable: Any] = ["SOCKSEnable": 1, "SOCKSProxy": "127.0.0.1", "SOCKSPort": Int(port)]
            if let iso = isolation { d["SOCKSUser"] = iso; d["SOCKSPassword"] = "kilowallet" }
            c.connectionProxyDictionary = d
        }
    }

    /// The same for a Network.framework connection (Electrum): iOS 17 or later.
    static func apply(to params: NWParameters, port: Int32) -> Bool {
        guard port > 0 else { return true }
        if #available(iOS 17.0, *) {
            let ctx = NWParameters.PrivacyContext(description: "Kilowallet Tor")
            ctx.proxyConfigurations = [configuration(port: port, isolation: nil)]
            params.setPrivacyContext(ctx)
            return true
        }
        return false
    }
}
