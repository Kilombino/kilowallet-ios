package com.kilombino.kilowallet.app

import com.kilombino.kilowallet.core.wallet.*
import kotlin.test.*

class CoinControlTest {
    private class Memory : Prefs {
        val data = mutableMapOf<String, String>()
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
    }
    private val a = SpendableUtxo("ab".repeat(32), 0, 200_000, 0, 0, 970000)
    private val b = SpendableUtxo("cd".repeat(32), 1, 100_000, 0, 1, 970000)
    private val to = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val words = List(11) { "abandon" } + "about"

    @Test fun frozenCoinsExcludedFromAutomaticAndMaxWithoutMutatingBalance() {
        val coins = listOf(a, b)
        val allowed = eligibleCoins(coins, setOf(a.coinId()), null)
        assertEquals(listOf(b), allowed)
        val change = SendPlanner.changeScript(Signer.accountZpub(words, ""), 0)
        assertEquals(listOf(b), SendPlanner.plan(to, 50_000, 1.0, allowed, change).inputs)
        assertEquals(listOf(b), SendPlanner.plan(to, 0, 1.0, allowed, change, sendAll = true).inputs)
        assertTrue(SendPlanner.maxSendable(1.0, allowed) < b.value)
        assertEquals(300_000, coins.sumOf { it.value })
        assertTrue(eligibleCoins(coins, setOf(a.coinId(), b.coinId()), null).isEmpty())
    }

    @Test fun manualSelectionUsesExactlySelectedInputsAndNeverFallsBack() {
        val change = SendPlanner.changeScript(Signer.accountZpub(words, ""), 0)
        val selected = eligibleCoins(listOf(a,b), emptySet(), setOf(a.coinId(),b.coinId()))
        assertEquals(2, SendPlanner.plan(to, 50_000, 1.0, selected, change, useAllInputs = true).inputs.size)
        val one = eligibleCoins(listOf(a,b), emptySet(), setOf(b.coinId()))
        assertFails { SendPlanner.plan(to, 150_000, 1.0, one, change, useAllInputs = true) }
        assertFails { eligibleCoins(listOf(a,b), emptySet(), emptySet()) }
        assertFails { eligibleCoins(listOf(a), emptySet(), setOf(b.coinId())) }
        assertFails { eligibleCoins(listOf(a,b), setOf(a.coinId()), setOf(a.coinId())) }
        assertFails { eligibleCoins(listOf(a,a), emptySet(), null) }
    }

    @Test fun freezePersistsByWalletAndCorruptionFailsClosed() {
        val p = Memory()
        val x = Signer.accountZpub(words, "")
        val y = Signer.accountZpub(words, "another")
        FrozenCoins(p).set(x, a.coinId(), true)
        assertEquals(setOf(a.coinId()), FrozenCoins(p).read(x))
        assertTrue(FrozenCoins(p).read(y).isEmpty())
        FrozenCoins(p).set(y, b.coinId(), true)
        FrozenCoins(p).set(x, a.coinId(), false)
        assertTrue(FrozenCoins(p).read(x).isEmpty())
        assertEquals(setOf(b.coinId()), FrozenCoins(p).read(y))
        val key = "frozen.v1." + WalletCatalog.idFor(x)
        p.data[key] = "{broken"
        assertFails { FrozenCoins(p).read(x) }
        assertFails { FrozenCoins(p).set(x, a.coinId(), false) }
        assertEquals("{broken", p.data[key])
        assertFails { FrozenCoins(p).set(y, "bad:0", true) }
    }
}
