package com.kilombino.kilowallet.core

import com.kilombino.kilowallet.core.wallet.PaymentRequest
import kotlin.test.*

class ReceiveRequestTest {
    private val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    @Test fun receiveRequestsRoundTripWithoutRounding() {
        assertEquals(address, PaymentRequest.create(address))
        val values = listOf(1L, 99_999_999L, 100_000_000L, 120_000L, PaymentRequest.MAX_REQUEST_SATS) +
            (1L..1000L).map { it * 7919 }
        values.forEach { sats ->
            val request = PaymentRequest.create(address, sats)
            assertTrue(request.contains("?chain=xbt&amount="))
            assertEquals(address to sats, PaymentRequest.parse(request))
        }
        assertEquals("bitcoin:$address?chain=xbt&amount=0.00000001", PaymentRequest.create(address, 1))
    }
    @Test fun invalidReceiveRequestNeverProducesAUri() {
        listOf(0L, -1L, Long.MAX_VALUE, PaymentRequest.MAX_REQUEST_SATS + 1).forEach {
            assertFails { PaymentRequest.create(address, it) }
        }
        listOf("wrong", "$address?amount=9", " $address", "").forEach {
            assertFails { PaymentRequest.create(it, 100) }
        }
    }
}
