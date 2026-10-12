package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import com.kilombino.kilowallet.core.chain.TransportFactory

/**
 * Where the recovery words live. On iOS: a separate Keychain item per wallet, readable only on this device while it
 * has a passcode, and only after Face ID / Touch ID / the passcode (`iosApp/iosApp/Keychain.swift`).
 * The words are never written anywhere else.
 */
interface SeedStore {
    fun hasSeed(walletId: String): Boolean
    /** Store [secret]; calls back with an error message, or null on success. */
    fun save(walletId: String, secret: String, done: (String?) -> Unit)
    /** Ask the user to authenticate, then return the secret (or an error message). */
    fun load(walletId: String, reason: String, done: (secret: String?, error: String?) -> Unit)
    fun delete(walletId: String): String?
}

/** Non-secret settings (the wallet's public key, the server and its certificate pin). */
interface Prefs {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

interface AppPlatform {
    val seeds: SeedStore
    val prefs: Prefs
    val transports: TransportFactory
    /** "Face ID", "Touch ID" or "your passcode": what unlocks the words on this device. */
    val unlockName: String
    /** A QR code image for [text], or null if this platform cannot draw one. */
    fun qrImage(text: String): ImageBitmap?
    val canScanQr: Boolean
    /** Open the camera and call back with what the QR code said (null if cancelled). */
    fun scanQr(done: (String?) -> Unit)
    /** Screenshot mode for CI: which screen to set up with a throwaway demo wallet, or null. */
    val demoScreen: String?
    /** Screenshot mode: tell the CI script the screen has settled. */
    fun markReady(name: String) {}
    val appVersion: String
}
