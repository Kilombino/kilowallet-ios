package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import com.kilombino.kilowallet.core.ark.ArkEngine
import com.kilombino.kilowallet.core.ark.ArkHost
import com.kilombino.kilowallet.core.chain.TransportFactory
import com.kilombino.kilowallet.core.kilojoin.WebSocketFactory
import com.kilombino.kilowallet.core.ln.LndEngine
import com.kilombino.kilowallet.core.ln.LndHost

/**
 * Where the recovery words live. On iOS: one Keychain item, readable only on this device while it
 * has a passcode, and only after Face ID / Touch ID / the passcode (`iosApp/iosApp/Keychain.swift`).
 * The words are never written anywhere else.
 */
interface SeedStore {
    fun hasSeed(): Boolean
    /** Store [secret]; calls back with an error message, or null on success. */
    fun save(secret: String, done: (String?) -> Unit)
    /** Ask the user to authenticate, then return the secret (or an error message). */
    fun load(reason: String, done: (secret: String?, error: String?) -> Unit)
    fun delete()
}

/** Non-secret settings (the wallet's public key, the server and its certificate pin). */
interface Prefs {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * The Lightning node's platform side: the engine (LND linked in as a library), its files and the
 * network, and the password its wallet file is encrypted with (iOS: a Keychain item readable
 * after the first unlock, with no Face ID, so the node can start by itself; it protects the
 * file at rest, the money is in keys that come from the recovery words).
 */
interface LightningPlatform {
    val engine: LndEngine
    val host: LndHost
    fun loadPassword(): String?
    /** Returns an error message, or null when stored. */
    fun savePassword(hex: String): String?
    fun deletePassword()
    /**
     * While a payment is on its way, leaving the app keeps the node running for the little time
     * iOS allows (a background task); [end] gives it back.
     */
    fun beginBackgroundWork(name: String): Any? = null
    fun endBackgroundWork(token: Any?) {}
    /**
     * An HTTP request (blocking) through Tor's SOCKS port [socks] when given (then plain http to a
     * .onion is allowed: the address is the server's key), else https directly: status and body.
     */
    fun request(method: String, url: String, body: String?, socks: Int?): Pair<Int, String> = host.http(method, url, body)
}

/**
 * Ark's platform side: the engine (the Paperclip/bark wallet linked in as a library), its folder,
 * the HTTP client to it and the store of its words (iOS: a Keychain item readable after the first
 * unlock, with no Face ID, so coins can be renewed when the app opens; the words open Ark only).
 */
interface ArkPlatform {
    val engine: ArkEngine
    val host: ArkHost
    /** While Ark works (a payment, a renewal), leaving the app keeps it going the little time iOS allows. */
    fun beginArkWork(name: String): Any? = null
    fun endArkWork(token: Any?) {}
    /**
     * A local notification at [atEpochSeconds] (null: cancel the one set before), to open the app
     * before an Ark coin expires: iOS does not let the wallet stay up to renew it by itself.
     */
    fun remind(atEpochSeconds: Long?, title: String, body: String) {}
    /** Ask iOS for a background run to renew Ark coins (it picks the time, usually while charging). */
    fun scheduleBackgroundRenewal() {}
}

interface AppPlatform {
    val seeds: SeedStore
    val prefs: Prefs
    val transports: TransportFactory
    /** WebSockets to the pools' nostr relay (iOS: URLSessionWebSocketTask, iosApp/iosApp/RelaySocket.swift). */
    val sockets: WebSocketFactory get() = WebSocketFactory { _, _ -> error("no WebSocket on this platform") }
    /** "Face ID", "Touch ID" or "your passcode": what unlocks the words on this device. */
    val unlockName: String
    /** A QR code image for [text], or null if this platform cannot draw one. */
    fun qrImage(text: String): ImageBitmap?
    val canScanQr: Boolean
    /** Open the camera and call back with what the QR code said (null if cancelled). */
    fun scanQr(done: (String?) -> Unit)
    /**
     * Read an animated QR: the camera stays open and every new code goes to [onPart] until it
     * returns true (complete); [onEnd] when the camera closes. Without it, one code per scan.
     */
    fun scanQrParts(onPart: (String) -> Boolean, onEnd: () -> Unit) {
        scanQr { t -> if (t != null) onPart(t); onEnd() }
    }
    /** Screenshot mode for CI: which screen to set up with a throwaway demo wallet, or null. */
    val demoScreen: String?
    /** Screenshot mode: tell the CI script the screen has settled. */
    fun markReady(name: String) {}
    val appVersion: String
    /**
     * HTTPS GET of [url] (the price feed only), calling back with the body of a 200 answer, or
     * null. On iOS: URLSession (iosApp/iosApp/Bridge.swift).
     */
    fun httpGet(url: String, done: (String?) -> Unit) = done(null)
    /** Save [data] as [name] where the user chooses; calls back with null when saved, else why not ("cancelled"). */
    fun saveFile(name: String, data: ByteArray, done: (String?) -> Unit) = done("Saving files is not available here.")
    /** Let the user pick a file; calls back with its bytes, or null when cancelled. */
    fun openFile(done: (ByteArray?) -> Unit) = done(null)
    /** The Lightning node, or null where it is not available (desktop, screenshot demos). */
    val lightning: LightningPlatform? get() = null
    /** Ark, or null where it is not available (desktop, screenshot demos). */
    val ark: ArkPlatform? get() = null
    /** Calls [action] each time the app comes back to the foreground (iOS: after being suspended). */
    fun onForeground(action: () -> Unit) {}
}
