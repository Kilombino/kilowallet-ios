package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kilombino.kilowallet.app.AmountUnit
import com.kilombino.kilowallet.app.coinId
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
    if (platform.demoScreen == "coins") {
        ScreenColumn { CoinControls(model, state, initiallyExpanded = true) }
        return
    }
    when (val p = state.send) {
        SendPhase.Editing -> SendForm(model, state, platform)
        SendPhase.Preparing -> Busy(model, "Choosing coins and checking each one against its transaction…")
        is SendPhase.Review -> Review(model, p, platform, state.walletName, state.amountUnit)
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
            // A transaction the server refuses for good would otherwise block this wallet forever.
            var confirmForget by remember { mutableStateOf(false) }
            SecondaryButton("Forget this transaction", Modifier.fillMaxWidth(), color = Warn) { confirmForget = true }
            if (confirmForget) AlertDialog(
                onDismissRequest = { confirmForget = false },
                title = { Text("Forget this transaction?") },
                text = { Text("Only if the network will not take it, for example its fee is below the server's minimum or its coins " +
                    "were already spent by another transaction. If it did reach the network it may still confirm: a new payment " +
                    "from the same coins replaces it, but one from other coins would pay twice.") },
                confirmButton = { TextButton(onClick = { confirmForget = false; model.discardPendingSend() }) { Text("FORGET IT", color = Bad) } },
                dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("KEEP IT", color = TextSoft) } },
                containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
            )
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
    var recipients by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf(false) }
    val rateValue = rate.replace(',', '.').toDoubleOrNull() ?: state.suggestedRate ?: 1.0
    val sats = state.amountUnit.parse(amount) ?: 0L
    val addrOk = to.isNotBlank() && Address.isValid(to)

    fun takePayment(text: String) {
        val parsed = runCatching { parsePaymentText(text) }.getOrElse { paymentError = it.message ?: "Invalid payment request"; return }
        paymentError = null
        val (a, s) = parsed
        to = a
        if (s != null) { amount = state.amountUnit.input(s); all = false }
    }

    ScreenColumn {
        BackLink { model.back() }
        Text("Send ${Chain.COIN}", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("From ${state.walletName}", color = TextSoft)
        Panel {
            SectionLabel("To")
            paymentError?.let { Text(it, color = Bad) }
            Field(to, { to = it.trim() }, "bc1… / 1… / 3… address", singleLine = false, minLines = 2)
            LinkText("Saved recipients") { recipients = true }
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
                Text(if (state.hideAmounts) "Everything: •••• ${state.amountUnit.label}" else "Everything: ${state.amountUnit.text(max)}", style = MaterialTheme.typography.titleMedium, color = Purple)
                Explain("All eligible selected coins minus the fee; frozen coins are excluded.")
            } else {
                Field(amount, { amount = it.take(32) }, state.amountUnit.label, keyboard = KeyboardType.Decimal)
                if (amount.isNotBlank() && sats == 0L) Explain("Enter a valid positive amount.", color = Bad)
                if (sats > 0) Explain(if (state.amountUnit == AmountUnit.SATS) "= ${formatBtc(sats)} XBT" else "= ${formatSats(sats)} sats")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(all, { all = it }, colors = CheckboxDefaults.colors(checkedColor = Purple, uncheckedColor = TextFaint))
                Text("send everything (MAX)", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
            }
            val available = state.spendable?.let { model.availableCoins().sumOf { it.value } }
            Explain(if (available == null) "loading your coins…" else if (state.hideAmounts) "spendable: •••• ${state.amountUnit.label}" else "spendable: ${state.amountUnit.text(available)} in ${model.availableCoins().size} eligible coins")
        }
        CoinControls(model, state)
        Panel {
            SectionLabel("Fee rate")
            Field(rate, { rate = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                "sat/vB (default ${fmtRate(state.suggestedRate ?: 1.0)})", keyboard = KeyboardType.Decimal)
            Explain(state.suggestedRate?.let { "The server suggests ${fmtRate(it)} sat/vB for the next blocks." }
                ?: "No estimate from the server; 1 sat/vB is the usual minimum.")
        }
        PrimaryButton("Review", Modifier.fillMaxWidth(), enabled = addrOk && (all || sats > 0) && state.coinPolicyError == null && model.availableCoins().isNotEmpty()) {
            model.prepareSend(to, sats, rateValue, all)
        }
    }
    if (recipients) RecipientsDialog(model, state, initialAddress = to, onChoose = { to = it; paymentError = null }, onClose = { recipients = false })
}

private fun fmtRate(r: Double): String {
    val t = (r * 10).roundToLong()
    return "${t / 10}.${t % 10}"
}

@Composable
private fun Review(model: WalletModel, p: SendPhase.Review, platform: AppPlatform, walletName: String, unit: AmountUnit) {
    val d = p.draft
    var ack by remember(d) { mutableStateOf(false) }
    var showDetails by remember(d) { mutableStateOf(false) }
    ScreenColumn {
        BackLink("← edit") { model.editSend() }
        Text("Review payment", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("From $walletName", color = TextSoft)
        Text(unit.text(d.amount), style = MaterialTheme.typography.headlineMedium, color = Purple)
        SectionLabel("XBT · BLAKE2b", color = TextSoft)
        Panel {
            SectionLabel("Recipient")
            Text(chunked(d.to), style = MaterialTheme.typography.titleMedium, color = TextMain)
        }
        Panel {
            KeyValue("Amount", unit.text(d.amount))
            KeyValue("Network fee", unit.text(d.fee))
            KeyValue("Total", unit.text(d.totalSent), Purple)
            KeyValue("Change back to you", if (d.change > 0) unit.text(d.change) else "none")
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
            d.inputs.forEach { Explain("${it.txid}:${it.vout} · ${unit.text(it.value)}") }
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

@Composable
private fun CoinControls(model: WalletModel, state: UiState, initiallyExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Panel {
        LinkText(if (expanded) "− Coin control" else "+ Coin control") { expanded = !expanded }
        state.coinPolicyError?.let { Explain(it, color = Bad) }
        Explain("${state.frozenCoins.size} frozen · " + if (state.selectedCoins == null) "automatic selection" else "${state.selectedCoins.size} selected")
        if (expanded) {
            Explain("Freezing excludes a coin from every new payment, including MAX. It is local to this device and is not restored by recovery words.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.selectedCoins != null, { model.setManualCoins(it) })
                Text("Choose exact coins", color = TextMain)
            }
            if (state.selectedCoins != null) Explain("Every selected coin will be used. Combining coins can link them publicly.")
            state.spendable?.forEach { coin ->
                val id = coin.coinId()
                val frozen = id in state.frozenCoins
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.selectedCoins != null) Checkbox(id in state.selectedCoins,
                        { model.selectCoin(id, it) }, enabled = !frozen)
                    Text(if (state.hideAmounts) "•••• " + state.amountUnit.label else state.amountUnit.text(coin.value), color = if (frozen) Warn else TextMain)
                }
                Explain(id)
                Explain(if (coin.height > 0) "Confirmed at block ${coin.height}" else "Unconfirmed")
                LinkText(if (frozen) "Unfreeze" else "Freeze") { model.freezeCoin(id, !frozen) }
            }
            val unseen = state.frozenCoins - (state.spendable?.map { it.coinId() }?.toSet() ?: emptySet())
            unseen.forEach { id ->
                Explain("Frozen · not in the current coin list: $id")
                LinkText("Unfreeze record") { model.freezeCoin(id, false) }
            }
        }
    }
}
