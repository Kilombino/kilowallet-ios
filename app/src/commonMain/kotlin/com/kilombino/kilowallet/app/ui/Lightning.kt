package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.LightningModel
import com.kilombino.kilowallet.app.UiState
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.i18n.Text
import com.kilombino.kilowallet.core.ln.Inbound
import com.kilombino.kilowallet.core.ln.Kilombino
import com.kilombino.kilowallet.core.ln.Lnd
import com.kilombino.kilowallet.core.platform.ioDispatcher
import com.kilombino.kilowallet.core.wallet.formatSats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The LIGHTNING tab, as Android 1.4.4's `ui/LightningScreen.kt`: the intro and its warnings while
 * the node is off, the steps while it starts, and the node itself (balances, FUND / CHANNEL /
 * INVOICE / PAY, channels, inbound liquidity from Kilombino's node). iPhone notes on top: the node
 * runs only with the app open, and the watchtower can't be reached yet without Tor.
 */
@Composable
fun LightningTab(model: WalletModel, state: UiState, platform: AppPlatform) {
    val ln = model.lightning
    val status by ln.status.collectAsState()
    DisposableEffect(Unit) { ln.watch(true); onDispose { ln.watch(false) } }
    when (val s = status) {
        LightningModel.Status.Off -> LnIntro(ln) { model.enableLightning() }
        is LightningModel.Status.Starting -> LnStarting(s, ln)
        is LightningModel.Status.Failed -> LnFailed(s, ln)
        is LightningModel.Status.Running -> LnNode(s, ln, state, platform, model)
    }
}

/** What iOS does not let the node do: it is said once, plainly, wherever the node is shown. */
@Composable
private fun IphoneLimits(ln: LightningModel) {
    Panel(accent = Warn) {
        SectionLabel("On iPhone", Warn)
        listOf(
            "The node runs only while Kilowallet is open. iOS stops apps in the background.",
            "To receive, keep the app open until you are paid. A reusable offer works whenever the app is open.",
            "If you leave the app while paying, iOS gives it about 30 seconds more; a payment still on its way is followed again when you come back.",
            ln.towerNote,
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = TextSoft) }
    }
}

@Composable
private fun LnIntro(ln: LightningModel, onTurnOn: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf(ln.alias()) }
    Panel(accent = LnGold) {
        SectionLabel("Lightning · beta", LnGold)
        Text("Your own Lightning node, on this phone", style = MaterialTheme.typography.titleMedium, color = TextMain)
        Explain("It runs LND for the BLAKE2b chain with neutrino: it follows the chain itself from compact block filters, checks the proof of work, and keeps the channels and their keys here. Nobody holds your money. The keys come from your recovery words.")
    }
    Panel(accent = Warn) {
        SectionLabel("Before you turn it on", Warn)
        listOf(
            "BETA. Put in only what you can afford to lose.",
            "NEVER run Lightning with these words on two phones or profiles at once. Two nodes with the same keys break the channels and can lose their money. (Ark can be copied back; Lightning cannot.)",
            "To receive, the phone must be online with the app open. Paying needs a channel with enough balance.",
            "Opening and closing a channel are on-chain transactions with their fees.",
            "The first start downloads a few hundred MB.",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = TextSoft) }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it },
                colors = CheckboxDefaults.colors(checkedColor = LnGold, uncheckedColor = TextFaint, checkmarkColor = Ink))
            Text("I understand, it is a beta", style = MaterialTheme.typography.bodySmall, color = TextMain)
        }
        Field(alias, { alias = it.take(32) }, "Your node's name (peers see it)")
        if (!ln.available) Text("This build has no Lightning engine.", style = MaterialTheme.typography.bodySmall, color = Warn)
        PrimaryButton("TURN ON LIGHTNING", Modifier.fillMaxWidth(), enabled = agreed && ln.available, color = LnGold) {
            ln.setAlias(alias); onTurnOn()
        }
    }
    IphoneLimits(ln)
}

@Composable
private fun LnStarting(s: LightningModel.Status.Starting, ln: LightningModel) {
    Panel(accent = LnGold) {
        SectionLabel("Lightning · beta", LnGold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(Warn); Spacer(Modifier.width(8.dp))
            Text(s.step.replaceFirstChar { it.uppercase() } + "…", style = MaterialTheme.typography.titleMedium, color = TextMain)
        }
        val tip = ln.chainTip
        s.height?.let { h ->
            Text(if (tip > h) "block ${formatSats(h.toLong())} of ${formatSats(tip.toLong())}" else "block ${formatSats(h.toLong())}",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        Explain("The first start downloads the chain's block headers and filters: it takes a while. Keep the app open.")
    }
    IphoneLimits(ln)
}

@Composable
private fun LnFailed(s: LightningModel.Status.Failed, ln: LightningModel) {
    val clipboard = LocalClipboardManager.current
    Panel(accent = Bad) {
        SectionLabel("Lightning did not start", Bad)
        Text(s.message, style = MaterialTheme.typography.bodySmall, color = TextMain)
        PrimaryButton("TRY AGAIN", Modifier.fillMaxWidth(), color = LnGold) { ln.resume() }
        ln.logTail()?.let { log ->
            SecondaryButton("COPY NODE LOG", Modifier.fillMaxWidth(), color = LnGold) { clipboard.setText(AnnotatedString(log)) }
            Explain("The log names peers and channels, never keys.")
        }
    }
}

private enum class LnSheet { None, Fund, Open, Receive, Pay }

@Composable
private fun LnNode(s: LightningModel.Status.Running, ln: LightningModel, state: UiState, platform: AppPlatform, model: WalletModel) {
    var sheet by remember { mutableStateOf(LnSheet.None) }
    var closing by remember { mutableStateOf<Lnd.Channel?>(null) }
    var resizing by remember { mutableStateOf<Lnd.Channel?>(null) }
    var speeding by remember { mutableStateOf<Lnd.Closing?>(null) }
    val i = s.info
    val b = s.balances
    val hide = { t: String -> if (state.hidden) MASK else t }
    val received by ln.received.collectAsState()
    received?.let { (memo, sats) ->
        AlertDialog(onDismissRequest = { ln.clearReceived() },
            title = { Text("⚡ Received ${formatSats(sats)} sats", style = MaterialTheme.typography.titleMedium) },
            text = { if (memo.isNotBlank()) Text("“$memo”", style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { ln.clearReceived() }) { Text("OK", color = LnGold) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
    }
    closing?.let { c -> CloseChannelDialog(c, ln) { closing = null } }
    resizing?.let { c -> ResizeChannelDialog(c, ln) { resizing = null } }
    speeding?.let { c -> SpeedUpCloseDialog(c, ln) { speeding = null } }

    Panel(accent = LnGold) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(if (i.syncedChain) Good else Warn); Spacer(Modifier.width(8.dp))
            val atTip = s.tip > 0 && i.height >= s.tip
            val sync = when { i.syncedChain -> "Synced"; atTip -> "Checking the wallet's coins"; else -> "Syncing" }
            val tip = maxOf(s.tip, i.height)
            val h = if (tip > 0 && !i.syncedChain && !atTip) "block ${formatSats(i.height.toLong())} of ${formatSats(tip.toLong())}" else "block ${formatSats(i.height.toLong())}"
            Text("$sync · $h · ${i.peers} peer" + (if (i.peers == 1) "" else "s"), style = MaterialTheme.typography.bodySmall,
                color = TextSoft, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        val lnSats = b?.channels ?: 0
        val chain = (b?.onchainConfirmed ?: 0) + (b?.onchainUnconfirmed ?: 0)
        val closingSats = s.closing.sumOf { it.limbo }
        val known = i.syncedChain
        Text(if (known) hide("${formatSats(lnSats + chain + closingSats + (b?.pendingOpen ?: 0))} sats") else "– sats",
            style = MaterialTheme.typography.headlineMedium, color = LnGold)
        Text("in the Lightning node", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        Spacer(Modifier.height(8.dp))
        BalanceLine("⚡ Lightning (in channels)", lnSats, TextMain, hide)
        BalanceLine("⛓ On-chain in the node", b?.onchainConfirmed ?: 0, TextSoft, hide,
            (b?.onchainUnconfirmed ?: 0).takeIf { it > 0 && known }?.let { "+" + hide(formatSats(it)) + " unconfirmed" }, known = known)
        if ((b?.pendingOpen ?: 0) > 0) BalanceLine("⏳ Opening channels", b!!.pendingOpen, Warn, hide)
        if (closingSats > 0) BalanceLine("⏳ Closing channels", closingSats, Warn, hide)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(("⛓" to "FUND") to LnSheet.Fund, ("🤝" to "CHANNEL") to LnSheet.Open,
            ("⚡" to "INVOICE") to LnSheet.Receive, ("📤" to "PAY") to LnSheet.Pay).forEach { (t, v) ->
            val on = sheet == v
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) LnGold else LnGold.copy(alpha = 0.25f))
                    .clickable { sheet = if (on) LnSheet.None else v }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.first, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(t.second, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, color = if (on) Ink else TextMain)
            }
        }
    }

    val book = remember(s) { ledger(s) }
    when (sheet) {
        LnSheet.Fund -> FundSheet(ln, platform)
        LnSheet.Open -> OpenSheet(ln, b?.onchainConfirmed ?: 0)
        LnSheet.Receive -> {
            ReceiveLn(ln, platform, i.syncedChain && s.channels.any { it.active })
            ReusableOffer(ln, platform)
            Movements(book.filter { it.delta > 0 }, "Invoices paid to you", hide)
        }
        LnSheet.Pay -> { PayLn(ln, platform, s.channels); Movements(book.filter { it.delta < 0 }, "Payments you made", hide) }
        LnSheet.None -> {}
    }

    if (s.pending.isNotEmpty() || s.channels.isNotEmpty() || s.closing.isNotEmpty()) Panel(accent = LnGold) {
        SectionLabel("Channels (${s.channels.size}" + (if (s.pending.isNotEmpty()) " · ${s.pending.size} opening" else "") +
            (if (s.closing.isNotEmpty()) " · ${s.closing.size} closing" else "") + ")", LnGold)
        s.pending.forEach { p ->
            Text(p.peer.take(16) + "…  " + hide(formatSats(p.capacity)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Text("opening" + (if (p.confirmationsLeft > 0) " · ${p.confirmationsLeft} confirmations left" else " · waiting for the first confirmation"),
                style = MaterialTheme.typography.bodySmall, color = Warn)
        }
        s.channels.forEach { c ->
            Spacer(Modifier.height(4.dp))
            val name = c.peerAlias.ifBlank { c.peer.take(16) + "…" }
            Text(name + "  " + hide(formatSats(c.capacity)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Text((if (c.active) "active" else "offline") + " · yours " + hide(formatSats(c.local)) + " · can receive " + hide(formatSats(c.remote)),
                style = MaterialTheme.typography.bodySmall, color = if (c.active) TextSoft else Warn)
            if (!c.active && c.peer == Kilombino.NODE) ln.linkError?.let { e ->
                val starting = e.lines().all { "still in the process of starting" in it || "starting" in it }
                Text(if (starting) "The node is still starting: it connects by itself in a minute or two."
                     else "Cannot reach it yet; it keeps trying. " + e.lines().first().substringAfter("desc = "),
                    style = MaterialTheme.typography.labelSmall, color = TextFaint)
            }
            c.splice?.let { sp -> Text(spliceLine(sp), style = MaterialTheme.typography.bodySmall, color = if (sp.dead) Bad else Warn) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (c.splice == null) TextButton(onClick = { resizing = c }, contentPadding = PaddingValues(0.dp)) {
                    Text("RESIZE", color = LnGold, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { closing = c }, contentPadding = PaddingValues(0.dp)) {
                    Text("CLOSE CHANNEL", color = LnGold, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        s.closing.forEach { c ->
            Text(c.peer.take(16) + "…  " + hide(formatSats(c.capacity)) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Text(c.note + " · yours " + hide(formatSats(c.limbo)), style = MaterialTheme.typography.bodySmall, color = Warn)
            if (c.waiting && c.txid.isNotEmpty()) TextButton(onClick = { speeding = c }, contentPadding = PaddingValues(0.dp)) {
                Text("SPEED UP", color = LnGold, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    InboundPanel(ln, s)
    if ((b?.onchainConfirmed ?: 0) + (b?.onchainUnconfirmed ?: 0) > 0) PayOnchainPanel(ln)
    ChannelBackupPanel(model, ln, platform)
    LnSettingsPanels(ln, i.alias)
    IphoneLimits(ln)
    Panel(accent = Warn) {
        Explain("Beta. Never run Lightning with these words on another phone or profile at the same time. Node: ${i.pubkey.take(20)}…")
    }
}

@Composable
private fun BalanceLine(label: String, sats: Long, color: Color, hide: (String) -> String, note: String? = null, known: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
        Text((if (known) hide(formatSats(sats)) else "–") + " sats", style = MaterialTheme.typography.bodySmall, color = color)
    }
    note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = TextFaint) }
}

/** A QR code on white, tappable to copy what it says. */
@Composable
private fun LnQr(platform: AppPlatform, text: String, copy: String = text, size: Int = 240) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(copy) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val qr = remember(text) { platform.qrImage(text) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (qr != null) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)
            .clickable { clipboard.setText(AnnotatedString(copy)); copied = true }) {
            Image(qr, contentDescription = "QR code", modifier = Modifier.size(size.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
        }
        Text(if (copy.length > 44) copy.take(40) + "…" else copy, style = MaterialTheme.typography.bodySmall, color = TextSoft)
        TextButton(onClick = { clipboard.setText(AnnotatedString(copy)); copied = true }) {
            Text(if (copied) "COPIED ✓" else "COPY", color = if (copied) Good else LnGold)
        }
    }
}

@Composable
private fun FundSheet(ln: LightningModel, platform: AppPlatform) {
    var addr by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var coins by remember { mutableStateOf<List<Lnd.Utxo>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { withContext(ioDispatcher) { Lnd.newAddress() } }
            .onSuccess { addr = it; ln.rememberFundAddress(it) }.onFailure { err = it.message }
        while (true) {
            coins = runCatching { withContext(ioDispatcher) { Lnd.utxos() } }.getOrDefault(coins)
            delay(15_000)
        }
    }
    Panel(accent = LnGold) {
        SectionLabel("Fund the node", LnGold)
        Explain("Channels are opened from the node's own on-chain coins. Send to this address from the BTC tab (or anywhere), then open a channel once it confirms.")
        addr?.let { LnQr(platform, "bitcoin:$it", it, 200) }
        err?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
    }
    val incoming = ln.incoming
    if (coins.isNotEmpty() || incoming.isNotEmpty()) Panel(accent = LnGold) {
        SectionLabel("Coins in the node (${coins.size})", LnGold)
        incoming.forEach { t -> Text("+" + formatSats(t.sats) + " sats · incoming, unconfirmed", style = MaterialTheme.typography.bodySmall, color = Warn) }
        coins.forEach { u ->
            Text(formatSats(u.sats) + " sats", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Text(u.outpoint.take(12) + "…:" + u.outpoint.substringAfter(':') + " · " +
                (if (u.confirmations > 0) "${u.confirmations} conf" else "unconfirmed"), style = MaterialTheme.typography.labelSmall, color = TextFaint)
        }
    }
}

@Composable
private fun OpenSheet(ln: LightningModel, onchain: Long) {
    val scope = rememberCoroutineScope()
    var custom by remember { mutableStateOf(false) }
    var node by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("1") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Panel(accent = LnGold) {
        SectionLabel("Open a channel", LnGold)
        Explain("A channel locks some of the node's on-chain coins with one peer; what you pay goes out through it. Kilombino's node is suggested because it is well connected on this chain; any node works.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            val rc = RadioButtonDefaults.colors(selectedColor = LnGold, unselectedColor = TextFaint)
            RadioButton(selected = !custom, onClick = { custom = false }, colors = rc); Text("Kilombino", style = MaterialTheme.typography.bodySmall, color = TextMain)
            Spacer(Modifier.width(12.dp))
            RadioButton(selected = custom, onClick = { custom = true }, colors = rc); Text("Another node", style = MaterialTheme.typography.bodySmall, color = TextMain)
        }
        if (custom) Field(node, { node = it.trim() }, "pubkey@host:port")
        Field(amount, { amount = it.filter(Char::isDigit) }, "Sats (available " + formatSats(onchain) + ")", keyboard = KeyboardType.Number)
        Field(fee, { fee = it.filter(Char::isDigit) }, "Fee rate (sat/vB)", keyboard = KeyboardType.Number)
        Explain("Minimum 20,000 sats. Leave some sats on-chain: closing and fee bumps need them. The channel is private: it is not announced and nobody routes other people's payments through your phone.")
        PrimaryButton(if (busy) "OPENING…" else "OPEN CHANNEL", Modifier.fillMaxWidth(), color = LnGold,
            enabled = !busy && (amount.toLongOrNull() ?: 0) >= 20_000) {
            busy = true; msg = null
            scope.launch {
                runCatching {
                    withContext(ioDispatcher) {
                        val (pk, hosts) = if (custom) node.substringBefore('@') to listOf(node.substringAfter('@', "")).filter { it.isNotEmpty() }
                                          else Kilombino.NODE to Kilombino.HOSTS
                        require(pk.length == 66) { "The node must be pubkey@host:port" }
                        if (hosts.isNotEmpty()) {
                            var last: Throwable? = null; var ok = false
                            for (t in Lnd.resolved(hosts)) { val r = runCatching { Lnd.connect(pk, t) }; if (r.isSuccess) { ok = true; break }; last = r.exceptionOrNull() }
                            if (!ok) throw last ?: IllegalStateException("could not reach the node")
                        }
                        Lnd.openChannel(pk, amount.toLong(), (fee.toLongOrNull() ?: 1).coerceAtLeast(1))
                    }
                }.onSuccess {
                    msg = "Opening: usable once it confirms (the peer decides how many blocks, usually 1 to 3)." to true
                    amount = ""; ln.refresh()
                }.onFailure { msg = (it.message ?: "could not open the channel") to false }
                busy = false
            }
        }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
}

/** A fixed BOLT 12 offer to share: paid many times, each payer choosing the amount. */
@Composable
private fun ReusableOffer(ln: LightningModel, platform: AppPlatform) {
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Panel(accent = LnGold) {
        SectionLabel("Reusable offer (BOLT 12)", LnGold)
        Explain("One code you can share and be paid on many times; each payer chooses the amount. " +
            "It is paid only while this app has Lightning running, and only up to what your channels can receive.")
        if (offer == null) PrimaryButton(if (busy) "CREATING…" else "SHOW MY OFFER", Modifier.fillMaxWidth(), enabled = !busy, color = LnGold) {
            err = null; busy = true
            scope.launch {
                runCatching { withContext(ioDispatcher) { Lnd.reusableOffer(ln.alias()) } }
                    .onSuccess { offer = it }.onFailure { err = "Could not create it: " + (it.message ?: "the node refused") }
                busy = false
            }
        }
        err?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
        offer?.let { o -> LnQr(platform, o.uppercase(), o) }
    }
}

@Composable
private fun ReceiveLn(ln: LightningModel, platform: AppPlatform, ready: Boolean) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf<String?>(null) }
    var hash by remember { mutableStateOf<String?>(null) }
    var paid by remember { mutableStateOf<Long?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(hash) {
        val h = hash ?: return@LaunchedEffect
        while (true) {
            delay(2_000)
            val st = runCatching { withContext(ioDispatcher) { Lnd.invoiceState(h) } }.getOrNull()
            if (st == 1) { paid = amount.toLongOrNull(); invoice = null; hash = null; ln.refresh(); break }
            if (st == 2) { invoice = null; hash = null; break }
        }
    }
    LaunchedEffect(paid) { if (paid != null) { delay(4_000); paid = null; amount = ""; memo = "" } }
    Panel(accent = LnGold) {
        SectionLabel("Invoice", LnGold)
        paid?.let { Text("⚡✅ Paid! " + formatSats(it) + " sats", style = MaterialTheme.typography.titleLarge, color = Good) }
        if (!ready) Explain("You need an active channel, and can receive only up to what the other side holds in it (\"can receive\" under Channels). A new channel you opened starts with everything on your side.")
        Field(amount, { amount = it.filter(Char::isDigit) }, "Sats", keyboard = KeyboardType.Number)
        Field(memo, { memo = it }, "Note (optional, the payer sees it)")
        PrimaryButton(if (busy) "CREATING…" else "CREATE INVOICE", Modifier.fillMaxWidth(), color = LnGold,
            enabled = !busy && (amount.toLongOrNull() ?: 0) > 0) {
            err = null; invoice = null; busy = true
            scope.launch {
                runCatching { withContext(ioDispatcher) { Lnd.addInvoiceWithHash(amount.toLong(), memo) } }
                    .onSuccess { invoice = it.first; hash = it.second; paid = null }
                    .onFailure { err = "Could not create it: " + (it.message ?: "the node refused") }
                busy = false
            }
        }
        err?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
        invoice?.let { inv ->
            LnQr(platform, inv.uppercase(), inv)
            Explain("Keep the app open until it is paid. Valid for one hour.")
        }
    }
}

@Composable
private fun PayLn(ln: LightningModel, platform: AppPlatform, channels: List<Lnd.Channel>) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var req by remember { mutableStateOf<Lnd.PayReq?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var paidSats by remember { mutableStateOf<Long?>(null) }
    var viaBridge by remember { mutableStateOf<Pair<Lnd.PayReq, com.kilombino.kilowallet.core.ln.Bridge.Estimate>?>(null) }
    LaunchedEffect(paidSats) { if (paidSats != null) { delay(4_000); paidSats = null } }
    val paying by ln.paying.collectAsState()
    val payResult by ln.payResult.collectAsState()
    LaunchedEffect(payResult) {
        payResult?.let { (why, sats, ok) ->
            if (ok) { paidSats = sats; msg = null; text = "" } else msg = why to false
            req = null; ln.clearPayResult()
        }
    }
    Panel(accent = LnGold) {
        SectionLabel("Pay a Lightning invoice or offer", LnGold)
        paidSats?.let { Text("⚡📤 Paid! " + formatSats(it) + " sats", style = MaterialTheme.typography.titleLarge, color = Good) }
        paying.forEach { p ->
            Text("⏳ Paying ${formatSats(p.sats)} sats: on its way. If it fails, the sats come back to your channel.",
                color = TextSoft, style = MaterialTheme.typography.bodySmall)
        }
        Field(text, { text = it.trim(); req = null; msg = null }, "Invoice (lnbc…) or offer (lno1…)", singleLine = false, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("PASTE", Modifier.weight(1f), color = LnGold) {
                clipboard.getText()?.text?.trim()?.let { text = it; req = null; msg = null }
            }
            if (platform.canScanQr) SecondaryButton("SCAN", Modifier.weight(1f), color = LnGold) {
                platform.scanQr { it?.let { t -> text = t.trim(); req = null; msg = null } }
            }
        }
        PrimaryButton(if (busy) "READING…" else "READ INVOICE", Modifier.fillMaxWidth(), color = LnGold, enabled = text.length > 20 && !busy) {
            scope.launch {
                busy = true
                val inv = text.removePrefix("lightning:").removePrefix("LIGHTNING:")
                val offer = Regex("(?i)\\blno1[0-9a-z]+").find(inv)?.value
                runCatching { withContext(ioDispatcher) { if (offer != null) Lnd.decodeOffer(offer.lowercase()) else Lnd.decode(inv) } }
                    .onSuccess { r ->
                        if (offer != null || r.blake2b) { req = r; msg = null; return@onSuccess }
                        // A SHA-256 invoice: Kilombino's bridge can pay it, with its price first (Android 1.1.3).
                        val B = com.kilombino.kilowallet.core.ln.Bridge
                        if (r.sats <= 0L) { msg = B.text("no_amount") to false; return@onSuccess }
                        msg = "Asking Kilombino's bridge for the price…" to true
                        runCatching { withContext(ioDispatcher) { B.check(inv) } }
                            .onSuccess { e -> msg = null; viaBridge = r to e }
                            .onFailure { e -> msg = (e.message ?: "the bridge did not answer") to false }
                    }
                    .onFailure { msg = (it.message ?: "not a valid invoice") to false }
                busy = false
            }
        }
        msg?.let { (t, ok) -> Text(t, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodySmall) }
    }
    viaBridge?.let { (sha, est) ->
        BridgePay(ln, text.removePrefix("lightning:").removePrefix("LIGHTNING:"), sha, est,
            onPaid = { s -> viaBridge = null; paidSats = s; text = ""; scope.launch { runCatching { ln.refresh() } } },
            onFailed = { m -> viaBridge = null; msg = m to false },
            onCancel = { viaBridge = null })
    }
    req?.let { r ->
        var askedText by remember(r) { mutableStateOf("") }
        val payAmount = if (r.sats > 0) r.sats else askedText.toLongOrNull()?.takeIf { it > 0 } ?: 0L
        val feeLimit = ln.feeLimit(payAmount)
        val key = r.offer ?: r.hash
        val inFlight = paying.any { it.key == key }
        AlertDialog(onDismissRequest = { req = null },
            title = { Text(if (r.sats > 0) "Pay ${formatSats(r.sats)} sats?" else if (r.offer != null) "Pay this offer?" else "Pay this invoice?",
                style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (r.description.isNotBlank()) Text("“${r.description}”", style = MaterialTheme.typography.bodySmall)
                    if (r.offer != null) Text("To ${r.destination} · BOLT 12 offer", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    else Text("To ${r.destination.take(20)}…", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    Text("Routing fee at most ${formatSats(feeLimit)} sats.", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    if (inFlight) Text("On its way… You can hide this: the payment goes on while the app is open.",
                        style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    if (r.sats == 0L) {
                        Text(if (r.offer != null) "This offer has no amount: say how much to pay." else "This invoice has no amount: say how much to pay.",
                            style = MaterialTheme.typography.bodySmall, color = TextSoft)
                        Field(askedText, { askedText = it.filter(Char::isDigit) }, "Sats", keyboard = KeyboardType.Number)
                    }
                    if (channels.none { it.active && it.local >= payAmount }) Text("No active channel has that much on your side.",
                        style = MaterialTheme.typography.bodySmall, color = Warn)
                }
            },
            confirmButton = {
                TextButton(enabled = !inFlight && payAmount > 0, onClick = {
                    val invoice = text.removePrefix("lightning:").removePrefix("LIGHTNING:")
                    val amount = if (r.sats > 0) null else payAmount
                    ln.startPayment(key, payAmount) {
                        val o = r.offer
                        if (o != null) Lnd.payOffer(o, feeLimit, amountSats = amount)
                        else Lnd.pay(invoice, feeLimit, amountSats = amount)
                    }
                }) { Text(if (inFlight) "PAYING…" else "PAY", color = LnGold) }
            },
            dismissButton = { TextButton(onClick = { req = null }) { Text(if (inFlight) "HIDE" else "CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
    }
}

/**
 * Pays a SHA-256 invoice with BTC through Kilombino's bridge once its price is accepted (Android's
 * BridgePay): the hold invoice is checked on this phone's own node before a sat leaves, and the
 * preimage that comes back is checked against the invoice's hash.
 */
@Composable
private fun BridgePay(ln: LightningModel, invoice: String, sha: Lnd.PayReq, est: com.kilombino.kilowallet.core.ln.Bridge.Estimate,
                      onPaid: (Long) -> Unit, onFailed: (String) -> Unit, onCancel: () -> Unit) {
    val B = com.kilombino.kilowallet.core.ln.Bridge
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var wait by remember { mutableStateOf(est.waitSeconds) }
    LaunchedEffect(Unit) { while (wait > 0) { delay(1_000); wait-- } }
    val feeLimit = ln.feeLimit(est.maxPaySat)
    val pct = ((est.spread * 10_000).toLong() / 100.0).toString()
    AlertDialog(onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Pay this SHA-256 invoice with BTC?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("This invoice is for Lightning on the SHA-256 chain. Kilombino's bridge can pay it for you: " +
                    "you pay in BTC, and it pays the invoice.", style = MaterialTheme.typography.bodySmall)
                if (sha.description.isNotBlank()) Text("“${sha.description}”", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                Text("Invoice: ${formatSats(sha.sats)} sats SHA-256", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Text("You pay about ${formatSats(est.paySat)} sats BTC", style = MaterialTheme.typography.titleMedium, color = LnGold)
                Text("Bridge fee $pct %, at the market price. If the price moves before paying, at most " +
                    "${formatSats(est.maxPaySat)} sats; above that nothing is paid. Plus the routing fee to the bridge, at most ${formatSats(feeLimit)} sats.",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
                Text("Nobody holds your money: your payment is held and the bridge can only collect it by paying this invoice. " +
                    "If it can't, your BTC comes back by itself.", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                if (wait > 0) Text("Wait $wait s before asking the bridge for a new price for this invoice.", color = Bad, style = MaterialTheme.typography.bodySmall)
                if (step.isNotBlank()) Text(step, style = MaterialTheme.typography.bodySmall, color = LnGold)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && wait == 0, onClick = {
                busy = true
                scope.launch {
                    val result = runCatching {
                        withContext(ioDispatcher) {
                            step = "Getting the final price (after a small proof of work, a few seconds)…"
                            val q = B.quote(invoice, sha.hash)
                            val hold = Lnd.decode(q.holdInvoice)
                            B.mismatch(hold, hold.msat, sha, q, est.maxPaySat, Lnd.host.nowSeconds())
                                ?.let { throw IllegalStateException("Not paid: $it.") }
                            step = "Paying ${formatSats(B.ceilSat(hold.msat))} sats BTC… the bridge pays the invoice and then collects."
                            val preimage = try {
                                Lnd.pay(q.holdInvoice, ln.feeLimit(B.ceilSat(hold.msat)), timeoutSeconds = 60)
                            } catch (e: Exception) {
                                throw IllegalStateException(B.afterFailure(B.state(q.hash), Lnd.payError(e)))
                            }
                            if (!B.proves(preimage, sha.hash)) throw IllegalStateException(
                                "Paid, but the proof does not match the invoice: tell Kilombino, with the payment hash ${sha.hash}.")
                            B.ceilSat(hold.msat)
                        }
                    }
                    busy = false
                    result.onSuccess { onPaid(it) }.onFailure { onFailed(it.message ?: "Not paid.") }
                }
            }) { Text(if (busy) "PAYING…" else "PAY ${formatSats(est.paySat)} SATS", color = LnGold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
}

/** [delta] is what the channels lost or gained, routing fee included; [fee] is that fee, shown apart. */
private data class LnEntry(val time: Long, val delta: Long, val label: String, val left: Long, val fee: Long = 0)

/** Received invoices and sent payments, newest first, with what was left in the channels after each (Android's ledger). */
private fun ledger(s: LightningModel.Status.Running): List<LnEntry> {
    data class E(val time: Long, val delta: Long, val label: String, val fee: Long = 0)
    val events = s.invoices.filter { it.state == 1 && it.paid > 0 }
        .map { E(it.settled, it.paid, it.memo.ifBlank { "invoice" }, if (it.sats in 1 until it.paid) it.paid - it.sats else 0) } +
        s.payments.filter { it.status == 2 }.map { E(it.timeSec, -(it.sats + it.fee), "payment", it.fee) }
    var running = s.balances?.channels ?: 0L
    return events.sortedByDescending { it.time }.map { e -> LnEntry(e.time, e.delta, e.label, running, e.fee).also { running -= e.delta } }
}

@Composable
private fun Movements(moves: List<LnEntry>, title: String, hide: (String) -> String) {
    if (moves.isEmpty()) return
    Panel(accent = LnGold) {
        SectionLabel(title, LnGold)
        moves.take(20).forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.label, style = MaterialTheme.typography.bodySmall, color = TextMain, maxLines = 1)
                    Text(dayTime(m.time) + " · left " + hide(formatSats(m.left)), style = MaterialTheme.typography.labelSmall, color = TextFaint)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text((if (m.delta > 0) "+" else "−") + hide(formatSats(kotlin.math.abs(m.delta) - m.fee)),
                        style = MaterialTheme.typography.bodyMedium, color = if (m.delta > 0) Good else TextSoft)
                    if (m.fee > 0) Text("fee " + hide(formatSats(m.fee)), style = MaterialTheme.typography.labelSmall, color = TextFaint)
                }
            }
        }
    }
}

/** "31 Oct 23:15", in UTC (no time zone database in the shared code). */
private fun dayTime(sec: Long): String {
    val days = sec / 86_400; val m = (sec % 86_400) / 60
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719_468; val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097; val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1; val month = if (mp < 10) mp + 3 else mp - 9
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "$d ${names[(month - 1).toInt()]} ${m / 60}:${(m % 60).toString().padStart(2, '0')}"
}

@Composable
private fun InboundPanel(ln: LightningModel, s: LightningModel.Status.Running) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Inbound.Info?>(null) }
    var millions by remember { mutableStateOf(1) }
    var quote by remember { mutableStateOf<Inbound.Quote?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var orderId by remember { mutableStateOf<String?>(null) }
    val canPay = s.channels.any { it.active && it.local > 0 }
    LaunchedEffect(orderId) {
        val id = orderId ?: return@LaunchedEffect
        while (true) {
            delay(4_000)
            val o = runCatching { withContext(ioDispatcher) { Inbound.order(id) } }.getOrNull() ?: continue
            when (o.status) {
                "opening", "open" -> { status = "✅ Channel on its way: usable once it confirms."; ln.refresh(); orderId = null; break }
                "failed" -> { status = null; err = "Not opened: " + (o.reason ?: "unknown") + ". The fee comes back by itself."; orderId = null; break }
                "expired" -> { status = null; err = "The request expired without being paid."; orderId = null; break }
            }
        }
    }
    Panel(accent = LnGold) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("Need to receive more? Inbound channel from Kilombino's node", style = MaterialTheme.typography.bodySmall,
                color = TextSoft, modifier = Modifier.weight(1f))
            Text(if (open) "▲" else "▼", color = TextFaint)
        }
        if (!open) return@Panel
        LaunchedEffect(Unit) {
            info = runCatching { withContext(ioDispatcher) { Inbound.info() } }.onFailure { err = "The service does not answer: " + it.message }.getOrNull()
        }
        Explain("You can only receive up to what the other side holds in your channels. Kilombino's node can open a private channel towards yours, with the sats on its side, so you can receive that much. It is not a deposit: those sats are not yours until someone pays you.")
        val i = info
        if (i == null) { if (err == null) Text("Asking the service…", style = MaterialTheme.typography.bodySmall, color = TextFaint) }
        else {
            val maxM = (minOf(i.maxSats, i.availableSats) / 1_000_000).toInt()
            Text("Fee: " + formatSats(i.feePerMillion) + " sats per million · kept open at least ${i.minDays} days, longer while it is used · private",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
            if (maxM < 1) Text("No liquidity free right now: try again later.", style = MaterialTheme.typography.bodySmall, color = Warn)
            else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = millions > 1, onClick = { millions-- }) { Text("−", color = LnGold) }
                    Text("$millions million sats", style = MaterialTheme.typography.titleMedium, color = TextMain)
                    TextButton(enabled = millions < maxM, onClick = { millions++ }) { Text("+", color = LnGold) }
                }
                Text("Fee " + formatSats(i.feePerMillion * millions) + " sats · up to $maxM million now", style = MaterialTheme.typography.labelSmall, color = TextFaint)
                if (!canPay) Text("The fee is paid over Lightning: you need an active channel with some sats on your side first.",
                    style = MaterialTheme.typography.bodySmall, color = Warn)
                PrimaryButton(if (busy) "ASKING…" else "ASK FOR $millions MILLION", Modifier.fillMaxWidth(), color = LnGold,
                    enabled = !busy && orderId == null && canPay) {
                    busy = true; err = null; status = null
                    scope.launch {
                        runCatching {
                            withContext(ioDispatcher) {
                                if (!ln.connectKilombino()) throw IllegalStateException("Cannot reach Kilombino's node: " + (ln.linkError ?: "no answer"))
                                Inbound.quote(s.info.pubkey, millions * 1_000_000L)
                            }
                        }.onSuccess { quote = it }.onFailure { err = it.message }
                        busy = false
                    }
                }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Good) }
        err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Bad) }
    }
    quote?.let { q ->
        AlertDialog(onDismissRequest = { quote = null },
            title = { Text("Pay ${formatSats(q.fee)} sats for an inbound channel of ${formatSats(q.sats)}?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text("Kilombino's node opens a private channel of ${formatSats(q.sats)} sats towards yours, kept open at least ${info?.minDays ?: 90} days. " +
                    "The fee is held, not taken, until the channel's transaction is out; if it cannot be opened, it comes back by itself. " +
                    "This offer lasts 10 minutes.", style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = {
                TextButton(onClick = {
                    quote = null; orderId = q.id; status = "Paying the fee and waiting for the channel…"
                    scope.launch {
                        runCatching {
                            withContext(ioDispatcher) {
                                Inbound.mismatch(q, millions * 1_000_000L, info?.feePerMillion ?: -1)?.let { throw IllegalStateException(it) }
                                Lnd.pay(q.invoice, ln.feeLimit(q.fee), 120)
                            }
                        }.onFailure { e -> if (orderId != null) { err = "The fee was not paid: " + (e.message ?: "failed"); status = null } }
                    }
                }) { Text("PAY AND OPEN", color = LnGold) }
            },
            dismissButton = { TextButton(onClick = { quote = null }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
    }
}
