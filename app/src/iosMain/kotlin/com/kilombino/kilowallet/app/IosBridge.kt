package com.kilombino.kilowallet.app

import platform.Foundation.NSData

/**
 * What the Swift side of the app provides (iosApp/iosApp/Bridge.swift). Kept small and in plain
 * types so each piece is ordinary, reviewable Swift: Keychain + LocalAuthentication, a
 * Network.framework TLS socket, CoreImage QR codes and an AVFoundation QR scanner.
 */
interface IosBridge {
    /** Store the secret in the Keychain behind user presence; returns an error message or null. */
    fun keychainSave(secret: String): String?
    /** Face ID / Touch ID / passcode, then the secret (or null and an error message). Called back on the main thread. */
    fun keychainLoad(reason: String, completion: (String?, String?) -> Unit)
    fun keychainHas(): Boolean
    fun keychainDelete()
    /** "Face ID", "Touch ID" or "passcode". */
    fun unlockName(): String

    /** Open a connection (blocking). Check [IosConnection.error] before using it. */
    fun openConnection(host: String, port: Int, tls: Boolean, pinnedFingerprint: String?): IosConnection
    /** The same through Tor's SOCKS port [socksPort] (iOS 17 or later), the name resolved by Tor. */
    fun openConnectionVia(host: String, port: Int, tls: Boolean, pinnedFingerprint: String?, socksPort: Int): IosConnection

    /** A PNG of the QR code for [text]. */
    fun qrPng(text: String): NSData?
    fun canScanQr(): Boolean
    fun scanQr(completion: (String?) -> Unit)
    /** Keep the camera open for an animated QR: every new code goes to [listener] until it says it is complete. */
    fun scanQrParts(listener: IosQrPartListener)

    fun appVersion(): String
    /** HTTPS GET: the body of a 200 answer, or null. Called back on any thread. */
    fun httpGet(url: String, completion: (String?) -> Unit)
    /** Ask where to save [data] as [name] (the document picker); calls back with null when saved, else an error or "cancelled". */
    fun saveFile(name: String, data: NSData, completion: (String?) -> Unit)
    /** Let the user pick a file; its bytes, or null when cancelled. Called back on the main thread. */
    fun openFile(completion: (NSData?) -> Unit)
    /** CI screenshot mode only: write a marker file so the workflow knows the screen settled. */
    fun markReady(name: String)

    /**
     * Open a WebSocket to the pools' nostr relay (URLSessionWebSocketTask; wss only, the system's
     * certificate checks). Blocks until it is open or failed: check [IosSocket.error]. Never on the main thread.
     */
    fun openWebSocket(url: String, listener: IosSocketListener): IosSocket
    /** The same through Tor's SOCKS port [socksPort] (0: directly), on the circuit named [isolation]. */
    fun openWebSocketVia(url: String, listener: IosSocketListener, socksPort: Int, isolation: String?): IosSocket

    // ---- Lightning (iosApp/iosApp/LndEngine.swift): LND linked in as Lndmobile.xcframework

    /** False when the app was built without the engine. */
    fun lndAvailable(): Boolean
    fun lndStart(args: String, listener: IosLndListener)
    fun lndCall(method: String, request: NSData?, listener: IosLndListener)
    fun lndStream(method: String, request: NSData?, listener: IosLndListener)
    /** The node's directory: Application Support/lnd, excluded from iCloud and device backups. */
    fun lndDir(): String
    /** The node wallet's password: a Keychain item without Face ID (after first unlock, this device only). */
    fun lndPasswordLoad(): String?
    fun lndPasswordSave(hex: String): String?
    fun lndPasswordDelete()
    /** HTTPS request (blocking; never on the main thread): status and body, or status 0 and the error. */
    fun httpRequest(method: String, url: String, body: String?): IosHttpResult
    /** The same through Tor's SOCKS port [socksPort] (0: directly). */
    fun httpRequestVia(method: String, url: String, body: String?, socksPort: Int): IosHttpResult
    /** A background task while a payment is on its way; returns its id (0 when none was given). */
    fun beginBackgroundWork(name: String): Long
    fun endBackgroundWork(id: Long)

    // ---- Tor (iosApp/iosApp/ArtiEngine.swift): Arti linked in as KilombinoTor.xcframework

    /** False when the app was built without the engine. */
    fun torAvailable(): Boolean
    /** Starts Arti (blocking; never on the main thread): null once its SOCKS port listens, else why not. */
    fun torStart(dir: String, logPath: String, port: Int): String?
    fun torStop()
    /** 0: not running. 1: connecting. 2: the SOCKS port carries traffic. */
    fun torState(): Int
    /** Arti's folder: Application Support/arti, excluded from iCloud and device backups. */
    fun torDir(): String
    /** Tor's log: Application Support/tor.log. */
    fun torLogPath(): String
    fun torFreePort(): Int
    /** True when 127.0.0.1:[port] answers a SOCKS5 greeting. */
    fun torSocksAnswers(port: Int): Boolean

    // ---- Ark (iosApp/iosApp/ArkEngine.swift): the Paperclip/bark wallet linked in as KilombinoArk.xcframework

    /** False when the app was built without the engine. */
    fun arkAvailable(): Boolean
    /** Starts the engine (blocking; never on the main thread): the bearer token, or "ERR:<message>". */
    fun arkStart(datadir: String, port: Int, mnemonic: String?, passphrase: String): String
    fun arkStop()
    /** The engine's folder: Application Support/ark, excluded from iCloud and device backups. */
    fun arkDir(): String
    /** Ark's words: a Keychain item without Face ID (after first unlock, this device only). */
    fun arkWordsLoad(): String?
    fun arkWordsSave(text: String): String?
    fun arkWordsDelete()
    /**
     * HTTP request to the engine on 127.0.0.1 (http) or to the chain source (https), blocking:
     * status and body, or status 0 and the error. [bearer] goes in the Authorization header.
     */
    fun arkHttp(method: String, url: String, body: String?, bearer: String?, timeoutMs: Int): IosHttpResult
    /** A local notification at [atEpochSeconds] (0: remove the one set before). */
    fun arkRemind(atEpochSeconds: Long, title: String, body: String)
    /** Ask iOS for a background run to renew Ark coins (BGProcessingTask). */
    fun arkScheduleBackground()
}

/** An animated QR being read part by part. */
interface IosQrPartListener {
    /** A new code; true when the message is complete (the camera then closes). */
    fun onPart(text: String): Boolean
    /** The camera closed (complete or cancelled). */
    fun onEnd()
}

/** What LND answers (one call, or each message of a stream). Called on any thread. */
interface IosLndListener {
    fun onResponse(bytes: NSData?)
    fun onError(message: String)
}

interface IosHttpResult {
    fun status(): Int
    fun body(): String
}

/** What a WebSocket tells the shared code; called on any thread. */
interface IosSocketListener {
    fun onText(text: String)
    fun onPong()
    fun onClosed(error: String?)
}

interface IosSocket {
    /** Non-null when the socket could not be opened. */
    fun error(): String?
    /** Sends a text frame (blocking until it is written); false when the socket is gone. */
    fun send(text: String): Boolean
    fun ping(): Boolean
    fun close()
}

interface IosConnection {
    /** Non-null when the connection could not be opened. */
    fun error(): String?
    /** True when the server's certificate is not the pinned one (the connection was refused). */
    fun certificateChanged(): Boolean
    /** SHA-256 hex of the server's leaf certificate (also set when [certificateChanged]). */
    fun fingerprint(): String?
    /** Returns an error message, or null when written. */
    fun writeLine(line: String): String?
    /** The next line, or null when the connection closed or failed. */
    fun readLine(): String?
    fun close()
}
