package com.kilombino.kilowallet.core.ln

/** What LND answers to one call (or to each message of a stream). Called on any thread. */
interface LndCallback {
    fun onResponse(bytes: ByteArray?)
    fun onError(message: String)
}

/**
 * The Lightning engine: LND linked into the app as a library (iOS: Lndmobile.xcframework, bound
 * by gomobile from the same sources as Android's Lndmobile.aar). [method] is the gomobile
 * function's name without its prefix, as Android calls it (`getInfo`, `routerSendPaymentV2`…);
 * requests and answers are serialized lnrpc protobuf ([Pb], [PbMsg]).
 */
interface LndEngine {
    /** Starts LND with these command-line [args]; [cb] answers once its RPC is up. */
    fun start(args: String, cb: LndCallback)
    fun call(method: String, request: ByteArray, cb: LndCallback)
    /** A server stream: [cb] gets each message, then an error (EOF when it simply ended). */
    fun stream(method: String, request: ByteArray, cb: LndCallback)
}

/** The node's files and the network, as each platform gives them. */
interface LndHost {
    /** LND's directory (not backed up). */
    val dir: String
    fun writeText(path: String, text: String)
    fun readBytes(path: String): ByteArray?
    fun writeBytes(path: String, bytes: ByteArray)
    fun exists(path: String): Boolean
    fun delete(path: String)
    /** Every file under [dir], at any depth (full paths). */
    fun files(dir: String): List<String> = emptyList()
    /** The last [maxBytes] of a file, as text, or null. */
    fun tail(path: String, maxBytes: Int): String?
    /** The addresses [host] resolves to (empty when it doesn't). */
    fun resolve(host: String): List<String>
    /** HTTPS request (blocking): the status code and body, or throws. */
    fun http(method: String, url: String, body: String?): Pair<Int, String>
    fun nowSeconds(): Long
}
