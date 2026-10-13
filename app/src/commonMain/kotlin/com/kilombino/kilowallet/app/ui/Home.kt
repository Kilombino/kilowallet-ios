package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.input.KeyboardType
import com.kilombino.kilowallet.app.AmountUnit
import com.kilombino.kilowallet.app.HistoryFilter
import com.kilombino.kilowallet.app.filterHistory
import com.kilombino.kilowallet.app.TransactionNote
import com.kilombino.kilowallet.app.syncTimeText
import com.kilombino.kilowallet.core.wallet.PaymentRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.ScanPhase
import com.kilombino.kilowallet.app.Screen
import com.kilombino.kilowallet.app.UiState
import com.kilombino.kilowallet.app.WalletKind
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.core.chain.Chain
import com.kilombino.kilowallet.core.wallet.TxConf
import com.kilombino.kilowallet.core.wallet.formatBtc
import com.kilombino.kilowallet.core.wallet.formatSats
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(model: WalletModel, state: UiState, platform: AppPlatform) {
    // While the wallet is on screen it re-checks every minute (Android does the same in the foreground).
    LaunchedEffect(state.zpub) { while (true) { delay(60_000); model.refresh() } }
    var detail by remember(state.walletId) { mutableStateOf<TxConf?>(null) }
    var exportConfirm by remember { mutableStateOf(platform.demoScreen == "export") }
    LaunchedEffect(platform.demoScreen, state.txs) {
        if (platform.demoScreen == "note") detail = state.txs.firstOrNull()
    }
    var query by remember(state.walletId, state.hideAmounts) { mutableStateOf("") }
    var filter by remember(state.walletId) { mutableStateOf(HistoryFilter.ALL) }
    var limit by remember(state.walletId, query, filter) { mutableStateOf(50) }
    var searchOpen by remember { mutableStateOf(platform.demoScreen == "filters") }
    val matching = filterHistory(state.txs, if (state.hideAmounts) emptyMap() else state.notes, query, filter)
    val hot = state.kind == WalletKind.HOT

    ScreenColumn {
        LinkText(state.walletName + "  ▾ wallets", color = TextMain) { model.go(Screen.Wallets) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (hot) Purple else Orange).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(if (hot) "HOT WALLET" else "WATCH-ONLY", style = MaterialTheme.typography.labelLarge, color = Ink)
            }
            Spacer(Modifier.weight(1f))
            LinkText("settings", color = TextMain) { model.go(Screen.Settings) }
            Spacer(Modifier.width(8.dp))
            Logo(40)
        }

        LinkText(if (state.hideAmounts) "Show amounts" else "Hide amounts", color = TextSoft) { model.toggleHideAmounts() }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Your ${Chain.COIN}")
                Spacer(Modifier.weight(1f))
                val live = state.scan is ScanPhase.Done
                LinkText(if (state.refreshing) "refreshing…" else "↻ refresh", color = TextSoft) { model.refresh() }
                PulseDot(if (live) Good else if (state.scan is ScanPhase.Error) Bad else Warn)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (state.hideAmounts) "••••" else state.amountUnit.number(state.total), fontSize = if (state.amountUnit == AmountUnit.XBT) 28.sp else BigNumber, fontWeight = FontWeight.Bold, color = Purple,
                    style = MaterialTheme.typography.displayLarge)
                Text(" " + state.amountUnit.label, style = MaterialTheme.typography.titleLarge, color = Purple.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(if (state.hideAmounts) "Balance hidden" else if (state.amountUnit == AmountUnit.SATS) "${formatBtc(state.total)} ${Chain.COIN} (${Chain.TICKER})" else "${formatSats(state.total)} sats", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
            if (!state.hideAmounts && state.unconfirmed != 0L) Text("of which ${state.amountUnit.text(state.unconfirmed)} unconfirmed",
                style = MaterialTheme.typography.bodySmall, color = Warn)
            Text(state.lastSyncedAt?.let { "Last successful sync: " + syncTimeText(it) }
                ?: "No successful sync this session", style = MaterialTheme.typography.bodySmall, color = TextSoft)
            when (val s = state.scan) {
                is ScanPhase.Scanning -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GapRing(s.gap, s.gapLimit, Purple)
                    Column {
                        Text(if (s.path.isEmpty()) "connecting to ${state.endpoint}…" else s.path,
                            style = MaterialTheme.typography.bodyMedium, color = Purple)
                        Text("${if (s.chainIndex == 0) "receive" else "change"} addresses · ${s.found} used so far",
                            style = MaterialTheme.typography.bodySmall, color = TextFaint)
                    }
                }
                is ScanPhase.Error -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = Bad)
                else -> Unit
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Receive", Modifier.weight(1f)) { model.go(Screen.Receive) }
            if (hot) PrimaryButton("Send", Modifier.weight(1f), enabled = state.scan is ScanPhase.Done && state.total > 0) { model.go(Screen.Send) }
        }
        if (!hot) Explain("Watch-only: this wallet can receive and show its coins, but not spend them.")

        Panel {
            SectionLabel("Movements · confirmations")
            LinkText(if (searchOpen) "− Search and filters" else "+ Search and filters") { searchOpen = !searchOpen }
            if (searchOpen) {
                Field(query, { query = it.take(200) }, if (state.hideAmounts) "Search transaction ID" else "Search ID, label or note")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HistoryFilter.entries.forEach { option ->
                        LinkText((if (filter == option) "✓ " else "") + option.name.lowercase().replaceFirstChar { it.uppercase() }) { filter = option }
                    }
                }
                Explain("${matching.size} of ${state.txs.size} loaded movements")
            }
            if (state.txs.isEmpty()) {
                Explain(if (state.scan is ScanPhase.Done) "No movements yet. Receive to an address from RECEIVE." else "…")
            } else {
                Explain("tap a movement for its details")
                if (matching.isEmpty()) Explain("No matching movements.")
                matching.take(limit).forEach { tx -> MovementRow(tx, state.hideAmounts, state.notes[tx.txid], state.amountUnit) { detail = tx } }
                if (matching.size > limit) LinkText("Show 50 more") { limit += 50 }
            }
        }
        SecondaryButton("Export history CSV", Modifier.fillMaxWidth(), enabled = state.lastSyncedAt != null) { exportConfirm = true }
        if (state.hideAmounts) Explain("Balance, history amounts and notes are hidden. Payment review and CSV exports show amounts.")
        Explain("block ${formatSats(state.height.toLong())} · ${state.endpoint}" + (state.serverVersion?.let { " · $it" } ?: ""))
    }

    detail?.let { tx -> MovementDialog(tx, state.hideAmounts, state.notes[tx.txid] ?: TransactionNote(), state.amountUnit,
        onSave = { label, note -> model.saveTransactionNote(state.walletId ?: "", tx.txid, label, note) },
        onClose = { detail = null }) }
    if (exportConfirm) AlertDialog(
        onDismissRequest = { exportConfirm = false },
        title = { Text("Export history?") },
        text = { Text("Export the loaded history for ${state.walletName}, including amounts, labels and notes—even when hidden on screen. " +
            "This is a snapshot from the last successful sync, not a tax report. Choose where to save or share the CSV.") },
        confirmButton = { TextButton(onClick = { exportConfirm = false; model.exportHistory() }) { Text("EXPORT", color = Purple) } },
        dismissButton = { TextButton(onClick = { exportConfirm = false }) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

@Composable
private fun MovementRow(tx: TxConf, hidden: Boolean, note: TransactionNote?, unit: AmountUnit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val a = tx.amount
            Text(if (hidden) "•••• " + unit.label else if (a == null) "? " + unit.label else (if (a > 0) "+" else "") + unit.text(a),
                style = MaterialTheme.typography.bodyLarge, color = if (a != null && a > 0) Good else TextMain)
            if (!hidden && !note?.label.isNullOrEmpty()) Text(note.label,
                style = MaterialTheme.typography.bodyMedium, color = TextMain, maxLines = 2)
            Text(shortTxid(tx.txid), style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
        ConfBadge(tx)
    }
}

@Composable
private fun ConfBadge(tx: TxConf) {
    val (text, color) = when {
        tx.pending -> "pending" to Warn
        tx.confirmations < 6 -> "${tx.confirmations} conf" to Warn
        else -> "${formatSats(tx.confirmations.toLong())} conf" to Good
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        Text(if (tx.pending) "…" else "✓", style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun MovementDialog(tx: TxConf, hidden: Boolean, saved: TransactionNote, unit: AmountUnit,
    onSave: (String, String) -> Boolean, onClose: () -> Unit) {
    var label by remember(tx.txid) { mutableStateOf(saved.label) }
    var note by remember(tx.txid) { mutableStateOf(saved.note) }
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (hidden) "Movement" else tx.amount?.let { (if (it > 0) "+" else "") + unit.text(it) } ?: "Movement", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyValue("status", if (tx.pending) "in the mempool" else "${formatSats(tx.confirmations.toLong())} confirmations")
                if (!tx.pending) KeyValue("block", formatSats(tx.height.toLong()))
                tx.spv?.let {
                    KeyValue("payment proof", if (it) "✓ matches one server-supplied header" else "✗ did NOT verify",
                        if (it) Good else Bad)
                }
                if (!hidden) {
                    Field(label, { label = it.take(80) }, "Label")
                    Field(note, { note = it.take(500) }, "Note", singleLine = false, minLines = 3)
                    Explain("Stored locally for this wallet. Not included in your recovery words backup.")
                } else Explain("Show amounts on the home screen to view or edit notes.")
                Text(tx.txid, style = MaterialTheme.typography.bodySmall, color = TextSoft)
                LinkText("copy txid", color = Purple) { clipboard.setText(AnnotatedString(tx.txid)) }
                LinkText("open on ${Chain.EXPLORER.removePrefix("https://")}", color = Purple) {
                    runCatching { uri.openUri("${Chain.EXPLORER}/tx/${tx.txid}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (hidden || onSave(label, note)) onClose() }) {
            Text(if (hidden) "CLOSE" else "SAVE", color = Purple) } },
        dismissButton = { if (!hidden) TextButton(onClick = onClose) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextMain,
    )
}

@Composable
fun ReceiveScreen(model: WalletModel, state: UiState, platform: AppPlatform) {
    val clipboard = LocalClipboardManager.current
    val addr = remember { model.receiveAddress() }
    var amount by remember { mutableStateOf(if (platform.demoScreen == "request") "120000" else "") }
    var sharing by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(2000); copied = null } }
    ScreenColumn {
        BackLink { model.back() }
        Text("Receive ${Chain.COIN}", style = MaterialTheme.typography.titleLarge, color = TextMain)
        if (addr == null) { Explain("No wallet."); return@ScreenColumn }
        val (address, path) = addr
        val wantsAmount = amount.isNotEmpty()
        val sats = state.amountUnit.parse(amount)
        val valid = !wantsAmount || (sats != null && sats in 1..PaymentRequest.MAX_REQUEST_SATS)
        val payload = if (valid) PaymentRequest.create(address, sats.takeIf { wantsAmount }) else null
        Field(amount, { amount = it.take(32); copied = null }, "Requested amount · ${state.amountUnit.label} (optional)", keyboard = KeyboardType.Decimal)
        if (!valid) Explain("Enter a positive amount up to 21 million XBT; at most 8 decimal places in XBT mode.", color = Bad)
        if (valid && wantsAmount) Explain("Request: ${state.amountUnit.text(sats!!)}", color = Purple)
        Panel {
            val qr = remember(payload) { payload?.let(platform::qrImage) }
            if (qr != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(14.dp)) {
                    Image(qr, contentDescription = if (wantsAmount) "XBT payment request QR" else "QR code of the address",
                        modifier = Modifier.size(240.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                }
            }
            Text(chunked(address), style = MaterialTheme.typography.titleMedium, color = TextMain,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(path, style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
        if (wantsAmount) PrimaryButton(if (copied == "request") "Copied" else "Copy payment request", Modifier.fillMaxWidth(), enabled = valid) {
            payload?.let { clipboard.setText(AnnotatedString(it)); copied = "request" }
        }
        SecondaryButton(if (copied == "address") "Copied" else "Copy address", Modifier.fillMaxWidth()) {
            clipboard.setText(AnnotatedString(address)); copied = "address"
        }
        SecondaryButton(if (sharing) "Opening share sheet…" else "Share QR and request", Modifier.fillMaxWidth(), enabled = valid && !sharing) {
            payload?.let { request ->
                sharing = true
                try { platform.sharePayment(request) { error -> sharing = false; shareError = error } }
                catch (e: Exception) { sharing = false; shareError = "Could not open the share sheet." }
            }
        }
        shareError?.let { Explain(it, color = Bad) }
        Explain("Only send XBT on the BLAKE2b network.", color = TextSoft)
        if (wantsAmount) Explain("Use an XBT-compatible wallet. Other Bitcoin wallets may ignore the request's chain label.")
        Explain("Previously issued addresses still work.")
    }
}

internal val HugeNumber = 40.sp
