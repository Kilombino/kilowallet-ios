package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
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
    val hot = state.kind == WalletKind.HOT

    ScreenColumn {
        BackLink("← back to wallet") { model.back() }
        Text("Settings", style = MaterialTheme.typography.titleLarge, color = TextMain)

        // The app's language: the phone's by default, or one fixed here (each named in its own
        // language), as in the Android app's settings.
        Panel {
            SectionLabel("Language")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Automatic", I18n.choice.value == I18n.AUTO) { I18n.set(I18n.AUTO) }
                I18n.LANGUAGES.forEach { (code, name) ->
                    Chip(name, I18n.choice.value == code) { I18n.set(code) }
                }
            }
        }

        // Values in a currency (Android 1.2.3: 36 of them): one choice for the whole app.
        Panel {
            SectionLabel("Currency")
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
                    "same for everyone: it carries nothing about your wallet.", color = TextSoft)
            if (state.fiat != Fiat.OFF) KeyValue("price of 1 ${Chain.TICKER}", state.perCoin?.let { Fiat.format(it, state.fiat) } ?: "no price yet")
        }

        Panel {
            SectionLabel("Electrum server")
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

        Panel(accent = if (hot) Purple else Orange) {
            SectionLabel(if (hot) "Hot wallet" else "Watch-only wallet", if (hot) Purple else Orange)
            KeyValue("addresses", state.scriptType.label)
            state.zpub?.let { z ->
                Text(z, style = MaterialTheme.typography.bodySmall, color = TextFaint)
                LinkText("copy the public key (zpub)", color = Purple) { clipboard.setText(AnnotatedString(z)) }
            }
            if (hot) SecondaryButton("Show recovery words", Modifier.fillMaxWidth(), color = TextMain) { model.revealWords() }
            SecondaryButton("Remove this wallet", Modifier.fillMaxWidth(), color = Bad) { confirmDelete = true }
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

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove this wallet?", style = MaterialTheme.typography.titleMedium) },
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
