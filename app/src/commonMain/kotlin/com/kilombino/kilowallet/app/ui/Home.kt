package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.Image
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

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Your ${Chain.COIN}")
                Spacer(Modifier.weight(1f))
                val live = state.scan is ScanPhase.Done
                LinkText(if (state.refreshing) "refreshing…" else "↻ refresh", color = TextSoft) { model.refresh() }
                PulseDot(if (live) Good else if (state.scan is ScanPhase.Error) Bad else Warn)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatSats(state.total), fontSize = BigNumber, fontWeight = FontWeight.Bold, color = Purple,
                    style = MaterialTheme.typography.displayLarge)
                Text(" sats", style = MaterialTheme.typography.titleLarge, color = Purple.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Text("${formatBtc(state.total)} ${Chain.COIN} (${Chain.TICKER})", style = MaterialTheme.typography.bodyMedium, color = TextSoft)
            if (state.unconfirmed != 0L) Text("of which ${formatSats(state.unconfirmed)} sats unconfirmed",
                style = MaterialTheme.typography.bodySmall, color = Warn)
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
            if (state.txs.isEmpty()) {
                Explain(if (state.scan is ScanPhase.Done) "No movements yet. Receive to an address from RECEIVE." else "…")
            } else {
                Explain("tap a movement for its details")
                state.txs.take(200).forEach { tx -> MovementRow(tx) { detail = tx } }
            }
        }
        Explain("block ${formatSats(state.height.toLong())} · ${state.endpoint}" + (state.serverVersion?.let { " · $it" } ?: ""))
    }

    detail?.let { tx -> MovementDialog(tx) { detail = null } }
}

@Composable
private fun MovementRow(tx: TxConf, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val a = tx.amount
            Text(if (a == null) "? sats" else (if (a > 0) "+" else "") + formatSats(a) + " sats",
                style = MaterialTheme.typography.bodyLarge, color = if (a != null && a > 0) Good else TextMain)
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
private fun MovementDialog(tx: TxConf, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tx.amount?.let { (if (it > 0) "+" else "") + formatSats(it) + " sats" } ?: "Movement", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyValue("status", if (tx.pending) "in the mempool" else "${formatSats(tx.confirmations.toLong())} confirmations")
                if (!tx.pending) KeyValue("block", formatSats(tx.height.toLong()))
                tx.spv?.let {
                    KeyValue("payment proof", if (it) "✓ matches one server-supplied header" else "✗ did NOT verify",
                        if (it) Good else Bad)
                }
                Text(tx.txid, style = MaterialTheme.typography.bodySmall, color = TextSoft)
                LinkText("copy txid", color = Purple) { clipboard.setText(AnnotatedString(tx.txid)) }
                LinkText("open on ${Chain.EXPLORER.removePrefix("https://")}", color = Purple) {
                    runCatching { uri.openUri("${Chain.EXPLORER}/tx/${tx.txid}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("CLOSE", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextMain,
    )
}

@Composable
fun ReceiveScreen(model: WalletModel, platform: AppPlatform) {
    val clipboard = LocalClipboardManager.current
    val addr = remember { model.receiveAddress() }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    ScreenColumn {
        BackLink { model.back() }
        Text("Receive ${Chain.COIN}", style = MaterialTheme.typography.titleLarge, color = TextMain)
        if (addr == null) { Explain("No wallet."); return@ScreenColumn }
        val (address, path) = addr
        Panel {
            val qr = remember(address) { platform.qrImage(address) }
            if (qr != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(14.dp)) {
                    Image(qr, contentDescription = "QR code of the address", modifier = Modifier.size(240.dp),
                        contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                }
            }
            Text(chunked(address), style = MaterialTheme.typography.titleMedium, color = TextMain,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(path, style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
        PrimaryButton(if (copied) "Copied" else "Copy address", Modifier.fillMaxWidth()) {
            clipboard.setText(AnnotatedString(address)); copied = true
        }
        Explain("Only send XBT on the BLAKE2b network.", color = TextSoft)
        Explain("Previously issued addresses still work.")
    }
}

internal val HugeNumber = 40.sp
