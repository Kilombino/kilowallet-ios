package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.kilombino.kilowallet.app.SendPhase
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
        SendPhase.Preparing -> Busy(model, "Choosing coins and checking each one against its transaction…")
        is SendPhase.Review -> Review(model, p, platform, state.walletName)
        SendPhase.Signing -> Busy(model, "Signing (unified sighash) and broadcasting…", allowBack = false)
        is SendPhase.Sent -> Sent(model, p.txid)
        is SendPhase.Pending -> ScreenColumn {
            BackLink { model.back() }
            Text("Delivery unconfirmed", style = MaterialTheme.typography.titleLarge, color = Warn)
            Panel(accent = Warn) {
                Text(p.message, style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Text(p.txid, style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
            Explain("This payment may already have reached the network. Resending the saved transaction keeps the same transaction ID and cannot create a second payment.")
            PrimaryButton("Check or resend same transaction", Modifier.fillMaxWidth()) { model.retryPendingSend() }
        }
        is SendPhase.Failed -> ScreenColumn {
            BackLink { model.back() }
            Text("Not sent", style = MaterialTheme.typography.titleLarge, color = Bad)
            Panel(accent = Bad) { Text(p.message, style = MaterialTheme.typography.bodyMedium, color = TextMain) }
            PrimaryButton("Edit the send", Modifier.fillMaxWidth()) { model.editSend() }
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
    var paymentError by remember { mutableStateOf<String?>(null) }
    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var rate by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    val rateValue = rate.replace(',', '.').toDoubleOrNull() ?: state.suggestedRate ?: 1.0
    val sats = amount.filter { it.isDigit() }.toLongOrNull() ?: 0L
    val addrOk = to.isNotBlank() && Address.isValid(to)

    fun takePayment(text: String) {
        val parsed = runCatching { parsePaymentText(text) }.getOrElse { paymentError = it.message ?: "Invalid payment request"; return }
        paymentError = null
        val (a, s) = parsed
        to = a
        if (s != null) { amount = s.toString(); all = false }
    }

    ScreenColumn {
        BackLink { model.back() }
        Text("Send ${Chain.COIN}", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("From ${state.walletName}", color = TextSoft)
        Panel {
            SectionLabel("To")
            paymentError?.let { Text(it, color = Bad) }
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
                Text(if (state.hideAmounts) "Everything: •••• sats" else "Everything: ${formatSats(max)} sats", style = MaterialTheme.typography.titleMedium, color = Purple)
                Explain("All your coins minus the fee; no change output.")
            } else {
                Field(amount, { amount = it.filter { c -> c.isDigit() } }, "sats", keyboard = KeyboardType.Number)
                if (sats > 0) Explain("= ${formatBtc(sats)} ${Chain.COIN}")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(all, { all = it }, colors = CheckboxDefaults.colors(checkedColor = Purple, uncheckedColor = TextFaint))
                Text("send everything (MAX)", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
            }
            val available = state.spendable?.sumOf { it.value }
            Explain(if (available == null) "loading your coins…" else if (state.hideAmounts) "spendable: •••• sats" else "spendable: ${formatSats(available)} sats in ${state.spendable.size} coins")
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
private fun Review(model: WalletModel, p: SendPhase.Review, platform: AppPlatform, walletName: String) {
    val d = p.draft
    var ack by remember(d) { mutableStateOf(false) }
    var showDetails by remember(d) { mutableStateOf(false) }
    ScreenColumn {
        BackLink("← edit") { model.editSend() }
        Text("Review payment", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("From $walletName", color = TextSoft)
        Text("${formatSats(d.amount)} sats", style = MaterialTheme.typography.headlineMedium, color = Purple)
        SectionLabel("XBT · BLAKE2b", color = TextSoft)
        Panel {
            SectionLabel("Recipient")
            Text(chunked(d.to), style = MaterialTheme.typography.titleMedium, color = TextMain)
        }
        Panel {
            KeyValue("Amount", "${formatSats(d.amount)} sats")
            KeyValue("Network fee", "${formatSats(d.fee)} sats")
            KeyValue("Total", "${formatSats(d.totalSent)} sats", Purple)
            KeyValue("Change back to you", if (d.change > 0) "${formatSats(d.change)} sats" else "none")
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
        Explain("Confirm the recipient uses the XBT BLAKE2b network.", color = TextSoft)
        LinkText(if (showDetails) "− Technical details" else "+ Technical details", modifier = Modifier.fillMaxWidth()) {
            showDetails = !showDetails
        }
        if (showDetails) Panel {
            KeyValue("Fee rate", "${fmtRate(d.effectiveRate)} sat/vB")
            KeyValue("Coins spent", "${d.inputs.size}")
            Explain("Address format alone cannot distinguish XBT BLAKE2b from Bitcoin SHA-256.")
            Explain("Signed with unified sighash (0x21) for the ${Chain.DISPLAY} chain only. " +
                "This transaction cannot be replayed on the SHA-256 chain. It signals replace-by-fee.")
        }
        PrimaryButton("Send with ${platform.unlockName}", Modifier.fillMaxWidth(), enabled = p.concern == null || ack) {
            model.confirmSend()
        }
    }
}

@Composable
private fun Sent(model: WalletModel, txid: String) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    ScreenColumn {
        Text("Sent", style = MaterialTheme.typography.headlineMedium, color = Good)
        Panel(accent = Good) {
            SectionLabel("Transaction id", Good)
            Text(txid, style = MaterialTheme.typography.bodyMedium, color = TextMain)
            LinkText("copy txid", color = Purple) { clipboard.setText(AnnotatedString(txid)) }
            LinkText("open on ${Chain.EXPLORER.removePrefix("https://")}", color = Purple) {
                runCatching { uri.openUri("${Chain.EXPLORER}/tx/$txid") }
            }
        }
        Explain("The server accepted it. It shows as pending until a block includes it.")
        PrimaryButton("Done", Modifier.fillMaxWidth()) { model.back() }
    }
}
