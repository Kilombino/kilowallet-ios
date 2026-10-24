package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.UiState
import com.kilombino.kilowallet.core.market.Fiat

/**
 * An amount in sats and, next to it, the same in the chosen currency: type in either and the other
 * follows (Android 1.2.3, `FiatAmountField`). Without prices (off, or none yet) only sats.
 */
@Composable
fun AmountFields(sats: String, onSats: (String) -> Unit, state: UiState) {
    val per = state.perCoin
    val code = state.fiat
    var text by remember { mutableStateOf("") }
    var emitted by remember { mutableStateOf<String?>(null) }
    // Sats typed in their own box (or set from a QR code) take over again.
    LaunchedEffect(sats) { if (sats != emitted) emitted = null }
    val shown = if (emitted != null) text
        else sats.toLongOrNull()?.let { s -> per?.let { Fiat.plain(it * s / 1e8, code) } } ?: ""
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(sats, { onSats(it.filter { c -> c.isDigit() }) }, "sats", Modifier.weight(1f), keyboard = KeyboardType.Number)
        if (code != Fiat.OFF) Field(shown, { t ->
            text = t.filter { it.isDigit() || it == '.' || it == ',' }
            val s = Fiat.toSats(text, per)?.toString() ?: ""
            emitted = s
            onSats(s)
        }, if (per == null) "≈ $code (no price yet)" else "≈ $code", Modifier.weight(1f), keyboard = KeyboardType.Decimal, enabled = per != null)
    }
}
