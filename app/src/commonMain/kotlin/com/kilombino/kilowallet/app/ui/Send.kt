package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.kilombino.kilowallet.app.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.Screen
import com.kilombino.kilowallet.app.SendPhase
import com.kilombino.kilowallet.core.market.Fiat
import com.kilombino.kilowallet.app.UiState
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.parsePaymentText
import com.kilombino.kilowallet.core.chain.Chain
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.wallet.formatBtc
import com.kilombino.kilowallet.core.wallet.formatSats
import kotlin.math.roundToLong

@Composable
fun SendScreen(model: WalletModel, state: UiState, platform: AppPlatform) {
    when (val p = state.send) {
        SendPhase.Editing -> SendForm(model, state, platform)
        else -> SendSteps(model, state, platform)
    }
}

/**
 * Speed up or cancel an unconfirmed send (RBF, Android 1.2.5): a fee rate, then the same review,
 * unlock and broadcast as a normal send.
 */
@Composable
fun ReplaceScreen(model: WalletModel, state: UiState, platform: AppPlatform, txid: String, cancel: Boolean) {
    if (state.send != SendPhase.Editing) { SendSteps(model, state, platform); return }
    var rate by remember { mutableStateOf("") }
    val rateValue = rate.replace(',', '.').toDoubleOrNull() ?: state.suggestedRate?.coerceAtLeast(3.0) ?: 3.0
    ScreenColumn {
        BackLink { model.back() }
        Text(if (cancel) "Cancel ${txid.take(8)}…" else "Speed up ${txid.take(8)}…", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Panel(accent = if (cancel) Warn else Purple) {
            Text(if (cancel) "Replaces the payment with one that sends everything back to a fresh address of yours. " +
                "The recipients get nothing, unless the original confirms first: then this one is refused."
                else "Replaces the stuck send with the same payment and a higher fee, taken from your change.",
                style = MaterialTheme.typography.bodyMedium, color = TextMain)
        }
        Panel {
            SectionLabel("Fee rate")
            Field(rate, { rate = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                "sat/vB (default ${fmtRate(rateValue)})", keyboard = KeyboardType.Decimal)
            Explain(if (cancel) "Fee rate for the cancellation. It must beat the old fee by at least 1 sat/vB of its size, " +
                "and the higher it is, the likelier it gets mined before the original." else
                "New fee rate. It must beat the old fee by at least 1 sat/vB of the transaction's size; " +
                "most pools only mine from 1 sat/vB.")
        }
        PrimaryButton("Review", Modifier.fillMaxWidth(), color = if (cancel) Warn else Purple) {
            model.prepareReplace(txid, cancel, rateValue)
        }
    }
}

/** Everything after the form: preparing, the review, signing, and the outcome. */
@Composable
private fun SendSteps(model: WalletModel, state: UiState, platform: AppPlatform) {
    when (val p = state.send) {
        SendPhase.Editing -> Unit
        SendPhase.Preparing -> Busy(model, "Choosing coins and checking each one against its transaction…")
        is SendPhase.Review -> Review(model, p, platform, state)
        SendPhase.Signing -> Busy(model, "Signing (unified sighash) and broadcasting…", allowBack = false)
        is SendPhase.Sent -> Sent(model, p)
        is SendPhase.Failed -> ScreenColumn {
            BackLink { model.back() }
            Text("Not sent", style = MaterialTheme.typography.titleLarge, color = Bad)
            Panel(accent = Bad) { Text(p.message, style = MaterialTheme.typography.bodyMedium, color = TextMain) }
            PrimaryButton(if (state.screen is Screen.Replace) "Back" else "Edit the send", Modifier.fillMaxWidth()) { model.editSend() }
        }
    }
}

@Composable
private fun Busy(model: WalletModel, text: String, allowBack: Boolean = true) {
    ScreenColumn {
        if (allowBack) BackLink { model.back() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Purple)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSoft)
        }
    }
}


@Composable
private fun SendForm(model: WalletModel, state: UiState, platform: AppPlatform) {
    val clipboard = LocalClipboardManager.current
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    val rateValue = rate.replace(',', '.').toDoubleOrNull() ?: state.suggestedRate ?: 1.0
    val sats = amount.filter { it.isDigit() }.toLongOrNull() ?: 0L
    val addrOk = to.isNotBlank() && Address.isValid(to)

    fun takePayment(text: String) {
        val (a, s) = parsePaymentText(text)
        to = a
        if (s != null) { amount = s.toString(); all = false }
    }

    ScreenColumn {
        BackLink { model.back() }
        Text("Send ${Chain.COIN}", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Panel {
            SectionLabel("To")
            Field(to, { to = it.trim() }, "bc1… / 1… / 3… address", singleLine = false, minLines = 2)
            if (to.isNotBlank() && !addrOk) Text("Not a valid address.", style = MaterialTheme.typography.bodySmall, color = Bad)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Paste", Modifier.weight(1f), color = TextMain) { clipboard.getText()?.text?.let { takePayment(it) } }
                if (platform.canScanQr) SecondaryButton("Scan QR", Modifier.weight(1f), color = TextMain) {
                    platform.scanQr { it?.let(::takePayment) }
                }
            }
        }
        Panel {
            SectionLabel("Amount")
            if (all) {
                val max = model.maxSendable(rateValue)
                Text("Everything: ${formatSats(max)} sats", style = MaterialTheme.typography.titleMedium, color = Purple)
                state.perCoin?.let { Explain("≈ " + Fiat.format(it * max / 1e8, state.fiat)) }
                Explain("All your coins minus the fee; no change output.")
            } else {
                AmountFields(amount, { amount = it }, state)
                if (sats > 0) Explain("= ${formatBtc(sats)} ${Chain.COIN}")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(all, { all = it }, colors = CheckboxDefaults.colors(checkedColor = Purple, uncheckedColor = TextFaint))
                Text("send everything (MAX)", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
            }
            val available = state.spendable?.sumOf { it.value }
            Explain(if (available == null) "loading your coins…" else "spendable: ${formatSats(available)} sats in ${state.spendable.size} coins")
        }
        Panel {
            SectionLabel("Fee rate")
            Field(rate, { rate = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                "sat/vB (default ${fmtRate(state.suggestedRate ?: 1.0)})", keyboard = KeyboardType.Decimal)
            Explain(state.suggestedRate?.let { "The server suggests ${fmtRate(it)} sat/vB for the next blocks." }
                ?: "No estimate from the server; 1 sat/vB is the usual minimum.")
        }
        PrimaryButton("Review", Modifier.fillMaxWidth(), enabled = addrOk && (all || sats > 0)) {
            model.prepareSend(to, sats, rateValue, all)
        }
    }
}

private fun fmtRate(r: Double): String {
    val t = (r * 10).roundToLong()
    return "${t / 10}.${t % 10}"
}

@Composable
private fun Review(model: WalletModel, p: SendPhase.Review, platform: AppPlatform, state: UiState) {
    val d = p.draft
    var ack by remember(d) { mutableStateOf(false) }
    ScreenColumn {
        BackLink("← edit") { model.editSend() }
        Text(when {
            d.cancels -> "Review the cancellation"
            d.replaces != null -> "Review the speed-up"
            else -> "Review the send"
        }, style = MaterialTheme.typography.titleLarge, color = TextMain)
        Panel {
            SectionLabel(if (d.cancels) "Back to you" else "To")
            Text(chunked(d.to), style = MaterialTheme.typography.titleMedium, color = TextMain)
            if (d.cancels) Explain("A fresh address of this wallet. The recipients get nothing.")
        }
        Panel {
            KeyValue("amount", "${formatSats(d.amount)} sats")
            state.perCoin?.let { KeyValue("≈", Fiat.format(it * d.amount / 1e8, state.fiat), TextSoft) }
            d.replaces?.let { KeyValue("replaces", shortTxid(it)) }
            if (d.replaces != null) KeyValue("old fee", "${formatSats(d.replacedFee)} sats")
            KeyValue("fee", "${formatSats(d.fee)} sats · ${fmtRate(d.effectiveRate)} sat/vB")
            if (!d.cancels) KeyValue("total", "${formatSats(d.totalSent)} sats", Purple)
            if (!d.cancels) KeyValue("change back to you", if (d.change > 0) "${formatSats(d.change)} sats" else "none")
            KeyValue("coins spent", "${d.inputs.size}")
        }
        p.concern?.let { c ->
            Panel(accent = Warn) {
                Text(c, style = MaterialTheme.typography.bodyMedium, color = Warn)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ack, { ack = it }, colors = CheckboxDefaults.colors(checkedColor = Warn, uncheckedColor = TextFaint))
                    Text("Yes, I want to pay this fee", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
                }
            }
        }
        Explain("Signed with the unified sighash (0x21): this transaction is valid on the ${Chain.DISPLAY} chain only and " +
            "cannot be replayed on the SHA-256 chain. It signals replace-by-fee.")
        PrimaryButton("Send with ${platform.unlockName}", Modifier.fillMaxWidth(), enabled = p.concern == null || ack) {
            model.confirmSend()
        }
    }
}

@Composable
private fun Sent(model: WalletModel, p: SendPhase.Sent) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val txid = p.txid
    ScreenColumn {
        Text(when {
            p.cancelled -> "Cancellation sent ✓"
            p.replaced -> "Replacement sent ✓"
            else -> "Sent"
        }, style = MaterialTheme.typography.headlineMedium, color = Good)
        Panel(accent = Good) {
            SectionLabel("Transaction id", Good)
            Text(txid, style = MaterialTheme.typography.bodyMedium, color = TextMain)
            LinkText("copy txid", color = Purple) { clipboard.setText(AnnotatedString(txid)) }
            LinkText("open on ${Chain.EXPLORER.removePrefix("https://")}", color = Purple) {
                runCatching { uri.openUri("${Chain.EXPLORER}/tx/$txid") }
            }
        }
        Explain(if (p.replaced) "The server accepted it in place of the earlier one, which can no longer confirm. " +
            "It shows as pending until a block includes it." else "The server accepted it. It shows as pending until a block includes it.")
        PrimaryButton("Done", Modifier.fillMaxWidth()) { model.back() }
    }
}
