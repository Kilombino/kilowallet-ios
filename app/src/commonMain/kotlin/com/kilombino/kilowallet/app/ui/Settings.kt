package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.kilombino.kilowallet.app.Screen
import com.kilombino.kilowallet.app.WalletKind
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.core.chain.Chain
import com.kilombino.kilowallet.core.chain.NodeEndpoint

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
        Text("Settings · ${state.walletName}", style = MaterialTheme.typography.titleLarge, color = TextMain)

        SecondaryButton("Manage wallets", Modifier.fillMaxWidth(), color = TextMain) { model.go(Screen.Wallets) }
        Panel {
            SectionLabel("Electrum server · shared by all wallets")
            Explain("Your balance and history come from this server, and your sends go through it. It learns your " +
                "addresses, so for the most privacy point it at your own Fulcrum/Electrs on the ${Chain.DISPLAY} chain. TLS is required for every server.", color = TextSoft)
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
            Text(state.pinned?.let { chunked(it) } ?: "none yet — approval required before wallet queries",
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
                "No account, no analytics, no tracking. The app contacts only the Electrum server above; opening a " +
                "transaction on the explorer is done in your browser, only when you tap it.", color = TextSoft)
            Text("Created by Kilombino", style = MaterialTheme.typography.bodyMedium, color = TextMain)
            Explain("Original project by Kilombino. This iOS distribution is maintained by romanmodin. Apache License 2.0.")
            Explain("Balances and confirmation counts rely on your selected server. Recent payment proofs check individual headers; the app does not independently validate the highest-work chain.")
            LinkText("github.com/romanmodin/kilowallet-ios", color = Purple) { runCatching { uri.openUri("https://github.com/romanmodin/kilowallet-ios") } }
            LinkText("github.com/Kilombino/kilowallet-ios", color = Purple) { runCatching { uri.openUri("https://github.com/Kilombino/kilowallet-ios") } }
            LinkText("kilombino.com", color = Purple) { runCatching { uri.openUri("https://kilombino.com") } }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove ${state.walletName}?", style = MaterialTheme.typography.titleMedium) },
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
