package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.JoinBlaketModel
import com.kilombino.kilowallet.app.Screen
import com.kilombino.kilowallet.app.UiState
import com.kilombino.kilowallet.app.WalletKind
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.i18n.Text
import com.kilombino.kilowallet.core.chain.Chain
import com.kilombino.kilowallet.core.crypto.Address
import com.kilombino.kilowallet.core.crypto.TxParse
import com.kilombino.kilowallet.core.joinblaket.JoinBlaketMarket
import com.kilombino.kilowallet.core.joinblaket.TakerPlan
import com.kilombino.kilowallet.core.joinblaket.TakerResult
import com.kilombino.kilowallet.core.joinblaket.TakerStep
import com.kilombino.kilowallet.core.wallet.formatSats
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/**
 * PAY THROUGH JOINBLAKET on the send screen (Android 1.6.0's HotWallet block): the market as
 * start10's orderbook watcher sees it, read again every 30 s while the screen is open, so the
 * user knows before sending how many makers there are, what amounts they take and what it would
 * cost; then the button, which checks the coins, unlocks and starts the round.
 */
@Composable
fun JoinBlaketSendBlock(model: WalletModel, state: UiState, to: String, amount: Long, rate: Double) {
    val jb = model.joinBlaket
    val mv by jb.market.collectAsState()
    val view by jb.view.collectAsState()
    val err by jb.error.collectAsState()
    val preparing by jb.preparing.collectAsState()
    LaunchedEffect(Unit) {
        jb.clearError()
        while (true) { jb.refreshMarket(); delay(30_000) }
    }
    val m = mv.market
    val takes = m != null && amount > 0 && JoinBlaketMarket.estimate(m, amount, 1.0) != null
    Panel {
        SectionLabel("🔀 JoinBlaket")
        when {
            m == null && !mv.failed -> Explain("Reading the JoinBlaket market…")
            m == null -> Explain("The JoinBlaket market can't be read right now.")
            m.makers == 0 || m.minAmount == null || m.maxAmount == null -> Explain("No makers with a fidelity bond right now.")
            else -> {
                val minAmount = m.minAmount ?: 0L
                val maxAmount = m.maxAmount ?: 0L
                val who = if (m.makers == 1) "${m.makers} maker with a fidelity bond" else "${m.makers} makers with a fidelity bond"
                Explain("$who · minimum ${formatSats(minAmount)} sats · maximum ${formatSats(maxAmount)} sats")
                if (amount > 0) {
                    val e = JoinBlaketMarket.estimate(m, amount, rate)
                    if (e == null) Explain("No maker takes ${formatSats(amount)} sats: it has to be between the minimum and the maximum.", color = Warn)
                    else Explain(if (e.makers == 1)
                        "For ${formatSats(amount)} sats with ${e.makers} maker: ${formatSats(e.makerFees)} to the makers + about ${formatSats(e.miningFee)} mining fee = about ${formatSats(e.total)} sats"
                    else
                        "For ${formatSats(amount)} sats with ${e.makers} makers: ${formatSats(e.makerFees)} to the makers + about ${formatSats(e.miningFee)} mining fee = about ${formatSats(e.total)} sats")
                }
                if (m.makers == 1) Explain("With a single maker, that maker can link your coin to your payment.", color = Warn)
            }
        }
        // A bc1q recipient (the round's equal outputs are all native SegWit), an amount the makers take,
        // a spending wallet of native SegWit addresses, and no other JoinBlaket payment going.
        val recipientOk = remember(to) {
            runCatching { Address.decodeToScriptPubKey(to.trim()) }.getOrNull()?.let { it.size == 22 && it[0] == 0.toByte() } == true
        }
        val hotOk = state.kind == WalletKind.HOT && state.keyType.let { it == null || it == com.kilombino.kilowallet.core.crypto.ScriptType.P2WPKH }
        val busy = view?.finished == false
        val ready = takes && recipientOk && hotOk && !busy && !preparing
        SecondaryButton("🔀 PAY THROUGH JOINBLAKET", Modifier.fillMaxWidth(), enabled = ready) {
            jb.start(to.trim(), amount, rate) { started -> if (started) model.go(Screen.JoinBlaket) }
        }
        if (preparing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = Purple, modifier = Modifier.size(18.dp))
            Explain("Getting your coins ready…")
        }
        val why = when {
            m == null || !takes || amount <= 0 -> null
            !recipientOk -> "Only plain bc1q addresses can be paid through JoinBlaket."
            !hotOk -> "JoinBlaket needs a spending wallet with native SegWit (bc1q) addresses."
            busy -> "Another JoinBlaket payment is going: wait for it to end."
            else -> "You confirm the makers' fees and the mining fee before anything is signed. It takes a few minutes, over Tor."
        }
        why?.let { Explain(it) }
        if (why != null && ready) Explain("Keep Kilowallet open until it is done: iOS pauses apps in the background.")
        err?.let { Explain(it, color = Bad) }
    }
}

/** One line for where the round is (Android's notification line), in English: the screen translates it. */
internal fun joinBlaketLine(v: JoinBlaketModel.View): String {
    v.result?.let { r ->
        return when (r) {
            is TakerResult.Sent -> if (r.seen) "Done: the JoinBlaket transaction was broadcast." else "Sent to the makers; your server doesn't show it yet."
            is TakerResult.Failed -> r.reason
            is TakerResult.Cancelled -> r.reason
        }
    }
    if (v.plan != null) return "Confirm the JoinBlaket payment"
    return when (val s = v.step) {
        null, is TakerStep.Connecting -> "Connecting to JoinBlaket through Tor…"
        TakerStep.ReadingOffers -> "Reading the makers' offers…"
        is TakerStep.CheckingBonds -> "Checking the makers' fidelity bonds…"
        is TakerStep.Confirm -> "Confirm the JoinBlaket payment"
        is TakerStep.WaitingForMakers -> "Waiting for the makers: ${s.ready} of ${s.asked} ready"
        is TakerStep.CollectingSignatures -> "Collecting the makers' signatures…"
        TakerStep.Broadcasting -> "Signing and broadcasting…"
    }
}

/** The round going (or just ended) on the BTC tab: where it is, and the way back to its screen. */
@Composable
fun JoinBlaketBanner(model: WalletModel) {
    val v by model.joinBlaket.view.collectAsState()
    val view = v ?: return
    Panel(accent = if (view.result is TakerResult.Failed) Bad else Purple) {
        Text("🔀 Pay through JoinBlaket", style = MaterialTheme.typography.titleSmall, color = Purple)
        Explain(JoinBlaketModel.reasonText(joinBlaketLine(view)), color = TextSoft)
        LinkText("open the payment ›", color = Purple) { model.go(Screen.JoinBlaket) }
    }
}

/**
 * The round's screen (Android's JoinBlaketSheet): where it is, the plan to confirm (each maker's
 * fee, the mining fee, the total; nothing about the coins has left the phone yet), and how it
 * ended. ← back leaves it running; it shows on the BTC tab meanwhile.
 */
@Composable
fun JoinBlaketScreen(model: WalletModel) {
    val jb = model.joinBlaket
    val v by jb.view.collectAsState()
    val view = v
    ScreenColumn {
        BackLink { model.back() }
        Text("🔀 Pay through JoinBlaket", style = MaterialTheme.typography.titleLarge, color = TextMain)
        if (view == null) { Explain("No JoinBlaket payment is going."); return@ScreenColumn }
        Explain("Paying ${formatSats(view.amount)} sats to ${shortAddress(view.recipient)}", color = TextSoft)
        val plan = view.plan
        val result = view.result
        when {
            plan != null -> PlanDetails(plan)
            result != null -> ResultDetails(result)
            else -> Panel {
                Text(joinBlaketLine(view), style = MaterialTheme.typography.bodyMedium, color = TextSoft)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple)
                Explain("Keep Kilowallet open until it is done: iOS pauses apps in the background.")
            }
        }
        when {
            plan != null -> {
                PrimaryButton("CONFIRM & SIGN", Modifier.fillMaxWidth()) { jb.answer(true) }
                SecondaryButton("CANCEL", Modifier.fillMaxWidth(), color = TextSoft) { jb.cancel() }
            }
            view.finished -> PrimaryButton("CLOSE", Modifier.fillMaxWidth()) { jb.dismiss(); model.back() }
            !view.pastReturn -> SecondaryButton("CANCEL", Modifier.fillMaxWidth(), color = Bad) { jb.cancel() }
        }
    }
}

private fun rate2(r: Double): String {
    val t = (r * 100).roundToLong()
    return "${t / 100}.${(t % 100).toString().padStart(2, '0')}"
}

@Composable
private fun PlanDetails(p: TakerPlan) {
    Panel {
        Text("Confirm the JoinBlaket payment", style = MaterialTheme.typography.titleMedium, color = TextMain)
        Explain(if (p.makers.size == 1) "1 maker with a fidelity bond" else "${p.makers.size} makers with a fidelity bond", color = TextSoft)
        p.makers.forEachIndexed { i, m -> Explain("maker ${i + 1}: fee ${formatSats(m.fee)} sats") }
        Explain("Makers' fees: ${formatSats(p.makerFees)} sats", color = TextSoft)
        Explain("Mining fee: about ${formatSats(p.miningFeeEstimate)} sats, at most ${formatSats(p.miningFeeMax)} sats (${rate2(p.feeRate)} sat/vB)", color = TextSoft)
        Text("Total cost: about ${formatSats(p.totalEstimate)} sats, at most ${formatSats(p.totalMax)} sats",
            style = MaterialTheme.typography.bodyMedium, color = Purple)
        Explain("From ${p.inputs} coin(s) of yours (${formatSats(p.inputTotal)} sats); the change comes back to this wallet.")
        if (p.makers.size == 1) Explain("With a single maker, that maker can link your coin to your payment.", color = Warn)
        Explain("Nothing about your coins has left the phone yet. If a maker drops out, another may take its place within these fees.")
    }
}

@Composable
private fun ResultDetails(r: TakerResult) {
    when (r) {
        is TakerResult.Sent -> Panel(accent = Good) {
            Text(if (r.seen) "Done: the JoinBlaket transaction was broadcast." else "Sent to the makers; your server doesn't show it yet.",
                style = MaterialTheme.typography.bodyMedium, color = Good)
            Explain("${r.makers} maker(s) · makers' fees ${formatSats(r.makerFees)} sats · mining fee ${formatSats(r.txFee)} sats", color = TextSoft)
            Explain("Total cost: ${formatSats(r.makerFees + r.txFee)} sats", color = TextSoft)
            if (r.change > 0) Explain("Change back to you: ${formatSats(r.change)} sats", color = TextSoft)
            val vbytes = remember(r.rawHex) { runCatching { TxParse.vsize(r.rawHex) }.getOrNull() }
            if (vbytes != null && vbytes > 0) Explain("Transaction: $vbytes vB · whole mining fee ${formatSats(r.minerFee)} sats (${rate2(r.minerFee.toDouble() / vbytes)} sat/vB)")
            Explain(if (r.viaMaker) "Broadcast by a maker." else "Broadcast by your server.")
            SectionLabel("Transaction id", Good)
            Text(r.txid, style = MaterialTheme.typography.bodySmall, color = TextMain)
            val clipboard = LocalClipboardManager.current
            val uri = LocalUriHandler.current
            var copied by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LinkText(if (copied) "COPIED ✓" else "📋 COPY TXID", color = Purple) { clipboard.setText(AnnotatedString(r.txid)); copied = true }
                LinkText("🔎 EXPLORER", color = Purple) { runCatching { uri.openUri("${Chain.EXPLORER}/tx/${r.txid}") } }
            }
        }
        is TakerResult.Failed -> Panel(accent = Bad) {
            Text(JoinBlaketModel.reasonText(r.reason), style = MaterialTheme.typography.bodyMedium, color = Bad)
            r.detail?.let { d -> Explain(d) }
        }
        is TakerResult.Cancelled -> Panel(accent = Warn) {
            Text(JoinBlaketModel.reasonText(r.reason), style = MaterialTheme.typography.bodyMedium, color = Warn)
        }
    }
}
