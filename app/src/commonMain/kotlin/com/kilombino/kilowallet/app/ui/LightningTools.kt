package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.LightningModel
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.i18n.Text
import com.kilombino.kilowallet.core.ln.Lnd
import com.kilombino.kilowallet.core.ln.Scb
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.wallet.formatSats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Lightning tab's tools after the Android app's ui/LightningScreen.kt (1.4.4): resizing a channel
// (splicing), closing it (cooperatively to a chosen address, or forced), speeding up a close (CPFP),
// paying on-chain from the node, the channel backup (export, and restore from one) and the node's
// settings (routing fee limit, name). The texts are Android's, so its translations apply.

/** Above this fee rate (sat/vB) a send asks to be confirmed once more (Android's HIGH_FEE_RATE). */
private const val HIGH_FEE_RATE = 200L

@Composable
private fun Radio(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = LnGold))
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMain)
    }
}

@Composable
private fun FeeGate(rate: Long, ok: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = ok, onCheckedChange = { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = Warn))
        Text("This fee is high: $rate sat/vB.", style = MaterialTheme.typography.bodySmall, color = Warn)
    }
}

internal fun spliceLine(sp: Lnd.Splice): String = when {
    sp.dead -> "resize failed: one of its coins was spent elsewhere. The channel keeps working at its old size; a new resize can't start until it closes."
    else -> {
        val delta = (if (sp.localDelta >= 0) "+" else "") + formatSats(sp.localDelta)
        val cap = formatSats(sp.newCapacity)
        val where = if (sp.state == "confirmed") "${sp.confirmations}/${sp.lockDepth}" else null
        if (where != null) "resizing $delta sats → $cap sats · $where confirmations · the channel keeps working meanwhile"
        else if (sp.state == "broadcast") "resizing $delta sats → $cap sats · waiting for its first confirmation · the channel keeps working meanwhile"
        else "resizing $delta sats → $cap sats · the channel keeps working meanwhile"
    }
}

/** Why a channel can't be resized now (lnrpc Channel.splice_status), in words. */
private fun spliceRefusal(code: String): String = when (code) {
    "peer_offline" -> "The other node is offline: it has to be online to resize."
    "peer_unsupported" -> "The other node doesn't support resizing channels."
    "no_unified_sigs", "taproot", "zero_conf" -> "This channel can't be resized (it was opened without the signatures resizing needs). Close it and open a new one."
    "pending" -> "A resize of this channel is already under way."
    "closing" -> "The channel is closing."
    "disabled" -> "Resizing is off on this node."
    "peer_not_locked" -> "The other node hasn't finished the last resize yet."
    "peer_misbehaved" -> "The other node broke an earlier resize: try again after restarting the app."
    "insufficient_funds" -> "Not enough on-chain coins in the node for that (FUND first)."
    "immature_inputs" -> "The node's on-chain coins need more confirmations first."
    "below_min" -> "That amount is too small."
    "above_max_out" -> "That's more than your side of the channel can give."
    "fee_below_floor" -> "That fee rate is below what the network relays now."
    "reserve_on_change" -> "The node must keep a reserve on-chain for its channels: try a little less."
    "close_address_set" -> "This channel only pays out to the address it was opened with."
    "node_cap" -> "Too many resizes at once on this node: try later."
    else -> "Not possible now ($code)."  // the code word is LND's
}

/**
 * RESIZE: more of the node's on-chain coins into the channel, or part of our side out of it,
 * without closing it (splicing). The channel keeps working while the new transaction confirms.
 */
@Composable
internal fun ResizeChannelDialog(c: Lnd.Channel, ln: LightningModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val resizable = c.spliceStatus == "ok" || c.spliceStatus == "peer_not_announcing"
    var out by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("2") }
    val rate = (fee.toLongOrNull() ?: 0).coerceAtLeast(0)
    var feeOk by remember { mutableStateOf(false) }
    var dest by remember { mutableStateOf(1) }   // 1 the node's coins, 2 another address
    var pasted by remember { mutableStateOf("") }
    val to = if (out && dest == 2) pasted.ifBlank { null } else null
    val sats = amount.toLongOrNull()
    var est by remember { mutableStateOf<Lnd.SpliceEstimate?>(null) }
    var estError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(out, sats, rate, to) {
        est = null; estError = null
        if (!resizable || rate < 1) return@LaunchedEffect
        delay(400)
        runCatching { withContext(ioDispatcher) { Lnd.estimateSplice(c.point, out, sats, rate, to) } }
            .onSuccess { est = it }.onFailure { estError = it.message }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDone() },
        title = { val name = c.peerAlias.ifBlank { c.peer.take(12) + "…" }; Text("Resize the channel with $name", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (!resizable) {
                    Text(spliceRefusal(c.spliceStatus.ifEmpty { "disabled" }), style = MaterialTheme.typography.bodySmall, color = Warn)
                    return@Column
                }
                Text("Now ${formatSats(c.capacity)} sats · yours ${formatSats(c.local)}", style = MaterialTheme.typography.bodySmall)
                Radio(!out, "Add from the node's on-chain coins") { out = false }
                Radio(out, "Take out part of your side") { out = true }
                Field(amount, { amount = it.filter(Char::isDigit) }, "sats (empty = as much as possible)", keyboard = KeyboardType.Number)
                if (out) {
                    Text("Send them to:", style = MaterialTheme.typography.bodySmall)
                    Radio(dest == 1, "The Lightning node's on-chain coins") { dest = 1 }
                    Radio(dest == 2, "Another address") { dest = 2 }
                    if (dest == 2) Field(pasted, { pasted = it.trim().removePrefix("bitcoin:").substringBefore('?') }, "bc1…")
                }
                Field(fee, { fee = it.filter(Char::isDigit) }, "Fee rate (sat/vB)", keyboard = KeyboardType.Number)
                if (rate > HIGH_FEE_RATE) FeeGate(rate, feeOk) { feeOk = !feeOk }
                est?.let { e ->
                    if (e.refusal.isNotEmpty()) Text(spliceRefusal(e.refusal), style = MaterialTheme.typography.bodySmall, color = Warn)
                    else {
                        val amt = formatSats(e.amount); val f = formatSats(e.fee); val r = e.satPerVbyte
                        Text(if (out) "Out: $amt sats · fee $f sats ($r sat/vB)" else "In: $amt sats · fee $f sats ($r sat/vB)",
                            style = MaterialTheme.typography.bodySmall, color = TextMain)
                        Text("Channel after: ${formatSats(e.newCapacity)} sats · yours ${formatSats(e.newLocal)}", style = MaterialTheme.typography.bodySmall, color = TextMain)
                        if (e.minSatPerVbyte > 0 && rate < e.minSatPerVbyte)
                            Text("The network wants at least ${e.minSatPerVbyte} sat/vB now.", style = MaterialTheme.typography.bodySmall, color = Warn)
                    }
                }
                estError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
                Text("The channel keeps working while the new transaction confirms. It takes the new size after " +
                    "${est?.lockDepth?.takeIf { it > 0 } ?: 6} confirmations." +
                    (if (!out) " The coins you add can pay through this peer only after 36 confirmations (about 6 hours): " +
                        "it waits that long so nobody can undo them with a chain reorganisation." else ""),
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
                msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (msg?.second == true) TextButton(onClick = onDone) { Text("OK", color = LnGold) }
            else if (resizable) TextButton(enabled = !busy && est != null && est!!.refusal.isEmpty() && rate >= 1 &&
                (!out || dest != 2 || pasted.length > 20) && (rate <= HIGH_FEE_RATE || feeOk), onClick = {
                busy = true; msg = null
                scope.launch {
                    runCatching { withContext(ioDispatcher) { Lnd.splice(c.point, out, sats, rate, to) } }
                        .onSuccess { msg = "Resize sent. It locks after its confirmations; the channel keeps working." to true; ln.refresh() }
                        .onFailure { msg = (it.message ?: "The resize failed") to false }
                    busy = false
                }
            }) { Text(if (busy) "RESIZING…" else if (out) "TAKE OUT" else "ADD", color = LnGold) }
        },
        dismissButton = { if (!busy && msg?.second != true) TextButton(onClick = onDone) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
}

/**
 * Closing a channel: cooperatively to the node's on-chain coins or a pasted address; or forced, for a
 * peer that is gone (the money then waits the channel's delay).
 */
@Composable
internal fun CloseChannelDialog(c: Lnd.Channel, ln: LightningModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dest by remember { mutableStateOf(1) }   // 1 node, 2 pasted
    var pasted by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("1") }
    val closeRate = fee.toLongOrNull() ?: 1
    var closeFeeOk by remember { mutableStateOf(false) }
    var force by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDone() },
        title = { val name = c.peerAlias.ifBlank { c.peer.take(12) + "…" }; Text("Close the channel with $name?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Your ${formatSats(c.local)} sats come back on-chain, minus the closing fee.", style = MaterialTheme.typography.bodySmall)
                if (!force) {
                    Text("Send them to:", style = MaterialTheme.typography.bodySmall)
                    Radio(dest == 1, "The Lightning node's on-chain coins") { dest = 1 }
                    Radio(dest == 2, "Another address") { dest = 2 }
                    if (dest == 2) Field(pasted, { pasted = it.trim().removePrefix("bitcoin:").substringBefore('?') }, "bc1…")
                    Field(fee, { fee = it.filter(Char::isDigit) }, "Fee rate (sat/vB)", keyboard = KeyboardType.Number)
                    if (closeRate > HIGH_FEE_RATE) FeeGate(closeRate, closeFeeOk) { closeFeeOk = !closeFeeOk }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = force, onCheckedChange = { force = it }, colors = CheckboxDefaults.colors(checkedColor = Bad))
                    Text("Force close (only if the other node is gone for good)", style = MaterialTheme.typography.bodySmall, color = TextMain)
                }
                if (force) Text("A forced close needs no answer from the peer, but your sats stay locked for the channel's delay (often 1–2 weeks) and the fee is set by the channel, not by you. It goes to the node's on-chain coins.",
                    style = MaterialTheme.typography.bodySmall, color = Warn)
                msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (msg?.second == true) TextButton(onClick = onDone) { Text("OK", color = LnGold) }
            else TextButton(enabled = !busy && (force || dest != 2 || pasted.length > 20) && (force || closeRate <= HIGH_FEE_RATE || closeFeeOk), onClick = {
                busy = true; msg = null
                val to = if (!force && dest == 2) pasted else null
                scope.launch {
                    runCatching { withContext(ioDispatcher) { Lnd.closeChannel(c.point, force, to, closeRate.coerceAtLeast(1)) } }
                        .onSuccess { msg = "Closing: transaction ${it.take(12)}…" to true; ln.refresh() }
                        .onFailure { msg = (it.message ?: "could not close") to false }
                    busy = false
                }
            }) { Text(if (busy) "CLOSING…" else if (force) "FORCE CLOSE" else "CLOSE", color = if (force) Bad else LnGold) }
        },
        dismissButton = { if (msg?.second != true) TextButton(enabled = !busy, onClick = onDone) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
}

/**
 * Speeding up a cooperative close stuck on a low fee. It can't be replaced (both nodes signed it,
 * and it doesn't signal RBF), so the node spends its own output of it with a child that pays for
 * both (CPFP).
 */
@Composable
internal fun SpeedUpCloseDialog(c: Lnd.Closing, ln: LightningModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var coin by remember { mutableStateOf<Lnd.CloseCoin?>(null) }
    var looked by remember { mutableStateOf(false) }
    LaunchedEffect(c.txid) {
        coin = withContext(ioDispatcher) { runCatching { Lnd.unconfirmedCoinOf(c.txid) }.getOrNull() }
        looked = true
    }
    var fee by remember { mutableStateOf("3") }
    val rate = fee.toLongOrNull() ?: 0
    var feeOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDone() },
        title = { Text("Speed up the close", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !looked -> Text("Looking for the node's coin in it…", style = MaterialTheme.typography.bodySmall)
                    coin == null -> Text("This close doesn't pay the node's on-chain coins. If it pays your BTC wallet, speed it up there: " +
                        "BTC tab, the incoming transaction, SPEED UP (CPFP).", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        val sats = formatSats(coin!!.sats)
                        Text("A cooperative close can't be replaced: both nodes signed it. Instead the node spends its $sats sats from it with a second transaction whose fee pays for both, so miners take the two together.",
                            style = MaterialTheme.typography.bodySmall)
                        Field(fee, { fee = it.filter(Char::isDigit) }, "Fee rate for both together (sat/vB)", keyboard = KeyboardType.Number)
                        if (rate > HIGH_FEE_RATE) FeeGate(rate, feeOk) { feeOk = !feeOk }
                    }
                }
                msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (msg?.second == true || (looked && coin == null)) TextButton(onClick = onDone) { Text("OK", color = LnGold) }
            else TextButton(enabled = !busy && coin != null && rate >= 1 && (rate <= HIGH_FEE_RATE || feeOk), onClick = {
                busy = true; msg = null
                scope.launch {
                    runCatching { withContext(ioDispatcher) { Lnd.speedUpClose(c.txid, c.capacity, rate) } }
                        .onSuccess { child -> msg = "Sent: the second transaction pays $child sat/vB, both together about $rate." to true; ln.refresh() }
                        .onFailure { msg = (it.message ?: "could not speed it up") to false }
                    busy = false
                }
            }) { Text(if (busy) "SENDING…" else "SPEED UP", color = LnGold) }
        },
        dismissButton = { if (msg?.second != true && coin != null) TextButton(enabled = !busy, onClick = onDone) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
}

/**
 * Pays from the node's on-chain coins, with coin control: to empty the node once done with
 * Lightning (after closing the channels), or to move a coin out. Every coin is ticked to start;
 * with no amount, everything ticked goes (minus the fee).
 */
@Composable
internal fun PayOnchainPanel(ln: LightningModel) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var coins by remember { mutableStateOf<List<Lnd.Utxo>?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var address by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("1") }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        coins = runCatching { withContext(ioDispatcher) { Lnd.utxos() } }.getOrDefault(emptyList())
        picked = coins.orEmpty().map { it.outpoint }.toSet()
    }
    val chosen = coins.orEmpty().filter { it.outpoint in picked }
    val total = chosen.sumOf { it.sats }
    val all = amount.isBlank()
    Panel(accent = LnGold) {
        SectionLabel("Pay on-chain from the node", LnGold)
        Explain("To empty the node when you are done with Lightning (close the channels first), or to move a coin out. Tick the coins to spend; with no amount, all of them go.")
        when {
            coins == null -> Text("Loading the node's coins…", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            coins!!.isEmpty() -> Text("The node has no on-chain coins.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
            else -> coins!!.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = u.outpoint in picked, onCheckedChange = { on -> picked = if (on) picked + u.outpoint else picked - u.outpoint },
                        colors = CheckboxDefaults.colors(checkedColor = LnGold))
                    Column {
                        Text(formatSats(u.sats) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
                        Text(u.outpoint.take(12) + "…:" + u.outpoint.substringAfter(':') + " · " +
                            (if (u.confirmations > 0) "${u.confirmations} conf" else "unconfirmed"),
                            style = MaterialTheme.typography.labelSmall, color = TextFaint)
                    }
                }
            }
        }
        Field(address, { address = it.trim() }, "Address (bc1…)")
        LinkText("PASTE", color = LnGold) { clip.getText()?.text?.trim()?.removePrefix("bitcoin:")?.substringBefore('?')?.let { address = it } }
        Field(amount, { amount = it.filter(Char::isDigit) }, "Sats (blank = all ticked: " + formatSats(total) + ")", keyboard = KeyboardType.Number)
        Field(fee, { fee = it.filter(Char::isDigit) }, "Fee rate (sat/vB)", keyboard = KeyboardType.Number)
        Explain("With open anchor channels, LND keeps a reserve on-chain for fee bumping, so it may not let you send everything.")
        PrimaryButton(if (busy) "SENDING…" else "SEND", Modifier.fillMaxWidth(),
            enabled = !busy && chosen.isNotEmpty() && address.length > 20 && (all || (amount.toLongOrNull() ?: 0) > 0), color = LnGold) { confirm = true }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    val sendRate = fee.toLongOrNull() ?: 1
    var sendFeeOk by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false; sendFeeOk = false },
        title = { val n = formatSats(amount.toLongOrNull() ?: 0); Text(if (all) "Send everything ticked?" else "Send $n sats?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                val n = chosen.size; val t = formatSats(total); val a = address.take(14); val z = address.takeLast(6)
                Text("From $n coin(s) ($t sats) of the Lightning node to $a…$z at $sendRate sat/vB.", style = MaterialTheme.typography.bodySmall)
                if (all) Text("The fee comes out of the amount.", style = MaterialTheme.typography.bodySmall)
                if (sendRate > HIGH_FEE_RATE) FeeGate(sendRate, sendFeeOk) { sendFeeOk = !sendFeeOk }
            }
        },
        confirmButton = {
            TextButton(enabled = sendRate <= HIGH_FEE_RATE || sendFeeOk, onClick = {
                confirm = false; busy = true; msg = null
                scope.launch {
                    runCatching {
                        withContext(ioDispatcher) {
                            Lnd.sendCoins(address.removePrefix("bitcoin:"), amount.toLongOrNull() ?: 0, sendRate.coerceAtLeast(1), all, chosen.map { it.outpoint })
                        }
                    }.onSuccess {
                        msg = "Sent." to true; ln.refresh()
                        coins = runCatching { withContext(ioDispatcher) { Lnd.utxos() } }.getOrNull()
                    }.onFailure { msg = (it.message ?: "could not send") to false }
                    busy = false
                }
            }) { Text("SEND", color = LnGold) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
}

/**
 * The channel backup: export it on its own (for a restore without the full backup file), and with
 * the node running hand it one from a phone that is gone, so the channels in it get closed and paid
 * back. Before that, the backup is opened with the node's keys and its channels listed.
 */
@Composable
internal fun ChannelBackupPanel(model: WalletModel, ln: LightningModel, platform: AppPlatform) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<String?>(null) }
    var file by remember { mutableStateOf<ByteArray?>(null) }
    var inside by remember { mutableStateOf<List<Scb.Channel>?>(null) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Panel(accent = LnGold) {
        SectionLabel("Export channel backup (SCB)", LnGold)
        Explain("The static channel backup: a small file, encrypted with the node's keys, that lists your channels and their peers. It does NOT keep the channels open. If this phone is lost and you have no up-to-date move file, your recovery words plus this file get the money back: the new node asks each peer to close the channel and the funds come back on-chain after their time lock. Save a new one after each channel you open or close; the backup file in Settings carries the latest one too.")
        LinkText("EXPORT SCB", color = LnGold) {
            saved = null
            scope.launch {
                runCatching { withContext(ioDispatcher) { Lnd.channelBackup() } }
                    .onSuccess { b -> platform.saveFile("kilowallet-channel.backup", b) { err ->
                        saved = if (err == null) "Channel backup saved. Save a new one after each channel you open or close." else "Error: " + err } }
                    .onFailure { saved = "Error: " + (it.message ?: "") }
            }
        }
        saved?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Error")) Bad else Good) }
    }
    Panel(accent = LnGold) {
        SectionLabel("Restore channels from an SCB", LnGold)
        Explain("For channels of an old phone that is gone (same recovery words): this node asks each of their peers to close them, and the money comes back on-chain after the time lock.")
        Text("Every channel in that file gets closed. Only if the old phone no longer runs Lightning.", style = MaterialTheme.typography.bodySmall, color = Warn)
        LinkText("LOAD SCB AND CLOSE ITS CHANNELS", color = LnGold) {
            msg = null
            platform.openFile { b ->
                when {
                    b == null -> {}
                    b.size < 32 || b.size > 5_000_000 -> msg = "That does not look like a channel backup." to false
                    else -> model.readChannelBackup(b) { r ->
                        r.onSuccess { file = b; inside = it }
                            .onFailure { msg = (if (it is Scb.WrongSeed) "This channel.backup was not made by this LND seed." else it.message ?: "could not read the file") to false }
                    }
                }
            }
        }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    val b = file
    val chans = inside
    if (b != null && chans != null) AlertDialog(onDismissRequest = { file = null; inside = null },
        title = { Text("Close the channels in this backup?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val n = chans.size; val cap = formatSats(chans.sumOf { it.capacity })
                Text("$n channel(s) · $cap sats of capacity", style = MaterialTheme.typography.bodySmall, color = TextMain)
                chans.forEach { c ->
                    val peer = c.remoteNode.take(16); val cap = formatSats(c.capacity); val scid = c.shortChannelId
                    Text("$peer…  $cap sats · $scid", style = MaterialTheme.typography.labelSmall, color = TextSoft)
                }
                Text("The node asks each peer to close them and the money comes back on-chain. This cannot be undone.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = chans.isNotEmpty(), onClick = {
                file = null; inside = null
                scope.launch {
                    runCatching { withContext(ioDispatcher) { Lnd.restoreChannelBackups(b) } }
                        .onSuccess { msg = "Done: the peers are being asked to close. The money shows on-chain in the node after their time lock." to true; ln.refresh() }
                        .onFailure { msg = (it.message ?: "the node refused the backup") to false }
                }
            }) { Text("CLOSE THEM", color = Warn) }
        },
        dismissButton = { TextButton(onClick = { file = null; inside = null }) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
}

/** The routing fee limit and the node's name (Android's FeeLimitPanel and AliasPanel). */
@Composable
internal fun LnSettingsPanels(ln: LightningModel, current: String) {
    val scope = rememberCoroutineScope()
    var pct by remember { mutableStateOf(ln.feePercent()) }
    var min by remember { mutableStateOf(ln.feeMinSats().toString()) }
    Panel(accent = LnGold) {
        SectionLabel("Routing fee limit", LnGold)
        Explain("The most a payment may cost in routing fees: a share of what you pay, never less than the minimum. A payment that would cost more is not sent. Example: " +
            formatSats(ln.feeLimit(10_000)) + " sats on 10,000.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.5, 1.0, 2.0, 3.0, 5.0).forEach { v ->
                val on = kotlin.math.abs(pct - v) < 0.01
                Chip((if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) + "%", on, color = LnGold) { pct = v; ln.setFeeLimit(v, min.toLongOrNull() ?: 10L) }
            }
        }
        Field(min, { v -> min = v.filter(Char::isDigit).take(5); ln.setFeeLimit(pct, min.toLongOrNull() ?: 0L) },
            "Minimum (sats, up to ${LightningModel.MAX_FEE_MIN})", keyboard = KeyboardType.Number)
    }
    var alias by remember { mutableStateOf(ln.alias()) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    Panel(accent = LnGold) {
        SectionLabel("Node name", LnGold)
        Explain("What peers see your node as. Now: " + current.ifBlank { "—" } + ".")
        Field(alias, { alias = it.take(32); saved = null }, "name")
        LinkText("SAVE", color = LnGold) {
            if (alias.isBlank()) return@LinkText
            ln.setAlias(alias)
            scope.launch { saved = Lnd.running && runCatching { withContext(ioDispatcher) { Lnd.rename(ln.alias()) } }.isSuccess }
        }
        when (saved) {
            true -> Text("Saved and in use.", style = MaterialTheme.typography.bodySmall, color = Good)
            false -> Text("Saved: it takes effect when the node next starts.", style = MaterialTheme.typography.bodySmall, color = Good)
            null -> {}
        }
    }
}
