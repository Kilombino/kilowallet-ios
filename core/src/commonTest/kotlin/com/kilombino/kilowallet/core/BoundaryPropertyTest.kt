package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.crypto.*
import com.kilombino.kilowallet.core.wallet.*
import kotlin.random.Random
import kotlin.test.*

/** Deterministic synthetic data only. No network, funded inputs or user secrets. */
class BoundaryPropertyTest {
    private val destination = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val change = byteArrayOf(0, 20) + ByteArray(20) { 17 }
    private fun coin(i: Int, value: Long) =
        SpendableUtxo(i.toString(16).padStart(64, '0'), 0, value, 0, i, 100)

    @Test fun paymentAmountsPreserveEverySatoshi() {
        val random = Random(4217)
        val amounts = listOf(0L, 1L, 99_999_999L, 100_000_000L, 2_100_000_000_000_000L) +
            List(1000) { random.nextLong(0, 2_100_000_000_000_001L) }
        for (amount in amounts) {
            val decimal = "${amount / 100_000_000}." + (amount % 100_000_000).toString().padStart(8, '0')
            assertEquals(amount, PaymentRequest.toSats(decimal))
            assertEquals(destination to amount, PaymentRequest.parse("bitcoin:$destination?chain=xbt&amount=$decimal"))
        }
    }

    @Test fun ambiguousAndHostilePaymentRequestsAreRejected() {
        for (query in listOf("chain=xbt&CHAIN=btc", "amount=1&AMOUNT=2", "%61mount=1",
            "req-unknown=1", "amount=1e-8", "amount=-1", "amount=+1", "amount=1,000", "amount=Infinity")) {
            assertFails { PaymentRequest.parse("bitcoin:$destination?$query") }
        }
        assertFails { PaymentRequest.parse("bitcoin://$destination") }
        assertFails { PaymentRequest.parse("lightning:example") }
        assertFails { PaymentRequest.parse("a".repeat(4097)) }
    }

    @Test fun randomizedPlansConserveValueAndNeverEmitDustChange() {
        val random = Random(7351)
        repeat(500) {
            val coins = List(random.nextInt(1, 12)) { coin(it, random.nextLong(20_000, 500_000)) }
            val amount = random.nextLong(1000, coins.sumOf { it.value } / 2)
            val rate = listOf(0.1, 1.0, 2.5, 10.0)[it % 4]
            val draft = SendPlanner.plan(destination, amount, rate, coins, change)
            assertEquals(draft.inputs.sumOf { it.value }, draft.outputs.sumOf { it.value } + draft.fee)
            assertEquals(amount, draft.outputs.first().value)
            assertEquals(draft.change, draft.outputs.drop(1).sumOf { it.value })
            assertTrue(draft.change == 0L || draft.change > SendPlanner.DUST_SATS)
            assertTrue(draft.fee >= SendPlanner.estimateFee(draft.inputs.size, draft.outputs.map { it.scriptPubKey }, rate))
        }
    }

    @Test fun sendAllConservesValueAtInputCountBoundary() {
        for (count in listOf(1, 2, 252, 253, 254)) {
            val coins = List(count) { coin(it, 10_000) }
            val draft = SendPlanner.plan(destination, 0, 1.0, coins, change, sendAll = true)
            assertEquals(count, draft.inputs.size)
            assertEquals(1, draft.outputs.size)
            assertEquals(coins.sumOf { it.value }, draft.amount + draft.fee)
            assertEquals(0L, draft.change)
        }
    }

    @Test fun checksumMutationsAndWrongNetworkAddressesAreRejected() {
        for (version in 0..1) {
            val address = Bech32.encodeSegwit("bc", version, ByteArray(if (version == 0) 20 else 32) { it.toByte() })
            assertTrue(Address.isValid(address))
            assertTrue(Address.isValid(address.uppercase()))
            for (i in 3 until address.length) {
                val replacement = if (address[i] == 'q') 'p' else 'q'
                assertFalse(Address.isValid(address.replaceRange(i, i + 1, replacement.toString())))
            }
            assertFalse(Address.isValid("B" + address.drop(1)))
            assertFalse(Address.isValid(Bech32.encodeSegwit("tb", version, ByteArray(32))))
        }
    }

    @Test fun futureWitnessDestinationsNeverReachAPlan() {
        for (version in 2..16) {
            val address = Bech32.encodeSegwit("bc", version, ByteArray(32) { 1 })
            assertFailsWith<IllegalArgumentException> {
                SendPlanner.plan(address, 1000, 1.0, listOf(coin(0, 100_000)), change)
            }
        }
        val malformedTaproot = Bech32.encodeSegwit("bc", 1, ByteArray(20))
        assertFailsWith<IllegalArgumentException> {
            SendPlanner.plan(malformedTaproot, 1000, 1.0, listOf(coin(0, 100_000)), change)
        }
    }

    @Test fun recoverySupportsAllStandardWordCountsAndUnicodeNormalization() {
        for (bytes in listOf(16, 20, 24, 28, 32)) {
            val words = Bip39.fromEntropy(ByteArray(bytes) { it.toByte() })
            assertTrue(Bip39.isValid(words))
            assertEquals(bytes * 3 / 4, words.size)
            assertFalse(Bip39.isValid(words.dropLast(1) + "notaword"))
        }
        val words = List(11) { "abandon" } + "about"
        assertContentEquals(Bip39.toSeed(words, "caf\u00e9"), Bip39.toSeed(words, "cafe\u0301"))
        assertFalse(Bip39.toSeed(words, "").contentEquals(Bip39.toSeed(words, " ")))
    }

    @Test fun parserHandlesSeededGarbageWithoutIndexOrAllocationExceptions() {
        val random = Random(893)
        repeat(2000) {
            val data = ByteArray(random.nextInt(10, 300)).also { random.nextBytes(it) }
            val hex = with(Hashes) { data.toHex() }
            try { TxParse.parse(hex) }
            catch (e: IllegalArgumentException) { /* expected malformed input */ }
        }
    }
}
