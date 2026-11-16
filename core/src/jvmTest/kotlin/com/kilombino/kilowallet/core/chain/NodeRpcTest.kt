package com.kilombino.kilowallet.core.chain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Your own node by RPC (Android 1.6.0's NodeRpcTest, same connection texts) and its HTTP over lines. */
class NodeRpcTest {
    @Test fun parsesConnectionTextsAsAndroid() {
        assertEquals(RpcConn("http://10.0.0.5:8332/", "u", "p:x"), RpcConn.parse("btcrpc://u:p%3Ax@10.0.0.5:8332"))
        assertEquals(RpcConn("http://abc.onion:8332/", "", ""), RpcConn.parse("abc.onion"))
        assertTrue(RpcConn.parse("http://a:b@xyz.onion:18443").isOnion)
        // Plain http only to this network or a .onion; https anywhere.
        assertTrue(runCatching { RpcConn.parse("http://u:p@203.0.113.5:8332") }.isFailure)
        assertTrue(runCatching { RpcConn.parse("http://u:p@node.example.com:8332") }.isFailure)
        assertTrue(runCatching { RpcConn.parse("https://u:p@node.example.com:8332") }.isSuccess)
        assertTrue(runCatching { RpcConn.parse("http://u:p@192.168.1.20:8332") }.isSuccess)
        assertTrue(runCatching { RpcConn.parse("http://u:p@umbrel:8332") }.isSuccess)
        assertTrue(runCatching { RpcConn.parse("http://u:p@start9.local:8332") }.isSuccess)
        // What a node's QR code carries: user:pass@host:port, with or without a scheme.
        assertEquals(RpcConn("http://192.168.1.20:8332/", "kw", "s3cr3t"), RpcConn.parse("kw:s3cr3t@192.168.1.20"))
        assertEquals(RpcConn("https://node.example.com:443/", "a", "b"), RpcConn.parse("https://a:b@node.example.com:443"))
        assertEquals(RpcConn("http://[fd00::5]:8332/", "", ""), RpcConn.parse("http://[fd00::5]:8332"))
    }

    @Test fun keepsTheListAcrossSaves() {
        val l = listOf(RpcConn("http://10.0.0.5:8332/", "u", "p"), RpcConn("https://n.example:443/", "a", "b", "ab12"))
        assertEquals(l, RpcConn.listFromJson(RpcConn.listToJson(l)))
        assertEquals(emptyList(), RpcConn.listFromJson(null))
    }

    @Test fun readsHttpAnswersPlainAndChunked() {
        fun read(vararg lines: String): Pair<Int, String> { val it = lines.iterator(); return NodeRpcBackend.readHttp { if (it.hasNext()) it.next() else null } }
        val body = """{"result":5,"error":null,"id":"kw"}"""
        assertEquals(200 to body, read("HTTP/1.1 200 OK\r", "Content-Type: application/json\r", "Content-Length: ${body.length + 1}\r", "\r", body))
        assertEquals(401 to "", read("HTTP/1.1 401 Unauthorized\r", "Content-Length: 0\r", "\r"))
        // A proxy in front of the node (StartOS) may chunk the answer.
        val hex = (body.length + 1).toString(16)
        assertEquals(200 to body, read("HTTP/1.1 200 OK\r", "Transfer-Encoding: chunked\r", "\r", "$hex\r", body, "\r", "0\r", "\r"))
    }

    @Test fun answersFromTheNodeAreCheckedForTheBlake2bChain() {
        val active = kotlinx.serialization.json.Json.parseToJsonElement("""{"deployments":{"blake2b":{"active":true}}}""").let { it as kotlinx.serialization.json.JsonObject }
        val not = kotlinx.serialization.json.Json.parseToJsonElement("""{"deployments":{"taproot":{"active":true}}}""").let { it as kotlinx.serialization.json.JsonObject }
        assertTrue(NodeRpcBackend.isBlake(active))
        assertTrue(!NodeRpcBackend.isBlake(not))
    }

    /**
     * The shared code calls every LND method, streams included, through LndEngine.call (as
     * Android's one gomobile function per method); each must be in the iOS engine's tables, or
     * paying fails with "no such LND call" (rc6, 16 nov 2026).
     */
    @Test fun everyLndMethodTheAppCallsIsInTheIosEngine() {
        val swift = File("../iosApp/iosApp/LndmobileEngine.swift").readText()
        val known = Regex("""^\s*"(\w+)": \{""", RegexOption.MULTILINE).findAll(swift).map { it.groupValues[1] }.toSet()
        val streams = Regex("""streams: \[[\s\S]*?\n    \]""").find(swift)!!.value
        assertTrue("if unary[method] == nil, let s = streams[method]" in swift, "call() must fall back to the streams")
        assertTrue("\"routerSendPaymentV2\"" in streams)
        val used = File("src/commonMain/kotlin/com/kilombino/kilowallet/core/ln").walk().filter { it.extension == "kt" }
            .flatMap { f -> Regex("""engine\.(?:call|stream)\("(\w+)"""").findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(used.isNotEmpty())
        val missing = used - known
        assertTrue(missing.isEmpty(), "LND methods missing from LndmobileEngine.swift: $missing")
    }
}
