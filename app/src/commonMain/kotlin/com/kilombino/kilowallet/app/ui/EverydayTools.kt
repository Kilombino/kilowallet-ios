package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.*

@Composable
fun RecipientsDialog(model: WalletModel, state: UiState, initialAddress: String = "",
    onChoose: ((String) -> Unit)? = null, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(initialAddress) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Saved recipients") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Explain("For ${state.walletName} · XBT BLAKE2b only. Confirm the full address with the recipient before paying. Saved locally on this device.")
            state.recipientError?.let { Explain(it, color = Bad) }
            Field(name, { name = it.take(60) }, "Name")
            Field(address, { address = it.trim() }, "XBT recipient address", singleLine = false, minLines = 2)
            SecondaryButton("Save recipient", enabled = name.isNotBlank() && address.isNotBlank() && state.recipientError == null) {
                if (model.saveRecipient(state.walletId ?: "", address, name)) { name = ""; address = "" }
            }
            if (state.recipients.isEmpty()) Explain("No saved recipients yet.")
            state.recipients.forEach { recipient ->
                Text(recipient.name, color = TextMain)
                Text(recipient.address, style = MaterialTheme.typography.bodySmall, color = TextSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onChoose != null) LinkText("Use address") { onChoose(recipient.address); onClose() }
                    LinkText("Edit") { name = recipient.name; address = recipient.address }
                    LinkText("Remove", color = Bad) { model.removeRecipient(state.walletId ?: "", recipient.address) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("CLOSE") } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft)
}

@Composable
fun BackupCheckScreen(model: WalletModel, state: UiState) {
    var text by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    ScreenColumn {
        BackLink { text = ""; pass = ""; model.back() }
        Text("Check written backup", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("For ${state.walletName}. Enter your written recovery words and exact optional passphrase. This check runs locally and does not send or save what you type.")
        Field(text, { text = it }, "Recovery words", secret = true, singleLine = false, minLines = 4)
        Field(pass, { pass = it }, "Optional passphrase", secret = true)
        Explain("A successful check confirms this account can be derived. It does not back up labels, recipients or frozen-coin settings.")
        PrimaryButton(if (state.checkingBackup) "Checking…" else "Verify backup", Modifier.fillMaxWidth(),
            enabled = !state.checkingBackup && text.isNotBlank()) {
            model.verifyBackup(text.trim().split(Regex("\\s+")), pass)
        }
    }
}
