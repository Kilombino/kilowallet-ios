package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import com.kilombino.kilowallet.app.Tab
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.kilombino.kilowallet.app.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.UiState
import com.kilombino.kilowallet.app.WalletKind
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.core.chain.Chain
import com.kilombino.kilowallet.core.chain.NodeEndpoint
import com.kilombino.kilowallet.core.market.Fiat
import com.kilombino.kilowallet.core.market.MarketFeed

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(model: WalletModel, state: UiState, platform: AppPlatform) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var host by remember { mutableStateOf(state.endpoint.host) }
    var port by remember { mutableStateOf(state.endpoint.port.toString()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var nodeOpen by remember { mutableStateOf(false) }
    var zpubQr by remember { mutableStateOf(false) }
    val hot = state.kind == WalletKind.HOT
    val poolsOn = model.pools.state.collectAsState().value.enabled

    ScreenColumn {
        BackLink("← back to wallet") { model.back() }

        // In Android 1.4.4's order: one panel of settings, then the recovery words at the very end.
        Panel {
            SectionLabel("settings")
            Text("Language", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Automatic", I18n.choice.value == I18n.AUTO) { I18n.set(I18n.AUTO) }
                I18n.LANGUAGES.forEach { (code, name) -> Chip(name, I18n.choice.value == code) { I18n.set(code) } }
            }

            SettingRow("Corecoin (SHA-256 chain)", "The same keys on the SHA-256 chain: balance and addresses. It comes in a later build on iPhone.") {
                LinkText("OPEN", color = TextFaint) {}
            }
            SettingRow("Tell me when the balance changes", "On iPhone the balance is checked while the app is open. Alerts with the app closed come in a later build.") {
                Switch(checked = false, onCheckedChange = null, enabled = false)
            }

            Text("Refresh", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            Explain("While the app is open")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30 to "30 s", 60 to "1 min", 300 to "5 min", 0 to "by hand").forEach { (sec, label) ->
                    Chip(label, state.refreshSeconds == sec) { model.setRefreshSeconds(sec) }
                }
            }

            if (hot) {
                SettingRow("Ark", "Off. Instant payments and Lightning through an Ark server: read how it works first.") {
                    LinkText("MORE INFO", color = Purple) { model.selectTab(Tab.ARK) }
                }
                SettingRow("Kilojoin", if (poolsOn) "On. Shared transactions with other people's coins, in pools with equal outputs."
                    else "Off. Read how it works first.") {
                    LinkText(if (poolsOn) "TURN OFF" else "OPEN", color = Purple) {
                        if (poolsOn) model.pools.setEnabled(false) else model.selectTab(Tab.POOLS)
                    }
                }
            }

            Text("Currency", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            var more by remember { mutableStateOf(false) }
            val available = Fiat.available(state.market)
            val shown = if (more) available else available.filter { it.code == "USD" || it.code == "EUR" || it.code == state.fiat }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Off", state.fiat == Fiat.OFF) { model.setFiat(Fiat.OFF) }
                shown.forEach { c -> Chip(c.code, state.fiat == c.code) { model.setFiat(c.code) } }
                if (!more && available.size > shown.size) Chip("▾", false) { more = true }
            }
            Explain(if (state.fiat == Fiat.OFF) "No prices: the app asks no price server." else
                "Prices from ${MarketFeed.SOURCE} (the ECB's daily reference for the exchange rates). The request is the " +
                    "same for everyone: it carries nothing about your wallet.")
            if (state.fiat != Fiat.OFF) KeyValue("price of 1 ${Chain.TICKER}", state.perCoin?.let { Fiat.format(it, state.fiat) } ?: "no price yet")

            Text(if (hot) "Address type for new addresses" else "Address type (derivation)", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            Text("● ${state.scriptType.label}", style = MaterialTheme.typography.bodySmall, color = Purple)
            if (hot) {
                Text("○ Taproot (bc1p)", style = MaterialTheme.typography.bodySmall, color = TextFaint)
                Explain("Taproot addresses come in a later build on iPhone.")
            }

            SettingRow("Gap limit: ${state.gapLimit}", "How many empty addresses in a row are checked before stopping. " +
                "Raise it if you use many addresses; lower it to scan faster.") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinkText("-5", color = Purple) { model.setGapLimit(state.gapLimit - 5) }
                    LinkText("+5", color = Purple) { model.setGapLimit(state.gapLimit + 5) }
                }
            }
            SettingRow("Full rescan", "To go faster, refreshes skip addresses that are already spent and confirmed. " +
                "If someone pays to an old address again, this finds it (it also runs by itself once a day).") {
                LinkText("RESCAN", color = Purple) { model.scan(); model.back() }
            }

            Row(Modifier.fillMaxWidth().clickable { nodeOpen = !nodeOpen }.padding(vertical = 6.dp)) {
                Text("BTC node & explorer", style = MaterialTheme.typography.bodyMedium, color = TextMain, modifier = Modifier.weight(1f))
                Text(if (nodeOpen) "▾" else "▸", color = Purple)
            }
            if (nodeOpen) {
                Text("Electrum server", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain("Your balance and history come from this server, and your sends go through it. It learns your " +
                    "addresses, so for the most privacy point it at your own Fulcrum/Electrs on the ${Chain.DISPLAY} chain. TLS only " +
                    "(plain TCP is allowed for a server on your local network).", color = TextSoft)
                Field(host, { host = it.trim() }, "host")
                Field(port, { port = it.filter { c -> c.isDigit() } }, "port (TLS)", keyboard = KeyboardType.Number)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Use", Modifier.weight(1f), enabled = host.isNotBlank() && port.toIntOrNull() in 1..65535) {
                        model.setServer(host, port.toIntOrNull()); model.back()
                    }
                    SecondaryButton("Default", Modifier.weight(1f), color = TextMain) {
                        host = NodeEndpoint.DEFAULT.host; port = NodeEndpoint.DEFAULT.port.toString()
                        model.setServer("", null)
                    }
                }
                KeyValue("in use", state.endpoint.toString() + if (state.endpoint.isCustom) " (yours)" else " (default)")
                Text("Pinned certificate (SHA-256):", style = MaterialTheme.typography.bodySmall, color = TextSoft)
                Text(state.pinned?.let { chunked(it) } ?: "none yet — trusted on first connection",
                    style = MaterialTheme.typography.bodySmall, color = TextFaint)
            }

            state.zpub?.let { z ->
                Text(shortAddress(z), style = MaterialTheme.typography.bodySmall, color = TextFaint)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LinkText("SHOW ZPUB (QR)", color = Purple) { zpubQr = true }
                    LinkText("copy the public key (zpub)", color = Purple) { clipboard.setText(AnnotatedString(z)) }
                }
            }

            SettingRow("Street mode", "On: balances and amounts are hidden every time the app opens, until you unlock them " +
                "with the eye. Off: they always show (turning it off asks for your fingerprint).") {
                Switch(checked = state.streetMode, onCheckedChange = { model.setStreetMode(it) })
            }

            Text("Backup file", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            Explain("The backup file (the same one as Kilowallet for Android, to restore on either) comes in a later build on iPhone. " +
                "Until then your written recovery words are the backup.")
            SecondaryButton("SAVE A BACKUP FILE", Modifier.fillMaxWidth(), enabled = false) {}
            SecondaryButton("SWITCH / NEW WALLET", Modifier.fillMaxWidth()) { confirmDelete = true }
            LinkText("forget this wallet", color = Bad) { confirmDelete = true }
        }

        if (hot) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            LinkText("RECOVERY WORDS", color = TextMain) { model.revealWords() }
        }

        Panel {
            SectionLabel("About")
            Text("Kilowallet for iOS ${platform.appVersion}", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            Explain("A self-custodial wallet for Bitcoin on the ${Chain.DISPLAY} proof-of-work chain (${Chain.TICKER}). " +
                "No account, no analytics, no tracking. The app contacts only the Electrum server above and, unless " +
                "the currency is off, the price feed; opening a transaction on the explorer is done in your browser, only when you tap it.", color = TextSoft)
            LinkText("github.com/Kilombino/kilowallet-ios", color = Purple) { runCatching { uri.openUri("https://github.com/Kilombino/kilowallet-ios") } }
            LinkText("kilombino.com", color = Purple) { runCatching { uri.openUri("https://kilombino.com") } }
        }
    }

    if (zpubQr) state.zpub?.let { z ->
        AlertDialog(
            onDismissRequest = { zpubQr = false },
            title = { Text("Show the public key", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    platform.qrImage(z)?.let { qr ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
                                Image(qr, contentDescription = "QR code of the address", modifier = Modifier.size(220.dp),
                                    contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                            }
                        }
                    }
                    Text(z, style = MaterialTheme.typography.bodySmall, color = TextSoft)
                    Explain("Anyone with it sees every address and payment of this wallet (but cannot spend).")
                }
            },
            confirmButton = { TextButton(onClick = { zpubQr = false }) { Text("CLOSE", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(if (hot) "Switch to another wallet?" else "Forget this wallet?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(if (hot) "The recovery words are deleted from this iPhone. Your coins stay on the chain, but only your " +
                "written words can bring them back. Make sure you have them." else "The public key is forgotten. Nothing can be lost: it was watch-only.",
                style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { TextButton(onClick = { confirmDelete = false; model.deleteWallet() }) { Text("REMOVE", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

/** A setting: its name and what it does on the left, its control on the right (Android's rows). */
@Composable
private fun SettingRow(title: String, explain: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = TextMain)
            Explain(explain)
        }
        control()
    }
}
