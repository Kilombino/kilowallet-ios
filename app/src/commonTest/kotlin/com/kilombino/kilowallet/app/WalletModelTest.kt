package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import com.kilombino.kilowallet.core.chain.*
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.crypto.TxParse
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.wallet.Scanner
import com.kilombino.kilowallet.core.wallet.ScanEvent
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WalletModelTest {
    private val words = List(11) { "abandon" } + "about"
    private class FakePlatform : AppPlatform {
        val values = HashMap<String, String>()
        var loadCalls = 0
        var saveCalls = 0
        var saved: String? = null
        var saveError: String? = null
        var deleteError: String? = null
        var unlock: ((String?, String?) -> Unit)? = null
        var connections = 0
        val sent = mutableListOf<String>()
        var reply: ((JsonObject) -> JsonElement)? = null
        init { values["pin.${NodeEndpoint.DEFAULT.host}:${NodeEndpoint.DEFAULT.port}"] = "a".repeat(64) }
        override val seeds = object : SeedStore {
            override fun hasSeed() = saved != null
            override fun save(secret: String, done: (String?) -> Unit) {
                saveCalls++
                if (saveError == null) saved = secret
                done(saveError)
            }
            override fun load(reason: String, done: (String?, String?) -> Unit) { loadCalls++; unlock = done }
            override fun delete(): String? { if (deleteError == null) saved = null; return deleteError }
        }
        override val prefs = object : Prefs {
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        }
        override val transports = TransportFactory { _, _, _, _ ->
            connections++
            val responder = reply ?: throw ElectrumException("Offline test")
            object : LineTransport {
                override val fingerprint = "a".repeat(64)
                var request: JsonObject? = null
                override fun writeLine(line: String) {
                    request = Json.parseToJsonElement(line).jsonObject
                    if (request!!["method"]!!.jsonPrimitive.content == "blockchain.transaction.broadcast") {
                        val raw = request!!["params"]!!.jsonArray[0].jsonPrimitive.content
                        check(values.values.contains(raw)) { "Transaction must be saved before broadcast" }
                        sent += raw
                    }
                }
                override fun readLine(): String {
                    val q = request!!
                    return buildJsonObject { put("id", q["id"]!!); put("result", responder(q)) }.toString()
                }
                override fun close() {}
            }
        }
        override val unlockName = "test authentication"
        override fun qrImage(text: String): ImageBitmap? = null
        override val canScanQr = false
        override fun scanQr(done: (String?) -> Unit) = done(null)
        override val demoScreen: String? = null
        override val appVersion = "test"
    }

    private fun checkModel(block: suspend TestScope.(WalletModel, FakePlatform) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val platform = FakePlatform()
        val model = WalletModel(platform, dispatcher, dispatcher)
        try { block(model, platform) }
        finally { model.close(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rapidConfirmOnlyPromptsOnceAndBackgroundInvalidatesUnlock() = checkModel { model, p ->
        model.setUpDemo("review"); advanceUntilIdle()
        assertIs<SendPhase.Review>(model.state.value.send)
        model.confirmSend(); model.confirmSend()
        assertEquals(1, p.loadCalls)
        assertIs<SendPhase.Signing>(model.state.value.send)
        model.onBackground()
        p.unlock!!(encodeSecret(words, ""), null)
        advanceUntilIdle()
        assertEquals(0, p.connections)
        assertIs<Screen.Home>(model.state.value.screen)
        assertIs<SendPhase.Editing>(model.state.value.send)
    }
    @Test fun cancelledAuthenticationReturnsToReviewWithoutSending() = checkModel { model, p ->
        model.setUpDemo("review"); advanceUntilIdle()
        model.confirmSend()
        p.unlock!!(null, "Cancelled")
        advanceUntilIdle()
        assertIs<SendPhase.Review>(model.state.value.send)
        assertEquals(0, p.connections)
    }
    @Test fun removalInvalidatesDelayedRecoveryWordCallback() = checkModel { model, p ->
        model.setUpDemo("review"); advanceUntilIdle()
        model.revealWords()
        model.deleteWallet()
        p.unlock!!(encodeSecret(words, ""), null)
        advanceUntilIdle()
        assertIs<Screen.Welcome>(model.state.value.screen)
        assertNull(model.state.value.zpub)
    }
    @Test fun navigatingAwayInvalidatesRecoveryWordCallback() = checkModel { model, p ->
        model.setUpDemo("review"); advanceUntilIdle()
        model.revealWords(); model.go(Screen.Home)
        p.unlock!!(encodeSecret(words, ""), null)
        assertIs<Screen.Home>(model.state.value.screen)
    }
    @Test fun storageFailureDoesNotCommitWalletMetadata() = checkModel { model, p ->
        p.saveError = "Keychain unavailable"
        model.restore(words, "TREZOR")
        model.restore(words, "different")
        advanceUntilIdle()
        assertEquals(1, p.saveCalls)
        assertNull(model.state.value.zpub)
        assertNull(p.values["wallet.zpub"])
        assertNull(p.saved)
    }
    @Test fun recoveryPreservesPassphraseAndRejectsInvalidBackup() = checkModel { model, p ->
        model.restore(List(12) { "abandon" }, "")
        advanceUntilIdle()
        assertEquals(0, p.saveCalls)
        model.restore(words, "TREZOR")
        advanceUntilIdle()
        assertEquals(words to "TREZOR", decodeSecret(p.saved!!))
        assertEquals(Signer.accountZpub(words, "TREZOR"), p.values["wallet.zpub"])
    }
    @Test fun incompleteRecoveryConfirmationCannotStoreSeed() = checkModel { model, p ->
        model.wroteDown(words)
        assertFalse(model.confirmCreate(words, emptyMap()))
        advanceUntilIdle()
        assertEquals(0, p.saveCalls)
    }
    @Test fun failedDeletionKeepsWalletMetadata() = checkModel { model, p ->
        model.setUpDemo("review"); advanceUntilIdle()
        val zpub = model.state.value.zpub
        p.deleteError = "Keychain locked"
        model.deleteWallet()
        assertEquals(zpub, model.state.value.zpub)
        assertNotNull(model.state.value.message)
    }
    @Test fun lostBroadcastReceiptRestoresAndResendsOnlyTheOriginalTransaction() = checkModel { model, p ->
        p.reply = { q -> when (q["method"]!!.jsonPrimitive.content) {
            "server.version" -> buildJsonArray { add("test"); add("1.4") }
            "blockchain.headers.subscribe" -> buildJsonObject { put("height", 976086); put("hex", "000000a0700c8b9690014507c6c277900d743ac3ed6d814f90772d060000000000000000670caeac2404c80798669b242623ea7310be8f47166168e9734baab9dec80446b2c2c66a2ad80019be9815dad0f4b1da0000000000000000b10cf8578500000000000000b2c2c66a7501000000000000000000000000000000000000d6e40e000000000000000000000000000000000000000000000000000000000000000000") }
            "blockchain.transaction.broadcast" -> {
                if (p.sent.size == 1) throw ElectrumException("lost receipt")
                JsonPrimitive(TxParse.txid(TxParse.parse(q["params"]!!.jsonArray[0].jsonPrimitive.content)))
            }
            "blockchain.transaction.get" -> throw ElectrumException("not found")
            "blockchain.scripthash.get_history" -> JsonArray(emptyList())
            else -> error("Unexpected mock request")
        } }
        model.setUpDemo("review"); advanceUntilIdle()
        // Persist the demo public identity to exercise process restart; never real seed material.
        p.values["wallet.kind"] = "HOT"
        p.values["wallet.zpub"] = model.state.value.zpub!!
        model.confirmSend()
        p.unlock!!(encodeSecret(words, ""), null)
        advanceUntilIdle()
        assertIs<SendPhase.Pending>(model.state.value.send)
        assertEquals(1, p.sent.size)
        val original = p.sent.single()
        val identity = model.state.value.zpub!!
        assertEquals(1, AddressBook(p.prefs).next(identity, 1))
        model.close(); advanceUntilIdle()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restarted = WalletModel(p, dispatcher, dispatcher)
        try {
            advanceUntilIdle()
            restarted.go(Screen.Send)
            assertIs<SendPhase.Pending>(restarted.state.value.send)
            restarted.retryPendingSend(); restarted.retryPendingSend()
            advanceUntilIdle()
            assertEquals(listOf(original, original), p.sent)
            assertEquals(1, AddressBook(p.prefs).next(identity, 1))
            assertEquals(TxParse.txid(TxParse.parse(original)), (restarted.state.value.send as SendPhase.Sent).txid)
            assertTrue(p.values.keys.none { it.startsWith("pending.") })
        } finally { restarted.close(); advanceUntilIdle() }
    }


    @Test fun restoresAllFiveStandardWordCountsWithoutChangingPassphrase() = checkModel { model, p ->
        for (bytes in listOf(16, 20, 24, 28, 32)) {
            val recovery = Bip39.fromEntropy(ByteArray(bytes) { it.toByte() })
            model.restore(recovery, " spaced passphrase ")
            advanceUntilIdle()
            assertEquals(recovery to " spaced passphrase ", decodeSecret(p.saved!!))
            assertEquals(Signer.accountZpub(recovery, " spaced passphrase "), model.state.value.zpub)
            model.deleteWallet()
        }
        assertEquals(5, p.saveCalls)
    }
    @Test fun receiveVisitIsStableButNextVisitAndRestartGetFreshAddresses() = checkModel { model, p ->
        model.setUpDemo("receive"); advanceUntilIdle()
        val first = model.receiveAddress()!!
        assertEquals(first, model.receiveAddress())
        model.go(Screen.Home); model.go(Screen.Receive)
        val second = model.receiveAddress()!!
        assertNotEquals(first.first, second.first)
        assertTrue(second.second.endsWith("/0/1"))
        p.values["wallet.kind"] = "HOT"; p.values["wallet.zpub"] = model.state.value.zpub!!
        model.close(); advanceUntilIdle()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restarted = WalletModel(p, dispatcher, dispatcher)
        try {
            advanceUntilIdle(); restarted.go(Screen.Receive)
            assertTrue(restarted.receiveAddress()!!.second.endsWith("/0/2"))
        } finally { restarted.close(); advanceUntilIdle() }
    }
    @Test fun cancellingAuthenticationDoesNotConsumeAChangeAddress() = checkModel { model, p ->
        model.setUpDemo("review"); advanceUntilIdle()
        val identity = model.state.value.zpub!!
        model.confirmSend(); p.unlock!!(null, "Cancelled"); advanceUntilIdle()
        assertEquals(0, AddressBook(p.prefs).next(identity, 1))
        assertEquals(0, (model.state.value.send as SendPhase.Review).changeIndex)
    }
    @Test fun scannerFindsIssuedAddressesBeyondAnEmptyGapOnBothBranches() = checkModel { model, p ->
        val zpub = Signer.accountZpub(words, "")
        val parsed = Bip32.parseExtendedPubKey(zpub)
        val late = (0..1).map { chain ->
            Address.scriptHashFor(Bip32.derivePath(parsed, chain, 25).pubkey(), parsed.scriptType)
        }.toSet()
        val histories = mutableSetOf<String>()
        p.reply = { q -> when(q["method"]!!.jsonPrimitive.content) {
            "server.version" -> buildJsonArray { add("test"); add("1.4") }
            "blockchain.headers.subscribe" -> buildJsonObject { put("height", 976086); put("hex", "000000a0700c8b9690014507c6c277900d743ac3ed6d814f90772d060000000000000000670caeac2404c80798669b242623ea7310be8f47166168e9734baab9dec80446b2c2c66a2ad80019be9815dad0f4b1da0000000000000000b10cf8578500000000000000b2c2c66a7501000000000000000000000000000000000000d6e40e000000000000000000000000000000000000000000000000000000000000000000") }
            "blockchain.scripthash.get_history" -> {
                val hash = q["params"]!!.jsonArray[0].jsonPrimitive.content
                histories += hash
                if (hash in late) buildJsonArray { add(buildJsonObject { put("tx_hash", "ab".repeat(32)); put("height", 0) }) }
                else JsonArray(emptyList())
            }
            "blockchain.scripthash.get_balance" -> buildJsonObject { put("confirmed", 0); put("unconfirmed", 1000) }
            "blockchain.transaction.get" -> throw ElectrumException("Synthetic history only")
            else -> error("Unexpected request")
        } }
        val scanner = Scanner(p.transports, dispatcher = StandardTestDispatcher(testScheduler))
        val events = scanner.scan(zpub, NodeEndpoint.DEFAULT, "a".repeat(64),
            knownReceiveCount = 26, knownChangeCount = 26).toList()
        val done = events.filterIsInstance<ScanEvent.Done>().single()
        assertEquals(setOf(0 to 25, 1 to 25), done.rows.map { it.chainIndex to it.index }.toSet())
        assertTrue(histories.containsAll(late))
        val connections = p.connections
        val invalid = scanner.scan(zpub, NodeEndpoint.DEFAULT, "a".repeat(64), knownReceiveCount = 10001).toList()
        assertIs<ScanEvent.Failed>(invalid.single())
        assertEquals(connections, p.connections)
    }

    @Test fun unusedReceiveLimitStillLetsUserReceiveOnLatestAddress() = checkModel { model, p ->
        model.setUpDemo("receive"); advanceUntilIdle()
        val issued = mutableListOf<Pair<String, String>>()
        repeat(20) {
            model.go(Screen.Home); model.go(Screen.Receive)
            issued += model.receiveAddress()!!
        }
        assertEquals(20, issued.map { it.first }.toSet().size)
        model.go(Screen.Home); model.go(Screen.Receive)
        assertEquals(issued.last(), model.receiveAddress())
        assertTrue(model.state.value.message!!.contains("latest unused address"))
        assertEquals(20, AddressBook(p.prefs).next(model.state.value.zpub!!, 0))
    }
}
