package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import com.kilombino.kilowallet.core.chain.*
import com.kilombino.kilowallet.core.wallet.Signer
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
        override val transports = TransportFactory { _, _, _, _ -> connections++; throw ElectrumException("Offline test") }
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
        val model = WalletModel(platform, dispatcher)
        try { block(model, platform) }
        finally { model.onBackground(); Dispatchers.resetMain() }
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
}
