package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.res.Res
import com.kilombino.kilowallet.app.res.logo
import com.kilombino.kilowallet.core.crypto.Bip39
import org.jetbrains.compose.resources.painterResource

@Composable
fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Gap),
    ) { content() }
}

@Composable
fun Logo(size: Int) {
    Image(painterResource(Res.drawable.logo), contentDescription = "Kilowallet",
        modifier = Modifier.size(size.dp).clip(CircleShape))
}

@Composable
fun BackLink(text: String = "← back", onClick: () -> Unit) = LinkText(text, onClick = onClick)

@Composable
fun WelcomeScreen(model: WalletModel) {
    val state by model.state.collectAsState()
    ScreenColumn {
        if (state.wallets.isNotEmpty()) BackLink("← back to wallets") { model.go(com.kilombino.kilowallet.app.Screen.Wallets) }
        Field(state.newWalletName, model::setNewWalletName, "Wallet name")
        if (state.settingUp) Explain("Saving wallet securely…", color = Purple)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Logo(150) }
        Text("Kilowallet", style = MaterialTheme.typography.displayLarge, color = Purple)
        Text("BITCOIN BLAKE2b (XBT) WALLET", style = MaterialTheme.typography.titleLarge, color = TextSoft)
        Explain("Self-custodial: your keys are made on this iPhone and never leave it. No account, no tracking. " +
            "The app only talks to the Electrum server you choose.")
        Panel {
            SectionLabel("Hot wallet")
            Explain("Create a wallet you can send from. Its recovery words are generated on THIS device and kept " +
                "in the iOS Keychain, unlocked only by Face ID, Touch ID or your passcode. Native SegWit (bc1q).", color = TextSoft)
            PrimaryButton("New wallet · 12 words", Modifier.fillMaxWidth()) { model.startCreate(12) }
            SecondaryButton("New wallet · 24 words", Modifier.fillMaxWidth()) { model.startCreate(24) }
            SecondaryButton("Restore from words", Modifier.fillMaxWidth(), color = TextMain) { model.go(com.kilombino.kilowallet.app.Screen.Restore) }
        }
        Panel(accent = Orange) {
            SectionLabel("Watch-only wallet", Orange)
            Explain("Paste an extended PUBLIC key (zpub, also ypub/xpub) to watch balances without any way to spend. " +
                "Careful: it reveals every address you will ever use — keep it like a bank statement.", color = TextSoft)
            SecondaryButton("Watch a zpub", Modifier.fillMaxWidth(), color = Orange) { model.go(com.kilombino.kilowallet.app.Screen.Watch) }
        }
        Explain("XBT here is Bitcoin on the BLAKE2b proof-of-work chain (XBT): same addresses and history as Bitcoin " +
            "up to block 961 640, a different proof of work after it.")
    }
}

@Composable
fun WordGrid(words: List<String>) {
    val half = (words.size + 1) / 2
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (col in 0..1) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in col * half until minOf(words.size, (col + 1) * half)) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PanelSoft).padding(8.dp)) {
                            Text("${i + 1}".padStart(2, ' '), style = MaterialTheme.typography.bodyMedium, color = TextFaint,
                                modifier = Modifier.width(28.dp))
                            Text(words[i], style = MaterialTheme.typography.bodyLarge, color = TextMain, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateShowScreen(model: WalletModel, words: List<String>) {
    ScreenColumn {
        BackLink { model.back() }
        Text("Your recovery words", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("Write these ${words.size} words on paper, in order. They ARE your wallet: anyone with them can take your " +
            "coins, and without them a lost phone means lost coins. Never type them into a website, never photograph them.",
            color = Warn)
        WordGrid(words)
        PrimaryButton("I wrote them down", Modifier.fillMaxWidth()) { model.wroteDown(words) }
    }
}

@Composable
fun CreateConfirmScreen(model: WalletModel, words: List<String>, positions: List<Int>) {
    val answers = remember { mutableStateMapOf<Int, String>() }
    ScreenColumn {
        BackLink("← show the words again") { model.go(com.kilombino.kilowallet.app.Screen.CreateShow(words)) }
        Text("Check your paper", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("Type the words at these positions, from what you wrote down.", color = TextSoft)
        for (p in positions) {
            Field(answers[p] ?: "", { answers[p] = it.lowercase().trim() }, "Word #${p + 1}")
        }
        PrimaryButton("Create wallet", Modifier.fillMaxWidth(), enabled = positions.all { !answers[it].isNullOrBlank() }) {
            model.confirmCreate(words, positions.associateWith { answers[it] ?: "" })
        }
    }
}

@Composable
fun RestoreScreen(model: WalletModel) {
    var text by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val unknown = words.filterIndexed { i, w -> !Bip39.isWord(w) && (i < words.size - 1 || text.endsWith(" ")) }
    val last = if (text.endsWith(" ")) "" else words.lastOrNull().orEmpty()
    val suggestions = if (last.isNotEmpty() && !Bip39.isWord(last)) Bip39.suggestions(last) else emptyList()
    val valid = Bip39.isValid(words)
    ScreenColumn {
        BackLink { model.back() }
        Text("Restore from words", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("Enter 12, 15, 18, 21 or 24 English BIP-39 recovery words in order. " +
            "Restores the first BIP-84 account (account 0) on XBT BLAKE2b. Other account types or additional accounts are not restored.", color = TextSoft)
        Field(text, { text = it }, "recovery words", singleLine = false, minLines = 4)
        if (suggestions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s ->
                LinkText(s, color = Purple) { text = (words.dropLast(1) + s).joinToString(" ") + " " }
            }
        }
        val status = when {
            unknown.isNotEmpty() -> "Not in the word list: ${unknown.joinToString(", ")}"
            words.size !in setOf(12, 15, 18, 21, 24) -> "${words.size} words"
            !valid -> "${words.size} words, but the checksum does not match: check the order"
            else -> "${words.size} words · valid"
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (valid) Good else if (unknown.isNotEmpty()) Bad else TextSoft)
        if (showPass) {
            Field(pass, { pass = it }, "passphrase (the \"25th word\", if you set one)", secret = true)
            Explain("A wrong passphrase opens a different, empty wallet: no error can tell you.", color = Warn)
        } else LinkText("+ I also used a passphrase") { showPass = true }
        PrimaryButton("Restore", Modifier.fillMaxWidth(), enabled = valid) { model.restore(words, pass) }
    }
}

@Composable
fun WatchScreen(model: WalletModel, platform: AppPlatform) {
    var key by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    ScreenColumn {
        BackLink { model.back() }
        Text("Watch-only wallet", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("Paste the account's extended public key. From it the app derives your addresses and shows balance and " +
            "movements; it can never spend.", color = TextSoft)
        Field(key, { key = it.trim() }, "zpub / ypub / xpub", singleLine = false, minLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Paste", Modifier.weight(1f), color = TextMain) { clipboard.getText()?.text?.let { key = it.trim() } }
            if (platform.canScanQr) SecondaryButton("Scan QR", Modifier.weight(1f), color = TextMain) {
                platform.scanQr { it?.let { key = it.trim() } }
            }
        }
        PrimaryButton("Watch", Modifier.fillMaxWidth(), enabled = key.length > 100, color = Orange) { model.watch(key) }
    }
}

@Composable
fun ShowWordsScreen(model: WalletModel, words: List<String>) {
    ScreenColumn {
        BackLink("← done") { model.back() }
        Text("Recovery words", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("Anyone who sees these can take your coins. Make sure nobody is watching.", color = Warn)
        WordGrid(words)
        PrimaryButton("Done", Modifier.fillMaxWidth()) { model.back() }
    }
}

internal val BigNumber = 52.sp
