package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import com.kilombino.kilowallet.core.chain.*
import com.kilombino.kilowallet.core.wallet.Signer
import com.kilombino.kilowallet.core.crypto.TxParse
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.Base58
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
        var now = 1_700_000_000_000L
        var exported: String? = null
        var exportCalls = 0
        var shareCompletion: ((String?) -> Unit)? = null
        override fun nowMillis() = now
        override fun shareCsv(fileName: String, contents: String, done: (String?) -> Unit) {
            assertEquals("kilowallet-history.csv", fileName)
            exported = contents; exportCalls++; shareCompletion = done
        }
        var loadCalls = 0
        var saveCalls = 0
        var saved: String? = null
        val secrets = HashMap<String, String>()
        var loadedId: String? = null
        val deletedIds = mutableListOf<String>()
        var saveError: String? = null
        var probeError = false
        var deleteError: String? = null
        var unlock: ((String?, String?) -> Unit)? = null
        var catalogWrites = 0
        var failCatalogWriteAt: Int? = null
        var connections = 0
        val sent = mutableListOf<String>()
        var reply: ((JsonObject) -> JsonElement)? = null
        init { values["pin.${NodeEndpoint.DEFAULT.host}:${NodeEndpoint.DEFAULT.port}"] = "a".repeat(64) }
        override val seeds = object : SeedStore {
            override fun hasSeed(walletId: String): Boolean { check(!probeError) { "Keychain temporarily unavailable" }; return secrets.containsKey(walletId) }
            override fun save(walletId: String, secret: String, done: (String?) -> Unit) {
                saveCalls++
                if (saveError == null) { saved = secret; secrets[walletId] = secret }
                done(saveError)
            }
            override fun load(walletId: String, reason: String, done: (String?, String?) -> Unit) { loadCalls++; loadedId = walletId; unlock = done }
            override fun delete(walletId: String): String? { deletedIds += walletId; if (deleteError == null) { secrets.remove(walletId); saved = null }; return deleteError }
        }
        override val prefs = object : Prefs {
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String?) {
                if (key == WalletCatalog.KEY && ++catalogWrites == failCatalogWriteAt) error("Simulated metadata failure")
                if (value == null) values.remove(key) else values[key] = value
            }
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
        assertEquals(Signer.accountZpub(words, "TREZOR"), WalletCatalog(p.prefs).value.wallets.single().zpub)
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
    @Test fun separateHotWalletsKeepSecretsNamesAddressesAndSelectionAcrossRestart() = checkModel { model, p ->
        model.setNewWalletName("Everyday"); model.restore(words, "first"); advanceUntilIdle()
        val first = model.state.value.wallets.single()
        model.go(Screen.Receive)
        val firstAddress = model.receiveAddress()!!
        model.addWallet(); model.setNewWalletName("Savings"); model.restore(words, "second"); advanceUntilIdle()
        val second = model.state.value.wallets.last()
        assertNotEquals(first.id, second.id)
        assertEquals(2, p.secrets.size)
        assertTrue(p.values.values.none { value -> p.secrets.values.any { value.contains(it) } })
        assertEquals("first", decodeSecret(p.secrets[first.id]!!).second)
        assertEquals("second", decodeSecret(p.secrets[second.id]!!).second)
        model.go(Screen.Receive)
        assertNotEquals(firstAddress.first, model.receiveAddress()!!.first)
        model.selectWallet(first.id); advanceUntilIdle(); model.go(Screen.Receive)
        assertTrue(model.receiveAddress()!!.second.endsWith("/0/1"))
        assertEquals(first.id, model.state.value.walletId)
        assertNull(model.state.value.spendable)
        model.renameWallet(first.id, "Daily")
        model.close(); advanceUntilIdle()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restarted = WalletModel(p, dispatcher, dispatcher)
        try {
            advanceUntilIdle()
            assertEquals(first.id, restarted.state.value.walletId)
            assertEquals(listOf("Daily", "Savings"), restarted.state.value.wallets.map { it.name })
            restarted.revealWords()
            assertEquals(first.id, p.loadedId)
        } finally { restarted.close(); advanceUntilIdle() }
    }

    @Test fun duplicateImportDoesNotReplaceExistingWordsOrAddAnotherEntry() = checkModel { model, p ->
        model.restore(words, ""); advanceUntilIdle()
        val before = p.secrets.toMap()
        model.addWallet(); model.restore(words, ""); advanceUntilIdle()
        assertEquals(1, model.state.value.wallets.size)
        assertEquals(1, p.saveCalls)
        assertEquals(before, p.secrets)
        assertTrue(model.state.value.message!!.contains("already added"))
        model.watch(model.state.value.zpub!!)
        assertEquals(1, model.state.value.wallets.size)
    }

    @Test fun switchingInvalidatesDelayedUnlockAndResetsSendReview() = checkModel { model, p ->
        model.restore(words, "first"); advanceUntilIdle()
        val first = model.state.value.walletId!!
        model.addWallet(); model.restore(words, "second"); advanceUntilIdle()
        val second = model.state.value.walletId!!
        model.selectWallet(first); advanceUntilIdle()
        model.revealWords()
        val oldUnlock = p.unlock!!
        model.selectWallet(second); advanceUntilIdle()
        oldUnlock(encodeSecret(words, "first"), null)
        assertEquals(second, model.state.value.walletId)
        assertIs<Screen.Home>(model.state.value.screen)
        assertIs<SendPhase.Editing>(model.state.value.send)
        assertTrue(model.state.value.rows.isEmpty())
        assertTrue(model.state.value.txs.isEmpty())
        assertNull(model.state.value.certChanged)
    }

    @Test fun removingWatchOnlyNeverDeletesHotSecretsAndRemovingOneHotKeepsOthers() = checkModel { model, p ->
        model.restore(words, "first"); advanceUntilIdle()
        val first = model.state.value.walletId!!
        model.addWallet(); model.restore(words, "second"); advanceUntilIdle()
        val second = model.state.value.walletId!!
        model.addWallet(); model.watch(Signer.accountZpub(words, "watch")); advanceUntilIdle()
        assertEquals(WalletKind.WATCH, model.state.value.kind)
        model.deleteWallet(); advanceUntilIdle()
        assertTrue(p.deletedIds.isEmpty())
        assertEquals(setOf(first, second), p.secrets.keys)
        model.selectWallet(second); advanceUntilIdle(); model.deleteWallet(); advanceUntilIdle()
        assertEquals(listOf(second), p.deletedIds)
        assertEquals(setOf(first), p.secrets.keys)
        assertEquals(first, model.state.value.walletId)
    }

    @Test fun legacyMigrationKeepsKeychainSlotAndAddressProgressWithoutUnlocking() = checkModel { model, p ->
        model.close(); advanceUntilIdle()
        val zpub = Signer.accountZpub(words, "")
        p.values["wallet.kind"] = "HOT"; p.values["wallet.zpub"] = zpub
        p.secrets[WalletCatalog.LEGACY_ID] = encodeSecret(words, "")
        AddressBook(p.prefs).reserve(zpub, 0, 0)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val migrated = WalletModel(p, dispatcher, dispatcher)
        try {
            advanceUntilIdle()
            assertEquals(WalletCatalog.LEGACY_ID, migrated.state.value.walletId)
            assertEquals(zpub, migrated.state.value.zpub)
            assertEquals(0, p.loadCalls); assertEquals(0, p.saveCalls)
            migrated.go(Screen.Receive)
            assertTrue(migrated.receiveAddress()!!.second.endsWith("/0/1"))
            migrated.revealWords()
            assertEquals(WalletCatalog.LEGACY_ID, p.loadedId)
        } finally { migrated.close(); advanceUntilIdle() }
    }

    @Test fun savedSeedWithInterruptedMetadataCommitIsRecoveredOnRestart() = checkModel { model, p ->
        p.failCatalogWriteAt = 2
        model.restore(words, "recovery"); advanceUntilIdle()
        assertEquals(1, p.secrets.size)
        assertNotNull(WalletCatalog(p.prefs).value.pending)
        assertTrue(model.state.value.message!!.contains("saved safely"))
        model.close(); advanceUntilIdle()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restarted = WalletModel(p, dispatcher, dispatcher)
        try {
            advanceUntilIdle()
            assertEquals(1, restarted.state.value.wallets.size)
            assertEquals(Signer.accountZpub(words, "recovery"), restarted.state.value.zpub)
            assertNull(WalletCatalog(p.prefs).value.pending)
            assertEquals(0, p.loadCalls)
        } finally { restarted.close(); advanceUntilIdle() }
    }

    @Test fun corruptCatalogCannotBeOverwrittenByNewWalletSetup() = checkModel { model, p ->
        model.close(); advanceUntilIdle()
        p.values[WalletCatalog.KEY] = "broken data"
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restarted = WalletModel(p, dispatcher, dispatcher)
        try {
            restarted.restore(words, ""); restarted.watch(Signer.accountZpub(words, "watch"))
            advanceUntilIdle()
            assertEquals("broken data", p.values[WalletCatalog.KEY])
            assertEquals(0, p.saveCalls)
            assertTrue(restarted.state.value.wallets.isEmpty())
        } finally { restarted.close(); advanceUntilIdle() }
    }

    @Test fun pendingPaymentBelongsToItsWalletAndBlocksOnlyThatWalletsRemoval() = checkModel { model, p ->
        model.restore(words, "first"); advanceUntilIdle()
        val first = model.state.value.wallets.single()
        p.values["pending.${first.zpub}"] = "unreadable pending fixture"
        model.addWallet(); model.restore(words, "second"); advanceUntilIdle()
        val second = model.state.value.walletId!!
        model.go(Screen.Send); advanceUntilIdle()
        assertIs<SendPhase.Editing>(model.state.value.send)
        model.selectWallet(first.id); advanceUntilIdle(); model.go(Screen.Send)
        assertIs<SendPhase.Pending>(model.state.value.send)
        model.deleteWallet()
        assertEquals(2, model.state.value.wallets.size)
        assertTrue(p.deletedIds.isEmpty())
        model.selectWallet(second); advanceUntilIdle()
        assertIs<SendPhase.Editing>(model.state.value.send)
        assertNotNull(p.values["pending.${first.zpub}"])
    }

    @Test fun backgroundBeforeDerivationCompletesDoesNotAddOrOverwriteWallet() = checkModel { model, p ->
        model.restore(words, "first")
        model.onBackground()
        advanceUntilIdle()
        assertEquals(0, p.saveCalls)
        assertTrue(model.state.value.wallets.isEmpty())
        assertIs<Screen.Welcome>(model.state.value.screen)
    }

    @Test fun publicKeyMetadataAliasesCannotDuplicateOneWallet() = checkModel { model, p ->
        model.restore(words, ""); advanceUntilIdle()
        val key = model.state.value.zpub!!
        val bytes = Base58.decodeChecked(key)
        bytes[5] = (bytes[5].toInt() xor 1).toByte()
        val alias = Base58.encodeChecked(bytes)
        assertNotEquals(key, alias)
        assertEquals(WalletCatalog.idFor(key), WalletCatalog.idFor(alias))
        model.addWallet(); model.watch(alias); advanceUntilIdle()
        assertEquals(1, model.state.value.wallets.size)
        assertEquals(1, p.secrets.size)
        assertTrue(model.state.value.message!!.contains("already added"))
    }

    @Test fun temporaryKeychainFailureDoesNotDiscardInterruptedSetup() = checkModel { model, p ->
        p.failCatalogWriteAt = 2
        model.restore(words, "recovery"); advanceUntilIdle()
        val before = p.values[WalletCatalog.KEY]
        model.close(); advanceUntilIdle()
        p.probeError = true
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restarted = WalletModel(p, dispatcher, dispatcher)
        try {
            advanceUntilIdle()
            assertEquals(before, p.values[WalletCatalog.KEY])
            assertEquals(1, p.secrets.size)
            assertNotNull(WalletCatalog(p.prefs).value.pending)
            assertTrue(restarted.state.value.message!!.contains("Keychain"))
        } finally { restarted.close(); advanceUntilIdle() }
    }

    @Test fun privacyPreferencePersistsAcrossWalletSwitchAndRestart() = checkModel { model, p ->
        model.restore(words, ""); advanceUntilIdle()
        val first = model.state.value.walletId!!
        model.toggleHideAmounts()
        assertTrue(model.state.value.hideAmounts)
        model.restore(words, "second"); advanceUntilIdle()
        assertTrue(model.state.value.hideAmounts)
        model.selectWallet(first); advanceUntilIdle()
        assertTrue(model.state.value.hideAmounts)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restarted = WalletModel(p, dispatcher, dispatcher)
        try { assertTrue(restarted.state.value.hideAmounts) } finally { restarted.close() }
        model.toggleHideAmounts()
        assertFalse(model.state.value.hideAmounts)
    }

    @Test fun noteEditFromPreviousWalletIsRejected() = checkModel { model, p ->
        model.setUpDemo("history"); advanceUntilIdle()
        val oldId = model.state.value.walletId!!
        val txid = model.state.value.txs.first().txid
        assertTrue(model.saveTransactionNote(oldId, txid, "Changed", "Local"))
        model.restore(words, "another"); advanceUntilIdle()
        assertFalse(model.saveTransactionNote(oldId, txid, "Wrong wallet", ""))
        assertTrue(model.state.value.notes.isEmpty())
    }

    @Test fun exportUsesOneWalletAndRequiresExplicitSuccessfulSnapshot() = checkModel { model, p ->
        model.exportHistory()
        assertEquals(0, p.exportCalls)
        model.setUpDemo("history"); advanceUntilIdle()
        model.toggleHideAmounts()
        model.exportHistory(); model.exportHistory()
        assertEquals(1, p.exportCalls)
        assertTrue(p.exported!!.contains("Mining payout"))
        assertTrue(p.exported!!.contains("250000"))
        assertFalse(p.exported!!.contains(model.state.value.zpub!!))
        assertFalse(p.exported!!.contains(words.joinToString(" ")))
        p.shareCompletion!!(null)
        model.restore(words, "separate"); advanceUntilIdle()
        assertNull(model.state.value.lastSyncedAt)
        model.exportHistory()
        assertEquals(1, p.exportCalls)
    }

    @Test fun successfulSyncSetsTimeFailuresKeepItAndServerSwitchClearsIt() = checkModel { model, p ->
        p.reply = { q -> when (q["method"]!!.jsonPrimitive.content) {
            "server.version" -> buildJsonArray { add("test"); add("1.4") }
            "blockchain.headers.subscribe" -> buildJsonObject { put("height", 976086); put("hex", "000000a0700c8b9690014507c6c277900d743ac3ed6d814f90772d060000000000000000670caeac2404c80798669b242623ea7310be8f47166168e9734baab9dec80446b2c2c66a2ad80019be9815dad0f4b1da0000000000000000b10cf8578500000000000000b2c2c66a7501000000000000000000000000000000000000d6e40e000000000000000000000000000000000000000000000000000000000000000000") }
            "blockchain.scripthash.get_history" -> JsonArray(emptyList())
            else -> error("Unexpected request")
        } }
        model.restore(words, ""); advanceUntilIdle()
        assertIs<ScanPhase.Done>(model.state.value.scan)
        assertEquals(p.now, model.state.value.lastSyncedAt)
        p.now += 60000
        model.refresh(); advanceUntilIdle()
        assertEquals(p.now, model.state.value.lastSyncedAt)
        p.reply = null
        p.now += 60000
        model.refresh(); advanceUntilIdle()
        assertEquals(p.now - 60000, model.state.value.lastSyncedAt)
        assertIs<ScanPhase.Error>(model.state.value.scan)
        model.setServer("offline.invalid", 50002); advanceUntilIdle()
        assertNull(model.state.value.lastSyncedAt)
    }

    @Test fun frozenReviewCannotPromptUnlockAndStorageChangesCannotSlipPastUnlock() = checkModel { model, p ->
        model.setUpDemo("review"); advanceUntilIdle()
        val zpub = model.state.value.zpub!!
        val id = (model.state.value.send as SendPhase.Review).draft.inputs.single().coinId()
        FrozenCoins(p.prefs).set(zpub, id, true)
        model.confirmSend()
        assertIs<SendPhase.Failed>(model.state.value.send)
        assertEquals(0, p.loadCalls)
        FrozenCoins(p.prefs).set(zpub, id, false)
        model.setUpDemo("review"); advanceUntilIdle()
        model.confirmSend()
        assertEquals(1, p.loadCalls)
        FrozenCoins(p.prefs).set(zpub, id, true)
        p.unlock!!(encodeSecret(words, ""), null)
        advanceUntilIdle()
        assertIs<SendPhase.Failed>(model.state.value.send)
        assertTrue(p.sent.isEmpty())
        assertTrue(p.values.keys.none { it.startsWith("pending.") })
    }

    @Test fun selectionFreezingMaxAndBackgroundUseTheSamePolicy() = checkModel { model, p ->
        model.setUpDemo("coins"); advanceUntilIdle()
        val s = model.state.value
        val first = s.spendable!!.first()
        assertEquals(listOf(first), model.availableCoins())
        val before = model.maxSendable(1.0)
        assertTrue(before in 1 until first.value)
        model.freezeCoin(first.coinId(), true)
        assertEquals(emptySet(), model.state.value.selectedCoins)
        assertEquals(0, model.maxSendable(1.0))
        model.setManualCoins(false)
        assertTrue(model.availableCoins().isEmpty())
        model.freezeCoin(first.coinId(), false)
        assertEquals(before, model.maxSendable(1.0))
        model.onBackground()
        assertNull(model.state.value.selectedCoins)
        assertNull(model.state.value.spendable)
        assertEquals(1, FrozenCoins(p.prefs).read(s.zpub!!).size)
    }

    @Test fun corruptFreezePolicyBlocksReviewWithoutOverwritingIt() = checkModel { model, p ->
        model.setUpDemo("coins"); advanceUntilIdle()
        val key = "frozen.v1." + WalletCatalog.idFor(model.state.value.zpub!!)
        p.values[key] = "{broken"
        assertEquals(0, model.maxSendable(1.0))
        model.prepareSend("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", 10000, 1.0, false)
        advanceUntilIdle()
        assertIs<SendPhase.Failed>(model.state.value.send)
        assertEquals("{broken", p.values[key])
        assertEquals(0, p.loadCalls)
    }

    @Test fun backupCheckRequiresAuthenticationAndMatchesExactPassphrase() = checkModel { model, p ->
        model.restore(words, "Exact Case "); advanceUntilIdle()
        model.startBackupCheck()
        assertEquals(1, p.loadCalls)
        assertTrue(model.state.value.screen != Screen.BackupCheck)
        p.unlock!!(null, "cancelled")
        assertTrue(model.state.value.screen != Screen.BackupCheck)
        model.startBackupCheck()
        p.unlock!!(encodeSecret(words, "Exact Case "), null)
        assertEquals(Screen.BackupCheck, model.state.value.screen)
        model.verifyBackup(words, "Exact Case"); advanceUntilIdle()
        assertNull(model.state.value.backupCheckedAt)
        assertEquals(Screen.BackupCheck, model.state.value.screen)
        model.verifyBackup(words, "Exact Case "); advanceUntilIdle()
        assertEquals(p.now, model.state.value.backupCheckedAt)
        assertEquals(Screen.Settings, model.state.value.screen)
        assertTrue(p.values.values.none { it.contains(words.joinToString(" ")) || it.contains("Exact Case") })
        val first = model.state.value.walletId!!
        model.addWallet(); model.restore(words, "second"); advanceUntilIdle()
        assertNull(model.state.value.backupCheckedAt)
        model.selectWallet(first); advanceUntilIdle()
        assertEquals(p.now, model.state.value.backupCheckedAt)
    }

    @Test fun backgroundCancelsBackupVerificationAndLateUnlock() = checkModel { model, p ->
        model.restore(words, ""); advanceUntilIdle()
        model.startBackupCheck()
        val late = p.unlock!!
        model.onBackground()
        late(encodeSecret(words, ""), null)
        assertEquals(Screen.Home, model.state.value.screen)
        model.startBackupCheck()
        p.unlock!!(encodeSecret(words, ""), null)
        model.verifyBackup(words, "")
        model.onBackground(); advanceUntilIdle()
        assertNull(model.state.value.backupCheckedAt)
        assertFalse(model.state.value.checkingBackup)
        assertTrue(p.values.keys.none { it.startsWith("backup.checked.") })
    }

    @Test fun recipientsAndUnitsSurviveSwitchAndStaleRecipientEditIsRejected() = checkModel { model, p ->
        model.restore(words, ""); advanceUntilIdle()
        val first = model.state.value.walletId!!
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        assertTrue(model.saveRecipient(first, address, "Alice"))
        model.setAmountUnit(AmountUnit.XBT)
        model.addWallet(); model.restore(words, "second"); advanceUntilIdle()
        assertEquals(AmountUnit.XBT, model.state.value.amountUnit)
        assertTrue(model.state.value.recipients.isEmpty())
        assertFalse(model.saveRecipient(first, address, "Wrong wallet"))
        model.selectWallet(first); advanceUntilIdle()
        assertEquals("Alice", model.state.value.recipients.single().name)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restarted = WalletModel(p, dispatcher, dispatcher)
        try {
            advanceUntilIdle()
            assertEquals(AmountUnit.XBT, restarted.state.value.amountUnit)
            assertEquals("Alice", restarted.state.value.recipients.single().name)
        } finally { restarted.close(); advanceUntilIdle() }
    }

}
