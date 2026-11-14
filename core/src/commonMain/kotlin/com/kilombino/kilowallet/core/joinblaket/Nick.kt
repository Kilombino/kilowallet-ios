package com.kilombino.kilowallet.core.joinblaket

import com.kilombino.kilowallet.core.crypto.Base58
import com.kilombino.kilowallet.core.crypto.Ec
import com.kilombino.kilowallet.core.crypto.Hashes
import com.kilombino.kilowallet.core.crypto.Hashes.toHex
import com.kilombino.kilowallet.core.platform.secureRandomBytes

/**
 * A JoinMarket nick and the key behind it (`NickIdentity` in `jmcore/crypto.py`). The taker
 * uses a fresh one per CoinJoin; every privmsg it sends is signed with it.
 */
class NickIdentity(val privateKey: ByteArray) {
    init { require(Ec.isValidSecret(privateKey)) { "nick key out of range" } }

    /** 33-byte compressed public key. */
    val pubkey: ByteArray = Ec.publicKey(privateKey)
    val pubkeyHex: String = pubkey.toHex()
    val nick: String = Nick.fromPubkey(pubkeyHex)

    /**
     * `sign_message`: returns `"<data> <pubkey_hex> <sig_b64>"`, the signature covering
     * `data + hostid` (never the command).
     */
    fun signData(data: String, hostid: String = Wire.ONION_HOSTID): String =
        "$data $pubkeyHex ${JmSig.signMessage(privateKey, (data + hostid).encodeToByteArray())}"

    /** `sign_bytes`: base64 DER over the signed-message hash of raw [msg]. */
    fun signBytes(msg: ByteArray): String = JmSig.signMessage(privateKey, msg)

    companion object {
        /** A uniformly random nick key (Python draws sha256 of 16 random bytes; equivalent). */
        fun random(): NickIdentity {
            while (true) {
                val k = secureRandomBytes(32)
                if (Ec.isValidSecret(k)) return NickIdentity(k)
            }
        }
    }
}

/** Nick derivation and privmsg authentication (`jmcore/crypto.py`, `jmcore/protocol.py`). */
object Nick {
    const val VERSION = 5
    private val NICK_RE = Regex("J[0-9][1-9A-HJ-NP-Za-km-zO]{14}")

    /**
     * `"J5" + base58(sha256(ascii hex of the compressed key)[:10])`, right-padded with 'O' to
     * 14 characters. The hash input is the hex STRING, not the key bytes.
     */
    fun fromPubkey(pubkeyHex: String, version: Int = VERSION): String {
        val h = Hashes.sha256(pubkeyHex.lowercase().encodeToByteArray()).copyOfRange(0, 10)
        return "J$version" + Base58.encode(h).padEnd(14, 'O')
    }

    fun isValid(nick: String): Boolean = NICK_RE.matches(nick)

    /** `get_nick_version`: the digit after 'J', else 5. */
    fun version(nick: String): Int =
        if (nick.length >= 2 && nick[0] == 'J' && nick[1] in '0'..'9') nick[1] - '0' else VERSION

    /** Result of [verifyPrivmsg]; [command] and [data] are filled even when [ok] is false. */
    data class Verified(val ok: Boolean, val command: String, val data: String)

    /**
     * `verify_signed_privmsg`: [rest] is `"<command> <data...> <pubkey_hex> <sig_b64>"` (the line
     * after `from!to!`). OK only if the key hashes to [fromNick] and its strict-DER low-S
     * signature over `data + hostid` verifies.
     */
    fun verifyPrivmsg(fromNick: String, rest: String, hostid: String = Wire.ONION_HOSTID): Verified {
        val t = rest.split(" ")
        if (t.size < 3) return Verified(false, "", "")
        val command = t[0]
        val pubHex = t[t.size - 2]
        val data = t.subList(1, t.size - 2).joinToString(" ")
        val ok = runCatching {
            fromPubkey(pubHex, version(fromNick)) == fromNick &&
                JmSig.verifyMessage(data + hostid, t.last(), Wire.hex(pubHex))
        }.getOrDefault(false)
        return Verified(ok, command, data)
    }
}

/**
 * The directory handshake and JMP-0005 nick authentication (`jmcore/protocol.py`,
 * `jmcore/nick_auth.py`, `jmcore/directory_client.py`), as pure functions.
 */
object NickAuth {
    const val DOMAIN = "nick-auth-v1"
    private val LOWER_HEX_64 = Regex("^[0-9a-f]{64}$")

    /**
     * The `line` of the taker's HANDSHAKE frame. Keys in Python's order (`app-name, directory,
     * location-string, proto-ver, features, nick, network`), features sorted and only the true
     * ones, `", "`/`": "` separators: nick auth hashes these exact bytes.
     */
    fun handshakeLine(nick: String, network: String = "xbt", nickAuth: Boolean = true, neutrinoCompat: Boolean = true): String {
        val features = buildList {
            if (neutrinoCompat) add("neutrino_compat")
            if (nickAuth) add("nick_auth")
            add("peerlist_features"); add("ping")
        }.sorted().joinToString(", ") { "${Wire.quote(it)}: true" }
        return "{\"app-name\": \"joinmarket\", \"directory\": false, \"location-string\": \"NOT-SERVING-ONION\", " +
            "\"proto-ver\": ${Nick.VERSION}, \"features\": {$features}, \"nick\": ${Wire.quote(nick)}, " +
            "\"network\": ${Wire.quote(network)}}"
    }

    /** sha256 hex of the UTF-8 handshake line. */
    fun handshakeSha256(line: String): String = Hashes.sha256(line.encodeToByteArray()).toHex()

    /**
     * Checks on the directory's DN_HANDSHAKE reply, in Python's order; returns an error or null.
     * [requireNickAuth] is the `require_verified` policy.
     */
    fun checkDirectoryReply(frame: Wire.Frame, network: String = "xbt", requireNickAuth: Boolean = true): String? {
        if (frame.type != Wire.HANDSHAKE && frame.type != Wire.DN_HANDSHAKE) return "unexpected response type ${frame.type}"
        val o = runCatching { JbJson.obj(frame.line) }.getOrNull() ?: return "handshake reply is not JSON"
        if (!JbJson.optBoolean(o, "accepted", false)) return "handshake rejected"
        if (JbJson.optString(o, "network") != network) return "directory network mismatch"
        val single = JbJson.optInt(o, "proto-ver", Nick.VERSION)
        val range = JbJson.has(o, "proto-ver-min") && JbJson.has(o, "proto-ver-max")
        val min = if (range) JbJson.getInt(o, "proto-ver-min") else single
        val max = if (range) JbJson.getInt(o, "proto-ver-max") else single
        if (Nick.VERSION !in min..max) return "no compatible protocol version"
        val nickAuth = (o["features"] as? kotlinx.serialization.json.JsonObject)?.get("nick_auth").let(JbJson::isTrue)
        if (requireNickAuth && !nickAuth) return "directory does not support required nick authentication"
        return null
    }

    /** A parsed NICK_AUTH_CHALLENGE line. */
    data class Challenge(val challenge: String, val directoryId: String)

    /** Parse the challenge line: exactly the two fields, lowercase 64-hex challenge. */
    fun parseChallenge(line: String): Challenge {
        val o = JbJson.obj(line)
        require(o.size == 2) { "unexpected challenge fields" }
        val c = JbJson.getString(o, "challenge")
        require(LOWER_HEX_64.matches(c)) { "bad challenge" }
        return Challenge(c, JbJson.getString(o, "directory-id"))
    }

    /** `build_nick_auth_signed_message`: the transcript ASCII bytes followed by `nick-auth-v1`. */
    fun signedMessage(challenge: String, directoryId: String, handshakeSha256: String, nick: String, pubkeyHex: String): ByteArray {
        require(LOWER_HEX_64.matches(challenge) && LOWER_HEX_64.matches(handshakeSha256)) { "bad hex field" }
        require(Nick.isValid(nick)) { "bad nick" }
        return "nick-auth|$challenge|$directoryId|$handshakeSha256|$nick|$pubkeyHex".encodeToByteArray() + DOMAIN.encodeToByteArray()
    }

    /**
     * The NICK_AUTH_PROOF line, compact JSON `{"pubkey":"..","signature":".."}`, for a challenge
     * whose directory id the caller already matched against its configured one.
     */
    fun proofLine(identity: NickIdentity, challenge: Challenge, handshakeLine: String): String {
        val msg = signedMessage(challenge.challenge, challenge.directoryId, handshakeSha256(handshakeLine), identity.nick, identity.pubkeyHex)
        return "{\"pubkey\":${Wire.quote(identity.pubkeyHex)},\"signature\":${Wire.quote(identity.signBytes(msg))}}"
    }

    /** True only for `{"code":"ok","verified":true}`. */
    fun resultOk(line: String): Boolean = runCatching {
        val o = JbJson.obj(line)
        JbJson.getString(o, "code") == "ok" && JbJson.getBoolean(o, "verified")
    }.getOrDefault(false)
}
