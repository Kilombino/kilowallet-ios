package com.kilombino.kilowallet.app

import platform.Foundation.NSData

/**
 * What the Swift side of the app provides (iosApp/iosApp/Bridge.swift). Kept small and in plain
 * types so each piece is ordinary, reviewable Swift: Keychain + LocalAuthentication, a
 * Network.framework TLS socket, CoreImage QR codes and an AVFoundation QR scanner.
 */
interface IosBridge {
    /** Store the secret in the Keychain behind user presence; returns an error message or null. */
    fun keychainSave(walletId: String, secret: String): String?
    /** Face ID / Touch ID / passcode, then the secret (or null and an error message). Called back on the main thread. */
    fun keychainLoad(walletId: String, reason: String, completion: (String?, String?) -> Unit)
    /** "present", "missing", or "unavailable"; an error is never treated as a missing seed. */
    fun keychainStatus(walletId: String): String
    fun keychainDelete(walletId: String): String?
    /** "Face ID", "Touch ID" or "passcode". */
    fun unlockName(): String

    /** Open a connection (blocking). Check [IosConnection.error] before using it. */
    fun openConnection(host: String, port: Int, tls: Boolean, pinnedFingerprint: String?): IosConnection

    /** A PNG of the QR code for [text]. */
    fun qrPng(text: String): NSData?
    fun canScanQr(): Boolean
    fun scanQr(completion: (String?) -> Unit)

    fun appVersion(): String
    /** CI screenshot mode only: write a marker file so the workflow knows the screen settled. */
    fun markReady(name: String)
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
