package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.ArkModel
import com.kilombino.kilowallet.app.UiState
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.i18n.Text
import com.kilombino.kilowallet.core.ark.Ark
import com.kilombino.kilowallet.core.crypto.Bip39
import com.kilombino.kilowallet.core.market.Fiat
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.wallet.formatSats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ARK tab, as Android 1.4.4's `ui/ArkScreen.kt`, `ui/ArkWords.kt` and `ui/ArkEmergency.kt`: the
 * warnings and ACTIVATE while Ark is off; then the balance (in Ark, the on-chain deposit, Lightning
 * settling), RECEIVE / SEND / MOVE INTO ARK / RENEW with the exact cost shown before anything is
 * signed, the activity, the words, the emergency exit and the engine log.
 */
@Composable
fun ArkTab(model: WalletModel, state: UiState, platform: AppPlatform) {
    val ark = model.ark
    val view by ark.view.collectAsState()
    val fig by ark.figures.collectAsState()
    val busy by ark.busy.collectAsState()
    val message by ark.message.collectAsState()
    var sheet by remember { mutableStateOf<String?>(null) }   // receive | send | board
    var renew by remember { mutableStateOf<Ark.Estimate?>(null) }
    val scope = rememberCoroutineScope()
    val hide = { t: String -> if (state.hidden) MASK else t }

    LaunchedEffect(Unit) {
        ark.open()
        // Keep the figures live while the tab is open; boards and rounds settle on their own.
        var tick = 0
        while (true) {
            delay(30_000)
            if (++tick % 4 == 0) ark.syncNow() else runCatching { ark.reload() }
        }
    }

    when (val v = view) {
        ArkModel.View.Intro, ArkModel.View.NoWallet -> {
            if (v == ArkModel.View.NoWallet) Panel(accent = Warn) {
                SectionLabel("Ark wallet missing", Warn)
                Explain("Ark is on but its engine has no wallet. Turn it on again from your words below: the server hands back the coins it holds for them.")
            }
            ArkWarnings()
            ArkActivate(model, ark)
        }
        ArkModel.View.Starting -> Panel(accent = ArkRed) {
            SectionLabel("Ark", ArkRed)
            Text("Starting the Ark engine…", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
        }
        is ArkModel.View.Failed -> Panel(accent = Bad) {
            SectionLabel("Ark could not start", Bad)
            Explain(v.message)
            SecondaryButton("TRY AGAIN", Modifier.fillMaxWidth(), color = ArkRed) { ark.open() }
        }
        ArkModel.View.Ready -> {
            ArkBalancePanel(fig, state, hide, onRescan = {
                ark.run("Looking for missing coins in the server's records…") {
                    Ark.rescanRecovery()
                    "Looked through the server's records of your coins: the balance above is up to date."
                }
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArkButton("RECEIVE", sheet == "receive", Modifier.weight(1f)) { sheet = if (sheet == "receive") null else "receive" }
                ArkButton("SEND", sheet == "send", Modifier.weight(1f)) { sheet = if (sheet == "send") null else "send" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArkButton("MOVE INTO ARK", sheet == "board", Modifier.weight(1f)) { sheet = if (sheet == "board") null else "board" }
                ArkButton("RENEW", renew != null, Modifier.weight(1f)) {
                    scope.launch {
                        ark.say("Calculating the renewal cost…")
                        renew = withContext(ioDispatcher) { Ark.estimateRenew() }
                            ?: Ark.Estimate(0, 0, 0, false, "", "Could not calculate the cost. Try again in a moment.")
                        ark.say(null)
                    }
                }
            }
            renew?.let { e ->
                ArkRenewDialog(e, onConfirm = { chosen ->
                    renew = null
                    ark.secured("Renew Ark coins", "Renewing coins…") {
                        Ark.renewSkippingRejected(chosen ?: withContext(ioDispatcher) { Ark.arkCoins().map { it.id } })
                    }
                }, onDismiss = { renew = null })
            }
            when (sheet) {
                "receive" -> ArkReceiveSheet(platform, state)
                "send" -> ArkSendSheet(platform, state, fig.balance?.onchainConfirmed ?: 0) { dest, sats, approved, fromDeposit, coins, rate ->
                    ark.secured("Send from Ark", "Sending…") {
                        when {
                            coins.isNotEmpty() && fromDeposit -> Ark.sendDepositCoins(dest, sats, coins, approved, rate)
                            coins.isNotEmpty() && dest.startsWith("ark1", true) && sats != null && approved != null ->
                                Ark.sendArkCoins(dest, sats, approved, coins)
                            coins.isNotEmpty() -> Ark.withdrawCoins(dest, coins, approved)
                            fromDeposit -> Ark.sendFromDeposit(dest, sats, approved, rate)
                            else -> Ark.send(dest, sats, approved)
                        }.ifBlank { "Sent." }
                    }
                    sheet = null
                }
                "board" -> ArkBoardSheet(fig.balance, state) { sats, rate, coins ->
                    ark.secured("Move into Ark", "Moving funds into Ark…") {
                        when {
                            coins != null -> Ark.boardCoins(coins, sats, rate)
                            sats == null -> Ark.boardAll(rate)
                            else -> Ark.board(sats, rate)
                        }
                        "Moving into Ark. It becomes spendable after ${Ark.BOARD_CONFIRMATIONS} confirmations."
                    }
                    sheet = null
                }
            }
            if (fig.activity.isNotEmpty()) ArkHistory(fig.activity, fig.balance?.spendable, hide, state)
            ArkWordsPanel(model)
            ArkEmergencyPanel(ark)
            ArkEngineLog(ark)
            ArkIphonePanel()
            ArkWarnings()
            LinkText("turn Ark off on this phone", color = TextFaint) { ark.turnOff() }
        }
    }
    busy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Error")) Bad else Good) }
}

@Composable
private fun ArkButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) PrimaryButton(label, modifier, color = ArkRed, onClick = onClick)
    else SecondaryButton(label, modifier, color = ArkRed, onClick = onClick)
}

private fun fiatOf(state: UiState, sats: Long): String? = state.perCoin?.let { "≈ " + Fiat.format(it * sats / 1e8, state.fiat) }

@Composable
private fun ArkBalancePanel(f: ArkModel.Figures, state: UiState, hide: (String) -> String, onRescan: () -> Unit) {
    val b = f.balance
    Panel(accent = ArkRed) {
        SectionLabel("Your Ark wallet", ArkRed)
        val deposit = (b?.onchainConfirmed ?: 0) + (b?.onchainPending ?: 0)
        val total = (b?.arkTotal ?: 0) + deposit + (b?.lightningPending ?: 0)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(hide(formatSats(total)), style = MaterialTheme.typography.displayLarge, color = ArkRed)
            Spacer(Modifier.width(8.dp))
            Text("sats", style = MaterialTheme.typography.titleLarge, color = ArkRed.copy(alpha = 0.7f))
        }
        fiatOf(state, total)?.let { Text(hide(it), style = MaterialTheme.typography.titleMedium, color = TextMain) }
        if (b == null) { Text("Loading…", style = MaterialTheme.typography.bodySmall, color = TextFaint); return@Panel }
        if (b.pendingBoard > 0) Text("entering Ark: ${hide(formatSats(b.pendingBoard) + " sats")} · " +
            (f.boardConfs?.let { "$it of ${Ark.BOARD_CONFIRMATIONS} confirmations" } ?: "needs ${Ark.BOARD_CONFIRMATIONS} confirmations"),
            style = MaterialTheme.typography.bodySmall, color = Warn)
        if (b.pendingRound > 0) Text("in the next round: " + hide("${formatSats(b.pendingRound)} sats"),
            style = MaterialTheme.typography.bodySmall, color = Warn)
        Spacer(Modifier.height(6.dp))
        PocketRow("In Ark", hide(formatSats(b.spendable) + " sats"), "spendable", Good)
        if (b.refused > 0) {
            Text("Not counted: " + hide("${formatSats(b.refused)} sats") + " in coins the server refused in the " +
                "last renewal as already spent: an earlier round renewed them and this phone did not record the new coins. " +
                "LOOK FOR MISSING COINS fetches them from the server's records of your coins.",
                style = MaterialTheme.typography.bodySmall, color = Warn)
            LinkText("LOOK FOR MISSING COINS", color = ArkRed, onClick = onRescan)
        }
        PocketRow("On-chain deposit", hide(formatSats(deposit) + " sats"),
            if (b.onchainPending > 0) hide(formatSats(b.onchainPending)) + " unconfirmed · not in Ark yet" else "not in Ark yet", TextSoft)
        if (b.lightningPending > 0) PocketRow("Lightning", hide(formatSats(b.lightningPending) + " sats"), "settling", Warn)
        if (b.pendingExit > 0) Text("leaving Ark (emergency exit): " + hide("${formatSats(b.pendingExit)} sats"),
            style = MaterialTheme.typography.bodySmall, color = Bad)
        f.expiry?.let {
            Spacer(Modifier.height(6.dp))
            Text("Next coin expires in $it blocks (≈ ${Ark.oneDecimalDays(it)} days). Opening the app renews it; tap RENEW to do it now.",
                style = MaterialTheme.typography.bodySmall, color = if (it < 1008) Bad else TextFaint)
        }
    }
}

@Composable
private fun PocketRow(label: String, amount: String, note: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.width(120.dp))
        Text(amount, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(8.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = TextFaint, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/** A QR code on white, tappable to copy what it says. */
@Composable
private fun ArkQr(platform: AppPlatform, text: String, qrText: String = text) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val qr = remember(qrText) { platform.qrImage(qrText) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (qr != null) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)
            .clickable { clipboard.setText(AnnotatedString(text)); copied = true }) {
            Image(qr, contentDescription = "QR code", modifier = Modifier.size(230.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextMain, modifier = Modifier.clickable { clipboard.setText(AnnotatedString(text)); copied = true })
        TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }) {
            Text(if (copied) "COPIED ✓" else "COPY", color = if (copied) Good else ArkRed)
        }
    }
}

@Composable
private fun ArkReceiveSheet(platform: AppPlatform, state: UiState) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }  // label, text
    var amount by remember { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lnState by remember { mutableStateOf<String?>(null) }

    fun get(label: String, block: () -> String, show: (String) -> Pair<String, String>) {
        if (working != null) return
        shown = null; error = null; working = label
        scope.launch {
            runCatching { withContext(ioDispatcher) { block() } }
                .onSuccess { lnState = null; shown = show(it) }
                .onFailure { error = "Error: " + (it.message ?: it.toString()) }
            working = null
        }
    }

    Panel(accent = ArkRed) {
        SectionLabel("Receive", ArkRed)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("ARK", Modifier.weight(1f), color = ArkRed) { get("Getting an Ark address…", { Ark.arkAddress() }) { "Ark address" to it } }
            SecondaryButton("DEPOSIT", Modifier.weight(1f), color = ArkRed) {
                get("Getting a deposit address…", { Ark.onchainAddress() }) { "On-chain deposit address" to it }
            }
            SecondaryButton("⚡ OFFER", Modifier.weight(1f), color = ArkRed) {
                val sats = amount.toLongOrNull()
                get("Getting your reusable offer…", { Ark.reusableOffer("Kilowallet", sats) }) {
                    (if (sats != null) "Reusable Lightning offer (BOLT12) for $sats sats" else "Reusable Lightning offer (BOLT12), any amount") to it
                }
            }
        }
        AmountFields(amount, { amount = it }, state)
        SecondaryButton("⚡ INVOICE", Modifier.fillMaxWidth(), color = ArkRed) {
            val sats = amount.toLongOrNull()
            if (sats == null || sats <= 0) { error = "Type the amount in sats first."; return@SecondaryButton }
            get("Creating a new Lightning invoice… It can take a minute while the wallet finishes other work, such as claiming an earlier payment.", {
                // The engine would make an invoice whose payment can never be claimed: refuse it here.
                val net = Ark.estimateLightningReceive(sats)?.amount
                require(net == null || net >= Ark.MIN_OUTPUT_SAT) {
                    "An invoice of $sats sats leaves you ${net ?: 0} after the receive cost, below the " +
                        "${Ark.MIN_OUTPUT_SAT}-sat minimum, so the payment would fail. Ask for at least about 5 500 sats."
                }
                Ark.lightningInvoice(sats, null)
            }) { "Lightning invoice ($sats sats)" to it }
        }
        working?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
        Text("Lightning: up to ${formatSats(Ark.MAX_LIGHTNING_SAT)} sats per payment. Deposits become Ark funds with MOVE INTO ARK (minimum ${formatSats(Ark.MIN_BOARD_SAT)} sats).",
            style = MaterialTheme.typography.bodySmall, color = TextFaint)
        // Before creating a Lightning invoice, show what will actually arrive.
        val lnSats = amount.toLongOrNull()
        var lnEst by remember { mutableStateOf<Ark.Estimate?>(null) }
        LaunchedEffect(lnSats) {
            lnEst = if (lnSats != null && lnSats > 0) withContext(ioDispatcher) { runCatching { Ark.estimateLightningReceive(lnSats) }.getOrNull() } else null
        }
        lnEst?.let { e ->
            CostBreakdown(e, "You receive", "Invoice amount", state)
            if (e.amount < Ark.MIN_OUTPUT_SAT) Text("Too small: after the receive cost you would get ${formatSats(e.amount)} " +
                "sats, below the ${formatSats(Ark.MIN_OUTPUT_SAT)}-sat minimum, so the payer's payment would fail.",
                style = MaterialTheme.typography.bodySmall, color = Bad)
        }
        // Follow an invoice on screen until it is paid, so nobody pays it a second time.
        val isInvoice = shown?.first?.startsWith("Lightning invoice") == true
        LaunchedEffect(shown) {
            val inv = shown?.second
            if (!isInvoice || inv == null) return@LaunchedEffect
            while (lnState != "settled") {
                lnState = withContext(ioDispatcher) { Ark.lightningReceiveState(inv) } ?: lnState
                delay(4_000)
            }
        }
        shown?.let { (label, text) ->
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = ArkRed)
            if (isInvoice) {
                val (st, col) = when (lnState) {
                    "settled" -> "✅ Paid: this invoice is done, it can't be paid again. Create a new one for another payment." to Good
                    "htlcs-ready", "preimage-revealed", "delivering" -> "⏳ Payment arriving…" to Warn
                    else -> "Waiting for payment. Valid for one payment only." to TextFaint
                }
                Text(st, style = MaterialTheme.typography.bodySmall, color = col)
            }
            if (!(isInvoice && lnState == "settled")) ArkQr(platform, text, if (label.contains("Lightning")) text.uppercase() else text)
            if (label.contains("BOLT12")) {
                Text("One code you can share and be paid on many times; each payment lands in Ark. It only works while " +
                    "Kilowallet is open on this iPhone: if it is closed, the payer sees the payment fail and nothing is lost. " +
                    "Each payment pays the Lightning receive cost (recovery reserve plus the server's fee); payments under " +
                    "about 5 500 sats fail. Type an amount first to fix it, or leave it empty so payers choose.",
                    style = MaterialTheme.typography.bodySmall, color = TextFaint)
                LinkText("DISABLE", color = TextSoft) {
                    get("Disabling the offer…", { Ark.disableOffer(); "" }) { "" to "" }
                    shown = null
                }
            }
        }
    }
}

@Composable
private fun ArkSendSheet(
    platform: AppPlatform, state: UiState, deposit: Long,
    onSend: (String, Long?, Long?, Boolean, List<String>, Double?) -> Unit,
) {
    var dest by remember { mutableStateOf("") }
    var choose by remember { mutableStateOf(false) }
    var coins by remember { mutableStateOf<List<Ark.Coin>>(emptyList()) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var fromDeposit by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var rateText by remember { mutableStateOf("") }
    val rate = rateText.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.1..10_000.0 }
    var review by remember { mutableStateOf<Ark.Estimate?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var reviewError by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    Panel(accent = ArkRed) {
        SectionLabel(if (fromDeposit) "Send from the on-chain deposit" else "Send from Ark", ArkRed)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("From", style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
            Chip("ARK", !fromDeposit, ArkRed) { fromDeposit = false; review = null; picked = emptySet(); choose = false }
            Chip("DEPOSIT · " + (if (state.hidden) MASK else formatSats(deposit)), fromDeposit, ArkRed) {
                fromDeposit = true; review = null; picked = emptySet(); choose = false
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
            choose = !choose; review = null; picked = emptySet()
            if (choose) scope.launch { coins = withContext(ioDispatcher) { if (fromDeposit) Ark.depositCoins() else Ark.arkCoins() } }
        }) {
            Text(if (choose) "☑" else "☐", color = ArkRed, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("Choose coins" + if (!fromDeposit) " (Ark payments and withdrawals to a BTC address)" else "",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        if (choose) CoinPicker(coins, picked) { id -> picked = if (id in picked) picked - id else picked + id; review = null }
        if (fromDeposit) {
            Text("Plain on-chain BTC to a BTC address. Only the network fee; no Ark reserves.",
                style = MaterialTheme.typography.bodySmall, color = TextFaint)
            FeeRatePicker(rateText) { rateText = it; review = null }
        }
        Field(dest, { dest = it.trim(); review = null }, "Ark address, Lightning invoice or BTC address")
        Row {
            TextButton(onClick = { clipboard.getText()?.text?.let { dest = Ark.scannedDestination(it); review = null } }) { Text("PASTE", color = ArkRed) }
            if (platform.canScanQr) TextButton(onClick = { platform.scanQr { raw -> if (raw != null) { dest = Ark.scannedDestination(raw); review = null } } }) {
                Text("SCAN QR", color = ArkRed)
            }
        }
        AmountFields(amount, { amount = it; review = null }, state)
        Explain("sats (empty: the invoice's amount, or everything to a BTC address)")
        val r = review
        if (r == null) {
            // Step 1: always show the real cost before anything is signed.
            PrimaryButton(if (reviewing) "CALCULATING…" else "REVIEW COST", Modifier.fillMaxWidth(), enabled = dest.isNotBlank() && !reviewing, color = ArkRed) {
                reviewing = true; reviewError = null
                scope.launch {
                    val e = withContext(ioDispatcher) {
                        runCatching {
                            val sats = amount.toLongOrNull()
                            when {
                                choose && picked.isEmpty() -> Ark.Estimate(0, 0, 0, false, "", "Tick the coins to spend.")
                                fromDeposit && rate == null -> Ark.Estimate(0, 0, 0, false, "", "Choose a network fee (sat/vB, at least 0.1).")
                                choose && fromDeposit -> Ark.estimateDepositCoins(dest, sats, picked.toList(), rate)
                                choose && dest.startsWith("ark1", true) ->
                                    if (sats == null) Ark.Estimate(0, 0, 0, false, "", "Enter the amount to pay.")
                                    else Ark.estimateArkCoins(dest, sats, picked.toList())
                                choose && dest.startsWith("ln", true) ->
                                    Ark.Estimate(0, 0, 0, false, "", "Choosing coins works for Ark payments and " +
                                        "withdrawals to a BTC address. Lightning payments pick their coins themselves.")
                                choose && sats != null -> Ark.Estimate(0, 0, 0, false, "", "Chosen Ark coins leave whole: leave the amount empty.")
                                choose -> Ark.estimateWithdrawCoins(dest, picked.toList())
                                fromDeposit -> Ark.estimateDepositSend(dest, sats, rate)
                                else -> Ark.estimateSend(dest, sats)
                            }
                        }.getOrNull()
                    }
                    reviewing = false
                    if (e == null) reviewError = "Could not estimate the cost. Check the destination and the amount."
                    review = e
                }
            }
            reviewError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
        } else {
            if (r.problem == null) CostBreakdown(r, "They receive", "You pay in total", state)
            r.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
            // A cost that looks like a slip needs a second yes, as the wallet's own sends do.
            val fee = (r.total - r.amount).coerceAtLeast(0)
            val concern = if (r.problem == null && (fee > 100_000 || (r.amount > 0 && fee * 100 > r.amount * 5)))
                "This fee is high: ${formatSats(fee)} sats." else null
            var feeOk by remember(r) { mutableStateOf(false) }
            concern?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { feeOk = !feeOk }) {
                    Text(if (feeOk) "☑" else "☐", color = Warn, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Warn)
                    Spacer(Modifier.width(6.dp))
                    Text("I checked it and want to pay it.", style = MaterialTheme.typography.bodySmall, color = Warn)
                }
            }
            if (r.problem == null) PrimaryButton("CONFIRM AND SEND", Modifier.fillMaxWidth(), enabled = concern == null || feeOk, color = ArkRed) {
                onSend(dest, amount.toLongOrNull(), r.total, fromDeposit, if (choose) picked.toList() else emptyList(), if (fromDeposit) rate else null)
            }
            LinkText("change") { review = null }
        }
    }
}

/** The network fee of an on-chain send: slow / normal / fast from the chain, or typed in sat/vB. */
@Composable
private fun FeeRatePicker(rateText: String, onRate: (String) -> Unit) {
    var rates by remember { mutableStateOf<Ark.FeeRates?>(null) }
    LaunchedEffect(Unit) {
        rates = withContext(ioDispatcher) { Ark.onchainFeeRates() }
        if (rateText.isEmpty()) rates?.let { onRate(it.regular.coerceAtLeast(1).toString()) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Network fee", style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
        rates?.let { r ->
            listOf("slow" to r.slow, "normal" to r.regular, "fast" to r.fast).forEach { (name, v) ->
                val value = v.coerceAtLeast(1).toString()
                Chip(name, rateText == value, ArkRed) { onRate(value) }
            }
        }
    }
    Field(rateText, { t -> onRate(t.filter { it.isDigit() || it == '.' || it == ',' }) }, "sat/vB (decimals allowed, at least 0.1)", keyboard = KeyboardType.Decimal)
}

/** Tick boxes over coins, biggest first, with what each holds. */
@Composable
private fun CoinPicker(coins: List<Ark.Coin>, picked: Set<String>, onToggle: (String) -> Unit) {
    if (coins.isEmpty()) { Text("No coins here.", style = MaterialTheme.typography.bodySmall, color = TextFaint); return }
    Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(10.dp)).padding(8.dp)) {
        coins.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onToggle(c.id) }.padding(vertical = 4.dp)) {
                Text(if (c.id in picked) "☑" else "☐", color = ArkRed, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(formatSats(c.amount) + " sats", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                    Text(c.id.take(10) + "…" + c.id.takeLast(6) + "  " + c.note, style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
            }
        }
        val sum = formatSats(coins.filter { it.id in picked }.sumOf { it.amount })
        Text("Chosen: $sum sats in ${picked.size} of ${coins.size} coins",
            style = MaterialTheme.typography.bodySmall, color = TextSoft)
    }
}

/** Amount / cost / total, with the cost in warning colour when it is a big share. */
@Composable
private fun CostBreakdown(e: Ark.Estimate, amountLabel: String, totalLabel: String, state: UiState) {
    val share = if (e.total > 0) e.fee * 100 / e.total else 0
    Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row { Text(amountLabel, style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
              Text(formatSats(e.amount) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain) }
        Row { Text(if (e.exact) "Cost" else "Cost (approx.)", style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
              Text(formatSats(e.fee) + " sats" + (if (share > 0) "  ($share% of the total)" else ""), style = MaterialTheme.typography.bodySmall,
                  color = if (share >= 15) Warn else TextMain) }
        Row { Text(totalLabel, style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.weight(1f))
              Text(formatSats(e.total) + " sats", style = MaterialTheme.typography.titleMedium, color = ArkRed) }
        fiatOf(state, e.total)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextFaint, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) }
        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = TextFaint)
    }
}

@Composable
private fun ArkBoardSheet(b: Ark.Balance?, state: UiState, onBoard: (Long?, Double?, List<String>?) -> Unit) {
    var amount by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var choose by remember { mutableStateOf(false) }
    var coins by remember { mutableStateOf<List<Ark.Coin>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val chosen = if (choose) picked.toList() else null
    val available = if (chosen != null) coins.filter { it.id in picked }.sumOf { it.amount } else (b?.onchainConfirmed ?: 0)
    var rateText by remember { mutableStateOf("") }
    val rate = rateText.replace(',', '.').toDoubleOrNull()
    val rateOk = rate != null && rate >= 0.1 && rate <= 10_000
    Panel(accent = ArkRed) {
        SectionLabel("Move into Ark", ArkRed)
        Explain("Moves confirmed BTC from this wallet's deposit address into Ark. Minimum " +
            "${formatSats(Ark.MIN_BOARD_SAT)} sats; spendable after ${Ark.BOARD_CONFIRMATIONS} confirmations. Available: ${formatSats(available)} sats.")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
            choose = !choose; picked = emptySet()
            if (choose) scope.launch { coins = withContext(ioDispatcher) { Ark.depositCoins() } }
        }) {
            Text(if (choose) "☑" else "☐", color = ArkRed, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text("Choose coins", style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        if (choose) CoinPicker(coins, picked) { id -> picked = if (id in picked) picked - id else picked + id }
        Field(amount, { amount = it.filter(Char::isDigit) }, "sats (empty = everything)", keyboard = KeyboardType.Number)
        FeeRatePicker(rateText) { rateText = it }
        if (rate != null && rate < 1.0 && rateOk) Text(
            "Below 1 sat/vB most BTC pools do not mine it yet, so it can take a long time to confirm. " +
                "The move only counts from its ${Ark.BOARD_CONFIRMATIONS}rd confirmation, and the new coin's " +
                "30 days start when you send it: a long wait shortens them.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
        val sats = amount.toLongOrNull()
        var est by remember { mutableStateOf<Ark.Estimate?>(null) }
        var netFee by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(sats, available, rate, picked, choose) {
            netFee = if (!rateOk) null
                else if (chosen != null) { if (chosen.isEmpty()) null else withContext(ioDispatcher) { runCatching { Ark.estimateBoardCoinsFee(chosen, sats, rate!!) }.getOrNull() } }
                else withContext(ioDispatcher) { runCatching { Ark.estimateBoardNetworkFee(rate!!, sats == null) }.getOrNull() }
            val target = sats ?: (available - (netFee ?: 0))
            est = if (target >= Ark.MIN_BOARD_SAT) withContext(ioDispatcher) { runCatching { Ark.estimateBoard(target) }.getOrNull() } else null
        }
        est?.let {
            CostBreakdown(it, "Added to Ark", "From your deposit", state)
            netFee?.let { f ->
                Text("Plus the network fee of the move: about ${formatSats(f)} sats at $rateText sat/vB" +
                    ", paid from the deposit" + (if (sats == null) " before the rest moves in." else "."),
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
            }
            Text("Your Ark balance after: ${formatSats((b?.spendable ?: 0) + it.amount)} sats (now ${formatSats(b?.spendable ?: 0)} + ${formatSats(it.amount)}).",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        PrimaryButton("MOVE INTO ARK", Modifier.fillMaxWidth(), color = ArkRed,
            enabled = available >= Ark.MIN_BOARD_SAT && (sats == null || sats >= Ark.MIN_BOARD_SAT) && rateOk && (rate ?: 0.0) <= 100 &&
                (chosen == null || (chosen.isNotEmpty() && netFee != null))) { onBoard(sats, rate, chosen) }
        if ((rate ?: 0.0) > 100) Text("This fee is high: $rateText sat/vB.", style = MaterialTheme.typography.bodySmall, color = Warn)
    }
}

/** The status, with the confirmations so far while a move into Ark or a deposit waits. */
private fun statusOf(m: Ark.Movement): String {
    val c = m.confirmations ?: return m.status
    return when (m.kind) {
        "move into Ark" -> "${m.status} · $c/${Ark.BOARD_CONFIRMATIONS} conf"
        "withdrawal" -> (if (c == 0) "sent, in the mempool" else "sent") + " · $c/${Ark.OFFBOARD_CONFIRMATIONS} conf"
        else -> "${m.status} · $c conf"
    }
}

@Composable
private fun ArkHistory(items: List<Ark.Movement>, spendable: Long?, hide: (String) -> String, state: UiState) {
    // What was left in Ark after each movement: today's spendable balance, walking back.
    val after = HashMap<String, Long>()
    if (spendable != null) {
        var run = spendable
        for (m in items) {
            if (m.kind == "deposit received" || m.kind == "sent from deposit") continue
            after[m.id] = run
            val st = m.status.lowercase()
            run -= when {
                st == "failed" || st == "canceled" || st == "cancelled" -> 0L
                st == "pending" && m.amount > 0 -> 0L
                else -> m.amount
            }
        }
    }
    var open by remember { mutableStateOf<Ark.Movement?>(null) }
    open?.let { m -> ArkMovementDialog(m, state) { open = null } }
    Panel(accent = ArkRed) {
        SectionLabel("Ark activity", ArkRed)
        Explain("tap a movement for its details")
        items.take(20).forEach { m ->
            Column(Modifier.fillMaxWidth().clickable { open = m }.padding(vertical = 2.dp)) {
                Row {
                    Text(hide((if (m.amount >= 0) "+" else "") + formatSats(m.amount) + " sats"), style = MaterialTheme.typography.bodySmall,
                        color = if (m.amount >= 0) Good else TextMain, modifier = Modifier.weight(1f))
                    Text("${m.kind} · ${statusOf(m)}", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                after[m.id]?.let { left ->
                    val st = m.status.lowercase()
                    Text(hide(if (st == "failed" || st == "canceled" || st == "cancelled") "(nothing changed: ${formatSats(left)} sats left)"
                        else "(left: ${formatSats(left)} sats)"), style = MaterialTheme.typography.bodySmall, color = TextSoft)
                }
            }
        }
    }
}

/** One movement in full, and its blockchain transaction when it has one. */
@Composable
private fun ArkMovementDialog(m: Ark.Movement, state: UiState, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(m.kind.replaceFirstChar { it.uppercase() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValue("Amount", (if (m.amount >= 0) "+" else "") + formatSats(m.amount) + " sats" + (fiatOf(state, m.amount)?.let { "  $it" } ?: ""))
                if (m.fee > 0) KeyValue("Cost", formatSats(m.fee) + " sats")
                KeyValue("Status", statusOf(m))
                if (m.time.isNotEmpty()) KeyValue("Date", m.time.substringBefore('.').replace('T', ' ') + " UTC")
                m.destinations.forEach { d -> KeyValue("To", d.take(24) + "…" + d.takeLast(8)) }
                if (m.onchainTxid != null) {
                    Text("On the blockchain: ${m.onchainTxid!!.take(12)}…${m.onchainTxid!!.takeLast(8)}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { clipboard.setText(AnnotatedString(m.onchainTxid!!)) })
                } else {
                    Text("This movement is off-chain: an Ark coin signed with the server, with no " +
                        "blockchain transaction until the coin is renewed or withdrawn.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                }
                m.vtxos.take(3).forEach { v ->
                    Text("Ark coin: ${v.take(12)}…${v.takeLast(10)}", style = MaterialTheme.typography.bodySmall, color = TextSoft,
                        modifier = Modifier.clickable { clipboard.setText(AnnotatedString(v)) })
                }
                m.paymentHash?.let { h ->
                    Text("Payment hash: ${h.take(16)}…", style = MaterialTheme.typography.bodySmall, color = TextSoft,
                        modifier = Modifier.clickable { clipboard.setText(AnnotatedString(h)) })
                }
                m.preimage?.let { p ->
                    Text("Preimage (proof of payment): ${p.take(16)}…", style = MaterialTheme.typography.bodySmall, color = TextSoft,
                        modifier = Modifier.clickable { clipboard.setText(AnnotatedString(p)) })
                }
                if (m.vtxos.isNotEmpty() || m.paymentHash != null || m.onchainTxid != null)
                    Text("Tap an identifier to copy it.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE", color = ArkRed) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

/** RENEW asks first: what a renewal does, what it costs now; untick coins to renew only the others. */
@Composable
private fun ArkRenewDialog(e: Ark.Estimate, onConfirm: (List<String>?) -> Unit, onDismiss: () -> Unit) {
    var coins by remember { mutableStateOf<List<Ark.Coin>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    var quote by remember { mutableStateOf(e) }
    LaunchedEffect(Unit) {
        val refused = withContext(ioDispatcher) { Ark.rejected() }
        coins = withContext(ioDispatcher) { Ark.arkCoins() }
            .map { if (it.id in refused) it.copy(note = it.note + " · ALREADY SPENT (says the server)") else it }
        picked = coins.map { it.id }.filter { it !in refused }.toSet()
    }
    val sel = picked
    LaunchedEffect(sel) {
        if (sel != null && coins.isNotEmpty() && sel.size != coins.size) {
            quote = withContext(ioDispatcher) {
                if (sel.isEmpty()) Ark.Estimate(0, 0, 0, false, "", "Tick at least one coin.")
                else Ark.estimateRenewCoins(sel.toList()) ?: Ark.Estimate(0, 0, 0, false, "", "Could not calculate the cost.")
            }
        } else if (sel != null && sel.size == coins.size) quote = e
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renew your Ark coins?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Renewing takes your coins into the next round: they get a new expiry, about " +
                    "30 days from now, and are joined into one coin, which makes later payments " +
                    "cheaper. The wallet also renews by itself before coins expire, so you only " +
                    "need this to join coins or to renew early.", style = MaterialTheme.typography.bodySmall)
                if (coins.size > 1 && sel != null) CoinPicker(coins, sel) { id -> picked = if (id in sel) sel - id else sel + id }
                quote.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
                    ?: Column(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(10.dp)).padding(10.dp)) {
                        KeyValue("Your coins after", formatSats(quote.amount) + " sats")
                        KeyValue("Cost", formatSats(quote.fee) + " sats")
                        KeyValue("Renewed now", formatSats(quote.total) + " sats")
                        if (quote.note.isNotBlank()) Text(quote.note, style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    }
            }
        },
        confirmButton = {
            if (quote.problem == null) TextButton(onClick = {
                onConfirm(if (sel == null || sel.size == coins.size) null else sel.toList())
            }) { Text("RENEW", color = ArkRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

@Composable
fun ArkWarnings() {
    Panel(accent = Warn) {
        SectionLabel("Before you use Ark", Warn)
        listOf(
            "Ark funds expire after ${Ark.VTXO_LIFETIME_BLOCKS} blocks (about 30 days). The wallet must go online before then to renew them, or they have to be withdrawn on-chain with fees and waiting times.",
            "Minimum to move funds into Ark: ${formatSats(Ark.MIN_BOARD_SAT)} sats.",
            "Maximum per Ark coin: ${formatSats(Ark.MAX_VTXO_SAT)} sats.",
            "Lightning: up to ${formatSats(Ark.MAX_LIGHTNING_SAT)} sats per payment, no channels needed.",
            "Every Ark payment pre-pays recovery reserves for each coin it uses: about 1 056 sats for a coin spent whole and 1 584 for one with change (4 000 and 6 000 where the server has not enabled its smaller anchors); change must be at least 1 330 sats. Small balances can only leave Ark on-chain. The wallet shows the exact cost before you confirm.",
            "Rounds every 60 seconds; moving funds in needs 3 confirmations.",
            "Ark is beta software, through the Paperclip Ark server (ark.paperclippool.xyz).",
        ).forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = TextSoft) }
    }
}

/** What an iPhone changes: the engine only runs while the app is open. */
@Composable
private fun ArkIphonePanel() {
    Panel(accent = ArkRed) {
        SectionLabel("On iPhone", ArkRed)
        listOf(
            "The Ark engine runs only while Kilowallet is open: iOS stops apps in the background.",
            "Coins are renewed when you open the app (the engine renews any coin close to expiry). Open Kilowallet at least once every few weeks while you hold Ark coins; a notification reminds you three days before the next coin expires.",
            "iOS may also give the app a few minutes in the background to renew, usually while the phone charges. It decides when, so do not count on it.",
            "A coin that expires can only leave Ark on-chain (emergency exit), with fees and a day's wait.",
            "A reusable Lightning offer is only paid while the app is open.",
        ).forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = TextSoft) }
    }
}

/** ACTIVATE (Ark's own words, made from the wallet's), or restore from words. */
@Composable
private fun ArkActivate(model: WalletModel, ark: ArkModel) {
    var understood by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("activate") }      // activate | words
    var phrase by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var whichArk by remember { mutableStateOf<List<String>?>(null) }
    val busy by ark.busy.collectAsState()

    whichArk?.let { words ->
        AlertDialog(
            onDismissRequest = { whichArk = null },
            title = { Text("Which Ark wallet?") },
            text = { Text("These are your BTC wallet's words. An Ark wallet made with them in Kilowallet 1.1.4 or later has " +
                "its own words made from them; one made before 1.1.4 used these same words. If you are not sure, try the " +
                "newer kind first: restoring the other one later is always possible.") },
            confirmButton = { TextButton(onClick = { whichArk = null; ark.restore(words, passphrase, derivedFrom = true) }) { Text("1.1.4 OR LATER", color = ArkRed) } },
            dismissButton = { TextButton(onClick = { whichArk = null; ark.restore(words, passphrase, derivedFrom = false) }) { Text("BEFORE 1.1.4", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }

    Panel(accent = ArkRed) {
        if (!ark.canActivate) {
            Text("This build has no Ark engine.", style = MaterialTheme.typography.bodySmall, color = Warn)
            return@Panel
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { understood = !understood }) {
            Text(if (understood) "☑" else "☐", style = MaterialTheme.typography.titleLarge, color = ArkRed)
            Spacer(Modifier.width(10.dp))
            Text("I have read the warnings above. Ark is beta software and I will start with a small amount.",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        Explain("Ark gets its own words, made from your BTC wallet's words, so the words you wrote down back up both. " +
            "Ark keeps its words so it can renew coins when the app opens, readable without Face ID on this iPhone; " +
            "they open Ark only, never your on-chain coins.")
        if (mode == "activate") {
            PrimaryButton("ACTIVATE ARK", Modifier.fillMaxWidth(), enabled = understood && busy == null, color = ArkRed) {
                model.withWords("Make Ark's words from your wallet's") { words, pass -> ark.activate(words, pass) }
            }
            LinkText("restore from words") { if (understood) mode = "words" }
        } else {
            Explain("Type the Ark wallet's 12 or 24 words. The Ark server hands back the coins it holds for them; history is not recovered.")
            Field(phrase, { phrase = it; error = null }, "Ark words", singleLine = false, minLines = 3)
            Field(passphrase, { passphrase = it }, "passphrase (if it had one)")
            PrimaryButton("RESTORE ARK", Modifier.fillMaxWidth(), enabled = busy == null, color = ArkRed) {
                val words = phrase.trim().lowercase().split(Regex("\\s+"))
                if (!Bip39.isValid(words)) error = "Those words are not a valid BIP-39 phrase."
                else model.withWords("Compare with your BTC wallet's words") { hot, hotPass ->
                    if (hot == words && hotPass == passphrase) whichArk = words
                    else ark.restore(words, passphrase, derivedFrom = false)
                }
            }
            LinkText("back") { mode = "activate" }
        }
        error?.let { Text("Error: $it", style = MaterialTheme.typography.bodySmall, color = Bad) }
    }
}

/** RECOVERY WORDS: Ark's own words (made from the wallet's, so the wallet's words also bring them back). */
@Composable
private fun ArkWordsPanel(model: WalletModel) {
    var shown by remember { mutableStateOf<List<String>?>(null) }
    var warn by remember { mutableStateOf(false) }
    if (warn) AlertDialog(
        onDismissRequest = { warn = false },
        title = { Text("Show recovery words?") },
        text = { Text("Anyone who sees these words can take your money. Make sure nobody is looking and nothing is recording the screen.") },
        confirmButton = { TextButton(onClick = {
            warn = false
            model.withWords("Show your Ark words") { _, _ -> shown = Ark.loadWords() }
        }) { Text("SHOW", color = Bad) } },
        dismissButton = { TextButton(onClick = { warn = false }) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
    Panel(accent = ArkRed) {
        SectionLabel("Backup", ArkRed)
        Explain(if (model.ark.wordsDerived()) "Ark's words are made from your BTC wallet's: the words you wrote down bring Ark back too " +
                "(restore from words, 1.1.4 OR LATER). The Ark server hands your coins back on a new phone."
            else "Write down Ark's words: the Ark server hands your coins back on a new phone.")
        SecondaryButton(if (shown == null) "RECOVERY WORDS" else "HIDE WORDS", Modifier.fillMaxWidth(), color = ArkRed) {
            if (shown == null) warn = true else shown = null
        }
        shown?.let { w -> Text(w.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("   "), style = MaterialTheme.typography.bodyMedium, color = TextMain) }
    }
}

/**
 * The emergency exit: taking Ark coins on-chain WITHOUT the Ark server, by broadcasting the signed
 * recovery transactions every coin carries. The last resort, folded away behind a warning.
 */
@Composable
private fun ArkEmergencyPanel(ark: ArkModel) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var exits by remember { mutableStateOf<List<Ark.ExitState>>(emptyList()) }
    var lost by remember { mutableStateOf<Set<String>>(emptySet()) }
    var quote by remember { mutableStateOf<Ark.Estimate?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("") }
    suspend fun reload() { exits = withContext(ioDispatcher) { Ark.exits() }; lost = withContext(ioDispatcher) { Ark.lostExits() } }
    LaunchedEffect(open) { if (open) reload() }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Start the emergency exit?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This takes ALL your Ark coins on-chain without the Ark server, using the recovery " +
                    "transactions each coin carries. Only do it if the server has disappeared or will " +
                    "not let you renew. While the server works, a normal withdrawal (SEND to a BTC " +
                    "address) is far cheaper and faster.", style = MaterialTheme.typography.bodySmall)
                Text("It cannot be undone once the last transaction is broadcast. It takes about 144 blocks (~1 day) plus " +
                    "confirmations, and the app must be opened to push it forward. Then you claim the BTC to an address of yours.",
                    style = MaterialTheme.typography.bodySmall, color = Warn)
                quote?.let { q ->
                    if (q.problem != null) Text(q.problem!!, style = MaterialTheme.typography.bodySmall, color = Bad)
                    else Text("Estimated cost: ${formatSats(q.fee)} sats (${q.note}). You get about " +
                        "${formatSats(q.amount)} of ${formatSats(q.total)} sats.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Type EXIT to confirm.", style = MaterialTheme.typography.bodySmall)
                Field(typed, { typed = it.uppercase() }, "EXIT")
            }
        },
        confirmButton = {
            TextButton(enabled = typed == "EXIT", onClick = {
                confirm = false; typed = ""
                ark.secured("Emergency exit", "Starting the emergency exit…") { Ark.startExitAll().let { "Emergency exit started: $it" } }
            }) { Text("START EXIT", color = Bad) }
        },
        dismissButton = { TextButton(onClick = { confirm = false; typed = "" }) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )

    Panel(accent = Bad) {
        LinkText((if (open) "▾ " else "▸ ") + "EMERGENCY EXIT (without the Ark server)", color = Bad) { open = !open }
        if (!open) return@Panel
        Text("Last resort, if ark.paperclippool.xyz disappears or stops renewing your coins: your coins carry signed " +
            "transactions that let you take them on-chain alone. It is slow (~1 day) and costs on-chain fees. While the " +
            "server works, withdraw normally instead.", style = MaterialTheme.typography.bodySmall, color = TextSoft)
        if (exits.isEmpty()) {
            PrimaryButton("START EMERGENCY EXIT…", Modifier.fillMaxWidth(), color = Bad) {
                scope.launch { quote = withContext(ioDispatcher) { Ark.estimateExit() }; confirm = true }
            }
        } else {
            exits.forEach { e ->
                val left = if (e.claimableHeight != null && e.tip != null) e.claimableHeight!! - e.tip!! else null
                val st = if (e.vtxo in lost) "lost: the coin expired and the server swept it" else when (e.type) {
                    "claimable" -> "ready to claim"
                    "awaiting-delta" -> "waiting the safety delay" + (left?.let { " · claimable in $it blocks (~${it * 10 / 60} h)" } ?: "")
                    "processing" -> "broadcasting its transactions"
                    "claimed", "claim-in-progress" -> "claimed"
                    else -> e.type
                }
                Text("${e.vtxo.take(12)}… · $st", style = MaterialTheme.typography.bodySmall, color = TextMain)
            }
            if (exits.any { it.type == "claimable" }) {
                Field(dest, { dest = it.trim() }, "your BTC address to receive it")
                PrimaryButton("CLAIM TO THIS ADDRESS", Modifier.fillMaxWidth(), enabled = dest.isNotBlank(), color = ArkRed) {
                    ark.secured("Claim the exit", "Claiming…") { Ark.claimExits(dest) }
                }
            }
            LinkText("refresh") { scope.launch { reload() } }
        }
    }
}

/** Copy the end of the Ark engine's log, for when a renewal or payment fails without saying why. */
@Composable
private fun ArkEngineLog(ark: ArkModel) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    Panel(accent = ArkRed) {
        SectionLabel("Engine log", ArkRed)
        Explain("If a renewal or payment fails, share this with whoever helps you: it says why. It shows coin ids and amounts, never your words.")
        SecondaryButton("COPY ENGINE LOG", Modifier.fillMaxWidth(), color = ArkRed) {
            scope.launch {
                val log = withContext(ioDispatcher) { Ark.engineLogTail() }
                if (log.isNullOrBlank()) ark.say("The Ark engine has not written a log yet.")
                else { clipboard.setText(AnnotatedString(log)); ark.say("Copied the engine log.") }
            }
        }
    }
}
