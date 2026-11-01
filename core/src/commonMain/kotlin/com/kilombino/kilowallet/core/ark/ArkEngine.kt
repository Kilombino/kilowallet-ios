package com.kilombino.kilowallet.core.ark

/**
 * The Ark engine: the Paperclip/bark wallet daemon and its REST API, linked into the app (iOS:
 * KilombinoArk.xcframework, from the same sources as the Android app's libkilombino_ark.so; see
 * ark-engine/ENGINE). It serves on 127.0.0.1 only, behind a random token made at every start.
 */
interface ArkEngine {
    /**
     * Starts the engine; returns the bearer token, or "ERR:<message>". [mnemonic] is null while
     * there is no Ark wallet yet; [passphrase] is "" for none. Blocking.
     */
    fun start(datadir: String, port: Int, mnemonic: String?, passphrase: String): String
    fun stop()
}

/** The engine's folder, the HTTP client to it and the app's small stores, as each platform gives them. */
interface ArkHost {
    /** The engine's folder (not backed up: the coins come back from the words). */
    val dir: String
    /** HTTP request (blocking, never on the main thread): status and body, or throws when nothing answered. */
    fun http(method: String, url: String, body: String?, headers: Map<String, String>, timeoutMs: Int): Pair<Int, String>
    fun readText(path: String): String?
    fun writeText(path: String, text: String)
    fun readBytes(path: String): ByteArray?
    fun writeBytes(path: String, bytes: ByteArray)
    fun exists(path: String): Boolean
    /** Deletes a file or a folder with everything in it. */
    fun delete(path: String)
    fun list(path: String): List<String>
    /** The last [maxBytes] of a file, as text, or null. */
    fun tail(path: String, maxBytes: Int): String?
    /** Non-secret settings of the Ark wallet. */
    fun pref(key: String): String?
    fun setPref(key: String, value: String?)
    /**
     * The Ark wallet's words (and passphrase after a newline), kept where the engine can read them
     * without Face ID so coins can be renewed when the app opens (iOS: a Keychain item readable
     * after the first unlock, this device only). Null when there is no Ark wallet.
     */
    fun loadWords(): String?
    /** Returns an error message, or null when stored. */
    fun saveWords(text: String): String?
    fun deleteWords()
}
