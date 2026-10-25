package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.PoolDest
import com.kilombino.kilowallet.app.PoolPayRequest
import com.kilombino.kilowallet.app.PoolsModel
import com.kilombino.kilowallet.app.PoolsUi
import com.kilombino.kilowallet.app.Screen
import com.kilombino.kilowallet.app.UiState
import com.kilombino.kilowallet.app.WalletKind
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.app.i18n.Text
import com.kilombino.kilowallet.core.chain.Chain
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.kilojoin.NostrEvent
import com.kilombino.kilowallet.core.kilojoin.PoolPayment
import com.kilombino.kilowallet.core.kilojoin.PoolSession
import com.kilombino.kilowallet.core.kilojoin.PoolTx
import com.kilombino.kilowallet.core.kilojoin.Protocol
import com.kilombino.kilowallet.core.wallet.SpendableUtxo
import com.kilombino.kilowallet.core.wallet.formatSats
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/*
 * KILOJOIN: shared transactions between people, the Android app's COINJOIN tab (1.3.0) for this
 * wallet: pools of one address type (native SegWit) where each person puts in coins and gets back
 * outputs of exactly the pool's amount, all equal, or pays recipients through one (v5). No server
 * holds any coin; each member checks the transaction and signs only its own inputs.
 */

private fun now() = NostrEvent.nowSeconds()

private fun remaining(at: Long): String {
    val s = at - now()
    return when {
        s <= 0 -> I18n.tr("now")
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

private fun rate(r: Double): String {
    val t = (r * 10).roundToLong()
    return if (t % 10 == 0L) "${t / 10}" else "${t / 10}.${t % 10}"
}

/** "2 × 50 000" for several equal outputs each, the plain amount otherwise. */
private fun poolAmount(t: Protocol.Terms): String = if (t.outputs > 1) "${t.outputs} × ${formatSats(t.amount)}" else formatSats(t.amount)

/** What kind of pool it is, in one line (each piece translated on its own). */
private fun poolKind(t: Protocol.Terms): String = listOf(
    if (t.maxInputs > 1) "up to ${t.maxInputs} coins each" else "one coin each",
    when {
        t.v5 && t.outputs > 1 -> "a shared payment: the creator pays ${t.payments.size} recipient(s); you get ${formatSats(t.mixTotal)} back as ${t.outputs} equal outputs"
        t.v5 -> "a shared payment: the creator pays ${t.payments.size} recipient(s); you get ${formatSats(t.mixTotal)} back in one output"
        t.outputs > 1 -> "${t.outputs} equal outputs each"
        else -> "one equal output each"
    },
).joinToString(" · ")

/** A reason that may come from another wallet, in this app's words. */
private fun reasonText(r: String): String = I18n.tr(r.replace(OTHER_WORDS, "equal"))

/** Another wallet's word for the equal outputs, in a reason it sent. */
private val OTHER_WORDS = Regex("(?i)\\bmix(ed|ing)?\\b")

private val active = setOf(PoolSession.Phase.JOINING, PoolSession.Phase.OPEN, PoolSession.Phase.VOTING, PoolSession.Phase.CLOSING,
    PoolSession.Phase.SIGNING, PoolSession.Phase.BROADCAST)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoolsScreen(model: WalletModel, state: UiState, platform: AppPlatform) {
    val pools = model.pools
    val ui by pools.state.collectAsState()
    // A heartbeat while the screen is open: countdowns move, and the open pools are looked up again every minute.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { pools.resume(); pools.refreshPools(); while (true) { delay(2_000); tick++; if (tick % 30 == 0) pools.refreshPools() } }
    if (tick < 0) return

    ScreenColumn {
        BackLink { model.back() }
        Text("Kilojoin", style = MaterialTheme.typography.titleLarge, color = TextMain)
        if (state.kind != WalletKind.HOT) {
            Panel(accent = Orange) {
                Explain("Kilojoin needs a wallet that can sign: a watch-only wallet has no keys to take part in a pool. " +
                    "A watch-only key can still receive a pool's outputs: set it in a spending wallet's Kilojoin screen.", color = TextSoft)
            }
            WhatItIs()
            return@ScreenColumn
        }
        if (!ui.enabled) { Intro(pools) { model.back() }; return@ScreenColumn }
        PoolsMessages(pools, ui)
        ForegroundNote()

        val mine = ui.rounds
        val going = mine.filter { it.phase in active }
        val finished = mine.filter { it.phase !in active }
        if (going.isNotEmpty()) {
            SectionLabel("Your rounds")
            going.forEach { RoundCard(model, pools, it, ui.rev, platform) }
        }
        PrimaryButton("＋  Open a pool", Modifier.fillMaxWidth()) { pools.startPayment(null); model.go(Screen.PoolCreate) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Open pools")
            Column(Modifier.weight(1f)) {}
            LinkText(if (ui.loading) "looking…" else "↻ refresh", color = TextSoft) { pools.refreshPools() }
        }
        ui.relayError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
        // Only rounds still going hide their pool: one that ended or was left shows again to join.
        val ours = going.map { it.poolId }.toSet()
        val shown = ui.open.orEmpty().filter { it.id !in ours }
        if (ui.open != null && shown.isEmpty()) Explain("No open pools right now. Open one, and others can join it.")
        if (ui.open == null && ui.loading) Explain("asking the relay…")
        shown.forEach { t -> OpenPoolCard(t) { model.go(Screen.PoolJoin(t.id)) } }

        if (finished.isNotEmpty()) {
            SectionLabel("Finished")
            finished.forEach { RoundCard(model, pools, it, ui.rev, platform) }
            LinkText("remove all finished", color = TextFaint) { pools.removeFinished() }
        }
        WatchKeyPanel(pools, ui)
        HowItGoes()
        WhatItIs()
    }
}

@Composable
private fun PoolsMessages(pools: PoolsModel, ui: PoolsUi) {
    ui.busy?.let { b ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = Purple)
            Text(b, style = MaterialTheme.typography.bodyMedium, color = TextSoft)
        }
    }
    ui.message?.let { m ->
        AlertDialog(
            onDismissRequest = pools::dismissMessage,
            text = { Text(reasonText(m), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = pools::dismissMessage) { Text("OK", color = Purple) } },
            containerColor = PanelBg, textContentColor = TextMain,
        )
    }
}

/** iOS keeps no app running in the background for long: said plainly, where a round is followed. */
@Composable
private fun ForegroundNote() {
    Panel(accent = Warn) {
        SectionLabel("Keep Kilowallet open during a round", Warn)
        Explain("iOS does not let an app run in the background for long. Your rounds run while Kilowallet is on screen, " +
            "and for the short time iOS allows after you leave it; then they pause until you open the app again. " +
            "A round that needs your answer, your outputs or your signature while the app is closed may be cancelled. " +
            "Nothing is lost when that happens: no coin moves until everyone has signed.", color = TextSoft)
    }
}

@Composable
private fun Intro(pools: PoolsModel, onDecline: () -> Unit) {
    Panel {
        SectionLabel("Kilojoin: shared transactions")
        Explain("A pool is a transaction made together by several people. Each one puts in coins and gets back outputs " +
            "of exactly the pool's amount, all equal, to fresh addresses of their own, so the chain shows the outputs but not " +
            "which one belongs to whom. You can also pay someone through a pool.", color = TextMain)
        Explain("Nobody holds your coins at any moment: not us, not the relay that carries the messages, not the other members. " +
            "Your wallet checks the whole transaction and you sign only your own inputs, only when your outputs and your " +
            "change are exactly right. There is no fee for this; each person pays their own share of the miners' fee.", color = TextSoft)
        Explain("The messages go through a nostr relay (relay.kilombino.com), encrypted. It sees when you are connected, " +
            "not what is yours.", color = TextSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Not now", Modifier.weight(1f), color = TextSoft) { onDecline() }
            PrimaryButton("Turn on", Modifier.weight(1f)) { pools.setEnabled(true) }
        }
    }
    ForegroundNote()
    HowItGoes()
}

@Composable
private fun HowItGoes() {
    Panel {
        SectionLabel("How a round goes")
        Explain("1. Someone opens a pool with an amount. 2. People join, each proving they own real coins, one seat per coin. " +
            "3. With enough people in, anyone can ask to close it; the others accept or refuse, and whoever does not answer in " +
            "${Protocol.VOTE_SECONDS / 60} minutes is left out. 4. Each wallet hands in its equal outputs with no name on them " +
            "(signed blindly by the pool's creator, so nobody can tell whose they are). 5. Every wallet builds the same " +
            "transaction and checks its own outputs, its change and the fee. 6. You sign; once all have signed, it is sent. " +
            "It is signed so that it is valid on the ${Chain.DISPLAY} chain only, never on Corecoin.")
    }
}

@Composable
private fun WhatItIs() {
    Panel {
        SectionLabel("About Kilojoin")
        Explain("Kilojoin is an open protocol over nostr, the same one Kilowallet for Android and for desktop use, so you can " +
            "be in a pool with people on any of them. Native SegWit (bc1q) pools only in this app; Taproot pools are not " +
            "shown. Pools are open from ${formatSats(Protocol.MIN_AMOUNT)} sats per person, " +
            "${formatSats(Protocol.TEST_MIN_AMOUNT)} sats per output at least.")
    }
}

@Composable
private fun OpenPoolCard(t: Protocol.Terms, onJoin: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text((if (t.private) "🔒 " else "") + "${poolAmount(t)} sats", style = MaterialTheme.typography.titleMedium, color = TextMain)
                Text(poolKind(t), style = MaterialTheme.typography.bodySmall, color = Purple)
                Text("${t.peers}/${t.maxPeers} people (min ${t.minPeers}) · ${rate(t.feeRate)} sat/vB · closes in ${remaining(t.expiresAt)}",
                    style = MaterialTheme.typography.bodySmall, color = TextSoft)
                val fs = PoolTx.feeShare(t.feeRate, false, 1, t.script, t.outputs)
                Text("your fee share: ${formatSats(PoolTx.feeShare(t.feeRate, true, 1, t.script, t.outputs))} sats with one coin " +
                    "(${formatSats(fs)} with a coin of exactly ${formatSats(t.mixTotal + fs)})",
                    style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }
            TextButton(onClick = onJoin) { Text("JOIN", color = Purple, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun WatchKeyPanel(pools: PoolsModel, ui: PoolsUi) {
    var editing by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Panel {
        SectionLabel("Where your equal outputs go")
        Explain(if (ui.watchKey == null) "To fresh addresses of this wallet. You can also send them to a watch-only wallet: " +
            "give its key (a zpub of native SegWit addresses), and each round lets you choose."
            else "This wallet by default, or the watch-only key below, chosen in each round.")
        ui.watchKey?.let { Text(chunked(it.take(24)) + "…", style = MaterialTheme.typography.bodySmall, color = TextMain) }
        if (editing) {
            Field(key, { key = it.trim(); error = null }, "zpub of the watch-only wallet", singleLine = false, minLines = 2)
            error?.let { Text(reasonText(it), style = MaterialTheme.typography.bodySmall, color = Bad) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), color = TextSoft) { editing = false; key = "" }
                PrimaryButton("Save", Modifier.weight(1f), enabled = key.isNotBlank()) {
                    error = pools.setWatchKey(key)
                    if (error == null) { editing = false; key = "" }
                }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LinkText(if (ui.watchKey == null) "add a watch-only key" else "change it", color = Purple) { editing = true }
            if (ui.watchKey != null) LinkText("remove it", color = TextFaint) { pools.setWatchKey(null) }
        }
    }
}

/** One of this wallet's rounds: where it stands, what is ours in it, and what it needs from us now. */
@Composable
private fun RoundCard(model: WalletModel, pools: PoolsModel, st: PoolSession.State, rev: Long, platform: AppPlatform) {
    if (rev < 0) return // read, so the card is drawn again when the round changes in place
    val uri = LocalUriHandler.current
    val color = when (st.phase) {
        PoolSession.Phase.CONFIRMED -> Good
        PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED -> Bad
        PoolSession.Phase.SIGNING, PoolSession.Phase.VOTING -> Warn
        else -> Purple
    }
    val t = st.terms
    val people = if (st.round.isNotEmpty()) st.round.size else st.seats.size
    val dest = pools.destOf(st.poolId)
    Panel(accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${poolAmount(t)} sats", style = MaterialTheme.typography.titleMedium, color = TextMain, modifier = Modifier.weight(1f))
            Text((if (t.private) "🔒 " else "") + (if (st.creator) "your pool" else "joined"), style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
        Text(when (st.phase) {
            PoolSession.Phase.JOINING -> "Asking to join…"
            PoolSession.Phase.OPEN -> "Waiting for people · $people/${t.maxPeers} · closes in ${remaining(t.expiresAt)}"
            PoolSession.Phase.VOTING -> "Close now with $people people?"
            PoolSession.Phase.CLOSING -> "Closing with $people people · collecting the equal outputs"
            PoolSession.Phase.SIGNING -> "Ready: check and sign within ${remaining(st.phaseDeadline)}"
            PoolSession.Phase.BROADCAST -> "Sent · waiting for the first confirmation"
            PoolSession.Phase.CONFIRMED -> "Confirmed ✓"
            PoolSession.Phase.ABORTED -> "Cancelled: ${reasonText(st.reason)}. Your coins did not move."
            PoolSession.Phase.REJECTED -> "Refused: ${reasonText(st.reason)}"
        }, style = MaterialTheme.typography.bodySmall, color = color)
        val fee = formatSats(st.seat.inputValue - t.mixTotal - st.seat.changeValue)
        val coins = if (st.seat.coins.size > 1) "your ${st.seat.coins.size} coins" else "your coin"
        val out = if (t.v5 && st.creator) "${formatSats(t.mixTotal)} paid to ${t.payments.size} recipient(s)" else "${poolAmount(t)} in equal outputs"
        val inV = formatSats(st.seat.inputValue)
        val ch = formatSats(st.seat.changeValue)
        Text(if (st.seat.changeValue > 0) "$coins: $inV → $out + $ch change · fee $fee" else "$coins: $inV → $out · fee $fee",
            style = MaterialTheme.typography.bodySmall, color = TextFaint)
        if (dest == PoolDest.WATCH) Text("your equal outputs go to the watch-only wallet", style = MaterialTheme.typography.bodySmall, color = Purple)
        if (dest == PoolDest.PAY) Text("a payment: it goes out only when the pool closes; if it does not fill, nothing is paid",
            style = MaterialTheme.typography.bodySmall, color = Purple)

        var confirmLeave by remember { mutableStateOf(false) }
        if (confirmLeave) AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (st.creator) "Cancel this pool?" else "Leave this pool?") },
            text = { Text(if (st.creator) "The round is cancelled for everyone in it, and they are told so. Nobody loses anything: " +
                "no transaction exists until all have signed, so every coin stays where it is."
                else "Your seat is freed and your coins are yours to spend again. Nothing was signed, so nothing moves.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; pools.leave(st.poolId) }) {
                Text(if (st.creator) "CANCEL POOL" else "LEAVE", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("STAY", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
        when (st.phase) {
            PoolSession.Phase.JOINING -> {
                Explain("Waiting for the pool's creator to let you in. If nobody answers in ${PoolSession.JOIN_TIMEOUT / 60} minutes the request is dropped.")
                SecondaryButton("Cancel", Modifier.fillMaxWidth(), color = TextSoft) { pools.leave(st.poolId) }
            }
            PoolSession.Phase.OPEN -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (people >= t.minPeers) PrimaryButton("Ask to close now", Modifier.weight(1f)) { pools.requestClose(st.poolId) }
                    SecondaryButton(if (st.creator) "Cancel pool" else "Leave", Modifier.weight(1f), color = TextSoft) { confirmLeave = true }
                }
                if (people < t.minPeers) Explain("It can close once ${t.minPeers} people are in.")
            }
            PoolSession.Phase.VOTING -> {
                val iAsked = st.voteBy == st.token?.let { Protocol.tokenHash(it) }
                if (iAsked || st.votedOn == st.voteId) {
                    Explain("Waiting for the others to answer, until ${clock(st.voteDeadline)}. Whoever has not answered by then is left out; " +
                        "if too few are left, the pool opens again without them.")
                } else {
                    Explain("Someone asked to close the pool now. Answer before ${clock(st.voteDeadline)}: if you accept, the round starts " +
                        "and you will be asked to check and sign it.")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Accept", Modifier.weight(1f)) { pools.vote(st.poolId, true) }
                        SecondaryButton("Not yet", Modifier.weight(1f), color = TextSoft) { pools.vote(st.poolId, false) }
                    }
                }
            }
            PoolSession.Phase.CLOSING -> Explain("Each wallet hands in its equal outputs now, each from a key of its own after a random wait. " +
                "Then the transaction is ready to check.")
            PoolSession.Phase.SIGNING -> SignReview(pools, st, platform)
            PoolSession.Phase.BROADCAST, PoolSession.Phase.CONFIRMED -> st.txid?.let { txid ->
                Text(shortTxid(txid), style = MaterialTheme.typography.bodySmall, color = TextSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LinkText("open in the explorer", color = Purple) { runCatching { uri.openUri("${Chain.EXPLORER}/tx/$txid") } }
                    if (st.phase == PoolSession.Phase.CONFIRMED) LinkText("remove", color = TextFaint) { pools.remove(st.poolId) }
                }
            }
            PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED -> LinkText("remove", color = TextFaint) { pools.remove(st.poolId) }
        }
    }
}

private fun clock(at: Long): String {
    val m = ((at % 86400) / 60).toInt()
    return "${m / 60}:${(m % 60).toString().padStart(2, '0')} UTC"
}

/** What the transaction gives us, checked before the user signs; the button only when the check passes. */
@Composable
private fun SignReview(pools: PoolsModel, st: PoolSession.State, platform: AppPlatform) {
    val session = pools.session(st.poolId)
    val plan = session?.plan()
    if (st.postedSig) { Explain("Signed. Waiting for the others (${st.sigs.size}/${plan?.coins?.size ?: "?"} coins)…"); return }
    if (plan == null) { Explain("The transaction is not complete yet."); return }
    val problem = session.planProblem()
    val t = st.terms
    Panel(accent = if (problem == null) Good else Bad) {
        SectionLabel("Check before signing", if (problem == null) Good else Bad)
        KeyValue("people", "${st.round.size}")
        KeyValue("coins in it", "${plan.coins.size}")
        val equalCount = plan.outputs.count { it.value == t.amount }
        KeyValue("equal outputs", "$equalCount × ${formatSats(t.amount)} sats")
        val mine = st.mixScripts.mapIndexed { i, sp -> Address.fromScriptPubKey(sp) to (if (t.v5 && st.creator) t.payments[i] else t.amount) }
        mine.forEach { (a, v) -> KeyValue(if (t.v5 && st.creator) "payment" else "yours", "${formatSats(v)} sats → ${shortAddress(a ?: "?")}") }
        KeyValue("your change", if (st.seat.changeValue > 0) "${formatSats(st.seat.changeValue)} sats" else "none (no change)")
        KeyValue("your fee share", "${formatSats(st.seat.inputValue - t.mixTotal - st.seat.changeValue)} sats")
        KeyValue("whole fee", "${formatSats(plan.fee)} sats")
        KeyValue("transaction", shortTxid(PoolTx.txid(plan)))
        if (problem == null) Text("✓ Checked: your outputs, your change and your fee share are exactly right, and every input signs " +
            "this transaction only.", style = MaterialTheme.typography.bodySmall, color = Good)
        else Text("✗ Not signed: ${reasonText(problem)}", style = MaterialTheme.typography.bodySmall, color = Bad)
    }
    PrimaryButton("Sign with ${platform.unlockName}", Modifier.fillMaxWidth(), enabled = problem == null, color = Warn) { pools.sign(st.poolId) }
}

// ---------------------------------------------------------------------- coins

@Composable
private fun CoinList(pools: PoolsModel, coins: List<SpendableUtxo>?, chosen: MutableList<SpendableUtxo>, max: Int, exact: Long? = null,
                     minOne: Long = 0) {
    when {
        coins == null -> Explain("reading your coins…")
        coins.isEmpty() -> Text("No confirmed coin to put in.", style = MaterialTheme.typography.bodySmall, color = Warn)
        else -> coins.sortedWith(compareBy({ if (it.value == exact) 0 else 1 }, { -it.value })).forEach { u ->
            val sel = chosen.any { it.txid == u.txid && it.vout == u.vout }
            val usable = max > 1 || u.value >= minOne
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (sel) Purple.copy(alpha = 0.15f) else PanelBg)
                .clickable(enabled = usable) {
                    if (sel) chosen.removeAll { it.txid == u.txid && it.vout == u.vout }
                    else if (max == 1) { chosen.clear(); chosen.add(u) }
                    else if (chosen.size < max) chosen.add(u)
                }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(if (u.value == exact) "${formatSats(u.value)} sats ★ exact" else "${formatSats(u.value)} sats",
                            color = if (!usable) TextFaint else if (u.value == exact) Good else TextMain, style = MaterialTheme.typography.bodyMedium)
                        if (pools.isPooled(u)) Text("  from a pool", color = Warn, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("m/84'/0'/0'/${u.chainIndex}/${u.index}", color = TextFaint, style = MaterialTheme.typography.bodySmall)
                }
                if (sel) Text("✓", color = Purple)
            }
        }
    }
    if (chosen.count { pools.isPooled(it) } > 0 && chosen.size > 1)
        Text("A coin from a pool put in together with other coins ties them back together: the round shows they are one person's. " +
            "Put coins from pools in one at a time.", style = MaterialTheme.typography.bodySmall, color = Warn)
}

@Composable
private fun DestChooser(ui: PoolsUi, dest: PoolDest, onDest: (PoolDest) -> Unit) {
    if (ui.watchKey == null) return
    SectionLabel("Your equal outputs go to")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("This wallet", dest == PoolDest.HOT) { onDest(PoolDest.HOT) }
        Chip("Watch-only wallet", dest == PoolDest.WATCH) { onDest(PoolDest.WATCH) }
    }
    if (dest == PoolDest.WATCH) Explain("Unused receive addresses of the watch-only key, asked to the server; your change stays in this wallet.")
}

// ---------------------------------------------------------------------- join

@Composable
fun PoolJoinScreen(model: WalletModel, state: UiState, poolId: String) {
    val pools = model.pools
    val ui by pools.state.collectAsState()
    val t = ui.open?.firstOrNull { it.id == poolId }
    var coins by remember { mutableStateOf<List<SpendableUtxo>?>(null) }
    val chosen = remember { mutableStateListOf<SpendableUtxo>() }
    var password by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf(PoolDest.HOT) }
    LaunchedEffect(poolId) {
        coins = runCatching { if (model.isDemo) demoCoins() else pools.poolCoins() }.getOrDefault(emptyList())
        // Screenshot mode: the exact coin ticked, to show what joining with it looks like.
        if (model.isDemo && t != null) coins?.firstOrNull { it.value == PoolTx.minimumCoin(t.amount, t.feeRate, t.script, t.outputs) }?.let { chosen.add(it) }
    }
    ScreenColumn {
        BackLink { model.go(Screen.Pools) }
        PoolsMessages(pools, ui)
        if (t == null) { Explain("That pool is no longer open."); return@ScreenColumn }
        Text("Join · ${poolAmount(t)} sats", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Panel {
            Text(poolKind(t), style = MaterialTheme.typography.bodySmall, color = Purple)
            Text("${t.peers}/${t.maxPeers} people (min ${t.minPeers}) · ${rate(t.feeRate)} sat/vB · closes in ${remaining(t.expiresAt)}",
                style = MaterialTheme.typography.bodySmall, color = TextSoft)
        }
        if (t.private) Panel {
            SectionLabel("🔒 Private pool")
            Explain("Its creator shares the password with the people invited. Without it the pool refuses you.")
            Field(password, { password = it }, "pool password")
        }
        val min = PoolTx.minimumCoin(t.amount, t.feeRate, t.script, t.outputs)
        Panel {
            SectionLabel("Your coins")
            Explain(if (t.maxInputs > 1) "Tick up to ${t.maxInputs} confirmed coins that together make at least ${formatSats(t.mixTotal)} sats plus your fee. " +
                "The rest comes back to a fresh change address."
                else "Tick ONE confirmed coin of at least ${formatSats(min)} sats. The rest comes back to a fresh change address.")
            CoinList(pools, coins, chosen, t.maxInputs, exact = min, minOne = min)
            val total = chosen.sumOf { it.value }
            val change = if (chosen.isEmpty()) null else PoolTx.change(total, t.amount, t.feeRate, chosen.size, t.script, t.outputs)
            if (chosen.isNotEmpty()) Text(when {
                change == null -> "${formatSats(total)} sats: ${formatSats(t.mixTotal + PoolTx.feeShare(t.feeRate, false, chosen.size, t.script, t.outputs) - total)} sats short"
                change == 0L -> "→ ${poolAmount(t)} in equal outputs · no change · fee ${formatSats(total - t.mixTotal)} sats"
                else -> "→ ${poolAmount(t)} in equal outputs + ${formatSats(change)} change · fee ${formatSats(total - t.mixTotal - change)} sats"
            }, style = MaterialTheme.typography.bodySmall, color = if (change == null) Warn else Good)
            Explain("A coin of exactly ${formatSats(min)} sats goes in with no change at all: nothing on the chain ties your outputs to it.")
        }
        Panel { DestChooser(ui, dest) { dest = it } ; Explain("Your outputs: ${t.outputs} fresh address(es); nothing about them leaves this phone but the outputs themselves.") }
        val total = chosen.sumOf { it.value }
        val ok = chosen.isNotEmpty() && PoolTx.change(total, t.amount, t.feeRate, chosen.size, t.script, t.outputs) != null &&
            (!t.private || password.isNotEmpty()) && ui.busy == null
        PrimaryButton("Join with ${model.unlockName}", Modifier.fillMaxWidth(), enabled = ok) {
            pools.join(t, chosen.toList(), dest, password) { joined -> if (joined) model.go(Screen.Pools) }
        }
        Explain("Joining signs nothing that can move your coins: it only proves you own them. You sign the round at the end, after checking it.")
    }
}

// ---------------------------------------------------------------------- pay through a pool (from SEND)

@Composable
fun PoolPayScreen(model: WalletModel, state: UiState) {
    val pools = model.pools
    val ui by pools.state.collectAsState()
    val start = ui.pay
    val addrs = remember { mutableStateListOf<String>().also { l -> start?.recipients?.forEach { l += it.first }; if (l.isEmpty()) l += "" } }
    val amounts = remember { mutableStateListOf<String>().also { l -> start?.recipients?.forEach { l += it.second.toString() }; if (l.isEmpty()) l += "" } }
    var rateText by remember { mutableStateOf(start?.feeRate?.let { rate(maxOf(1.0, it)) } ?: "1") }
    val rs = addrs.indices.map { addrs[it].trim() to (amounts[it].filter { c -> c.isDigit() }.toLongOrNull() ?: 0L) }
    val check = PoolPayment.check(rs)
    val feeRate = rateText.replace(',', '.').toDoubleOrNull()
    ScreenColumn {
        BackLink { model.go(Screen.Send) }
        Text("Pay through a pool", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("A new pool opens for this payment. When everyone gets the same amount, the payments are equal outputs " +
            "like everyone else's. With different amounts it is a shared payment: the others put in the same total and get it " +
            "back as equal outputs, and the chain shows the payments but not which coins paid them. The payment only goes " +
            "out when the pool closes, which can take hours; if it does not fill, nothing is paid.")
        addrs.indices.forEach { i ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Recipient ${i + 1}")
                    Column(Modifier.weight(1f)) {}
                    if (addrs.size > 1) LinkText("remove", color = TextFaint) { addrs.removeAt(i); amounts.removeAt(i) }
                }
                Field(addrs[i], { addrs[i] = it.trim() }, "bc1q… address", singleLine = false, minLines = 2)
                if (addrs[i].isNotBlank() && !Address.isValid(addrs[i])) Text("Not a valid address.", style = MaterialTheme.typography.bodySmall, color = Bad)
                AmountFields(amounts[i], { amounts[i] = it }, state)
            }
        }
        if (addrs.size < Protocol.MAX_OUTPUTS) LinkText("＋ add a recipient", color = Purple) { addrs += ""; amounts += "" }
        Panel {
            SectionLabel("Fee rate")
            Field(rateText, { rateText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, "sat/vB (1 to 500)", keyboard = KeyboardType.Decimal)
        }
        val ready = rs.all { it.first.isNotEmpty() && it.second > 0 }
        val sum = rs.sumOf { it.second }
        if (ready) Text(when {
            check.ok -> "${rs.size} × ${formatSats(check.amount)} sats: equal outputs of a pool of ${formatSats(check.amount)}"
            check.paymentOk -> "${formatSats(sum)} sats to ${rs.size} recipient(s): a shared payment"
            else -> reasonText(check.reason ?: "")
        }, style = MaterialTheme.typography.bodySmall, color = if (check.any) Good else Warn)
        PrimaryButton("Continue", Modifier.fillMaxWidth(), enabled = ready && check.any && feeRate != null && feeRate in 1.0..500.0) {
            pools.startPayment(PoolPayRequest(rs, feeRate!!)); model.go(Screen.PoolCreate)
        }
    }
}

// ---------------------------------------------------------------------- open a pool

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoolCreateScreen(model: WalletModel, state: UiState) {
    val pools = model.pools
    val ui by pools.state.collectAsState()
    val pay = ui.pay
    val check = pay?.let { PoolPayment.check(it.recipients) }
    val v5 = check != null && !check.ok
    var fromCoins by remember { mutableStateOf(pay == null) }
    var outputsText by remember { mutableStateOf(if (check?.ok == true) check.scripts.size.toString() else "1") }
    var amountText by remember { mutableStateOf("100000") }
    var rateText by remember { mutableStateOf(pay?.let { rate(maxOf(1.0, it.feeRate)) } ?: "1") }
    var peersText by remember { mutableStateOf("5") }
    var minText by remember { mutableStateOf("2") }
    var hoursText by remember { mutableStateOf("6") }
    var inputsText by remember { mutableStateOf("5") }
    var password by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf(PoolDest.HOT) }
    var coins by remember { mutableStateOf<List<SpendableUtxo>?>(null) }
    val chosen = remember { mutableStateListOf<SpendableUtxo>() }
    LaunchedEffect(Unit) {
        coins = runCatching { if (model.isDemo) demoCoins() else pools.poolCoins() }.getOrDefault(emptyList())
        if (model.isDemo) coins?.take(2)?.let { chosen.addAll(it) }
    }

    val feeRate = rateText.replace(',', '.').toDoubleOrNull()
    val peers = peersText.toIntOrNull(); val minPeers = minText.toIntOrNull(); val hours = hoursText.toIntOrNull()
    val maxInputs = inputsText.toIntOrNull()
    // A shared payment (v5): the others get the payments' total back, as equal outputs when it splits evenly.
    val split = if (v5) Protocol.v5Split(check!!.amounts.sum(), outputsText.toIntOrNull() ?: 1) else null
    val outputs = when { split != null -> split.second; check != null -> check.scripts.size; else -> outputsText.toIntOrNull() }
    val rateOk = feeRate != null && feeRate in 1.0..500.0
    val outputsOk = outputs != null && outputs in 1..Protocol.MAX_OUTPUTS
    val total = chosen.sumOf { it.value }
    // Our own outputs: the payments (v5), else the equal outputs.
    val ourOuts = if (v5) check!!.amounts.size else outputs ?: 1
    val amount: Long? = when {
        split != null -> split.first
        check != null -> check.amount
        !fromCoins -> amountText.filter { it.isDigit() }.toLongOrNull()
        chosen.isEmpty() || !rateOk || !outputsOk -> null
        else -> PoolTx.noChangeAmount(total, chosen.size, feeRate!!, outputs = outputs!!)
    }
    val ourTotal = if (v5) check!!.amounts.sum() else (amount ?: 0) * (outputs ?: 1)
    val change = if (chosen.isEmpty() || !rateOk) null else PoolTx.changeOf(total, ourTotal, feeRate!!, chosen.size, outputs = ourOuts)
    val error = when {
        !rateOk -> "Fee: 1 to 500 sat/vB."
        !outputsOk -> "Equal outputs per person: 1 to ${Protocol.MAX_OUTPUTS}."
        maxInputs == null || maxInputs !in 1..Protocol.MAX_INPUTS -> "Coins per person: 1 to ${Protocol.MAX_INPUTS}."
        peers == null || peers !in Protocol.MIN_PEERS..Protocol.MAX_PEERS -> "Most people: ${Protocol.MIN_PEERS} to ${Protocol.MAX_PEERS}."
        minPeers == null || minPeers < Protocol.MIN_PEERS || minPeers > peers -> "Fewest people: ${Protocol.MIN_PEERS} to $peers (the most)."
        hours == null || hours !in 1..72 -> "Open for 1 to 72 hours."
        chosen.isEmpty() -> "Tick the coins to put in."
        chosen.size > maxInputs -> "You ticked ${chosen.size} coins: set the most coins per person to at least ${chosen.size}."
        amount == null || amount < Protocol.TEST_MIN_AMOUNT || amount > Protocol.MAX_AMOUNT ->
            "Amount per output: ${formatSats(Protocol.TEST_MIN_AMOUNT)} to ${formatSats(Protocol.MAX_AMOUNT)} sats."
        amount * outputs!! < Protocol.MIN_AMOUNT -> "${formatSats(amount * outputs)} sats per person in all: a pool takes at least ${formatSats(Protocol.MIN_AMOUNT)} each."
        change == null -> "These coins do not cover ${formatSats(ourTotal)} sats plus your fee share."
        else -> null
    }

    ScreenColumn {
        BackLink { model.go(if (pay != null) Screen.PoolPay else Screen.Pools) }
        PoolsMessages(pools, ui)
        Text(if (pay != null) "Pay through a pool" else "Open a pool", style = MaterialTheme.typography.titleLarge, color = TextMain)
        if (pay != null && check != null) Panel {
            if (v5) {
                Text("${formatSats(check.amounts.sum())} sats to ${check.amounts.size} recipient(s) · a shared payment",
                    style = MaterialTheme.typography.titleMedium, color = TextMain)
                pay.recipients.forEach { (a, v) -> Text(shortAddress(a) + " · " + formatSats(v) + " sats", style = MaterialTheme.typography.bodySmall, color = Purple) }
                Explain("Each of the others puts in ${formatSats(check.amounts.sum())} sats too and gets it back as equal outputs, while your " +
                    "outputs are the payments. The chain shows the payments as one person's, but not which coins paid them.")
                Field(outputsText, { outputsText = it.filter { c -> c.isDigit() } }, "equal outputs for each of the others (1–${Protocol.MAX_OUTPUTS})",
                    keyboard = KeyboardType.Number)
                split?.let { (d, k) ->
                    Explain(if (k > 1) "The others get it back as $k × ${formatSats(d)} sats." else "The others get it back as one output.")
                }
            } else {
                Text("${check.scripts.size} × ${formatSats(check.amount)} sats", style = MaterialTheme.typography.titleMedium, color = TextMain)
                pay.recipients.forEach { (a, _) -> Text(shortAddress(a), style = MaterialTheme.typography.bodySmall, color = Purple) }
                Explain("A pool of exactly this amount opens, and its outputs for you go straight to the recipients: on the chain the " +
                    "payment looks like any other output of the round.")
            }
        } else Panel {
            SectionLabel("Pool amount")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("From your coins, no change", fromCoins) { fromCoins = true }
                Chip("Type the amount", !fromCoins) { fromCoins = false }
            }
            Explain(if (fromCoins) "Tick whole coins below: the pool's amount is worked out from them so they go in with no change " +
                "at all, the best for your privacy." else "The amount of each equal output; your coins below cover it and the rest comes back as change.")
            Field(outputsText, { outputsText = it.filter { c -> c.isDigit() } }, "equal outputs per person (1–${Protocol.MAX_OUTPUTS})", keyboard = KeyboardType.Number)
            if (!fromCoins) Field(amountText, { amountText = it.filter { c -> c.isDigit() } },
                if ((outputs ?: 1) > 1) "amount of each output (sats)" else "amount per person (sats)", keyboard = KeyboardType.Number)
            Explain("Native SegWit (bc1q) coins only: everyone's coins and outputs in the round look the same.")
        }
        Panel {
            SectionLabel("Fee and people")
            Field(rateText, { rateText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, "fee rate (sat/vB)", keyboard = KeyboardType.Decimal)
            Field(minText, { minText = it.filter { c -> c.isDigit() } }, "fewest people to close (${Protocol.MIN_PEERS}–${Protocol.MAX_PEERS})", keyboard = KeyboardType.Number)
            Field(peersText, { peersText = it.filter { c -> c.isDigit() } }, "most people (${Protocol.MIN_PEERS}–${Protocol.MAX_PEERS})", keyboard = KeyboardType.Number)
            Field(hoursText, { hoursText = it.filter { c -> c.isDigit() } }, "open for (hours)", keyboard = KeyboardType.Number)
            Field(inputsText, { inputsText = it.filter { c -> c.isDigit() } }, "most coins per person (1–${Protocol.MAX_INPUTS})", keyboard = KeyboardType.Number)
            Field(password, { password = it }, "password (optional: makes it private 🔒)")
            if (password.isNotEmpty()) Explain("Only people you give this password to can join. It is not announced anywhere.")
        }
        Panel {
            SectionLabel("Your coins")
            CoinList(pools, coins, chosen, maxInputs ?: 1)
            if (chosen.isNotEmpty() && amount != null && change != null) Text(
                if (v5) "${chosen.size} coin(s) · ${formatSats(total)} sats → ${formatSats(ourTotal)} paid + ${formatSats(change)} change · fee ${formatSats(total - ourTotal - change)}"
                else "${chosen.size} coin(s) · ${formatSats(total)} sats → ${outputs} × ${formatSats(amount)} + ${formatSats(change)} change · fee ${formatSats(total - ourTotal - change)}",
                style = MaterialTheme.typography.bodySmall, color = Good)
        }
        if (pay == null) Panel { DestChooser(ui, dest) { dest = it }; if (ui.watchKey == null) Explain("Your equal outputs go to fresh addresses of this wallet.") }
        error?.let { Text(reasonText(it), style = MaterialTheme.typography.bodySmall, color = Warn) }
        PrimaryButton("Open the pool with ${model.unlockName}", Modifier.fillMaxWidth(), enabled = error == null && ui.busy == null) {
            pools.create(amount!!, feeRate!!, minPeers!!, peers!!, hours!!, maxInputs!!, outputs!!, password, chosen.toList(), dest, pay) { ok ->
                if (ok) model.go(Screen.Pools)
            }
        }
        Explain("Opening signs nothing that can move your coins: it only proves you own them. Making the pool's keys takes a few seconds.")
    }
}

/** Screenshot mode: made-up coins, so the pickers have something to show. */
private fun demoCoins(): List<SpendableUtxo> = listOf(
    SpendableUtxo("4f".repeat(32), 0, 100_209, 0, 2, 970_000),
    SpendableUtxo("5e".repeat(32), 1, 61_234, 1, 0, 970_100),
    SpendableUtxo("6d".repeat(32), 0, 250_000, 0, 1, 969_000),
)
