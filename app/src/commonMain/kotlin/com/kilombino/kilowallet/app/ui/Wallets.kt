package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.kilombino.kilowallet.app.*

@Composable
fun WalletsScreen(model: WalletModel, state: UiState) {
    var renaming by remember { mutableStateOf<WalletInfo?>(null) }
    var name by remember { mutableStateOf("") }
    ScreenColumn {
        if (state.kind != null) BackLink("← back to wallet") { model.back() }
        Text("Wallets", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("Each wallet has its own balance, addresses and backup. Only the selected wallet is refreshed.", color = TextSoft)
        state.wallets.forEach { wallet ->
            Panel(accent = if (wallet.kind == WalletKind.HOT) Purple else Orange) {
                Text(wallet.name, style = MaterialTheme.typography.titleMedium, color = TextMain)
                Explain((if (wallet.kind == WalletKind.HOT) "Hot wallet" else "Watch-only") +
                    if (wallet.id == state.walletId) " · selected" else "", color = TextSoft)
                PrimaryButton(if (wallet.id == state.walletId) "Open wallet" else "Select wallet", Modifier.fillMaxWidth()) {
                    model.selectWallet(wallet.id)
                }
                LinkText("Rename", color = TextSoft) { renaming = wallet; name = wallet.name }
            }
        }
        PrimaryButton("Add wallet", Modifier.fillMaxWidth(), enabled = state.wallets.size < 20 && !state.settingUp) { model.addWallet() }
        Explain("Create a new wallet, restore a separate backup, or add a watch-only public key. Remove a wallet from its Settings.")
    }
    renaming?.let { wallet ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename wallet", color = TextMain) },
            text = { Field(name, { name = it.take(40) }, "Wallet name") },
            confirmButton = { TextButton(onClick = { model.renameWallet(wallet.id, name); renaming = null },
                enabled = name.trim().isNotEmpty()) { Text("SAVE", color = Purple) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg,
        )
    }
}
