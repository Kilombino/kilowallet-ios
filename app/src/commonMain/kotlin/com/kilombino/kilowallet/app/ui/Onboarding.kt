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
import com.kilombino.kilowallet.app.i18n.Text
import androidx.compose.runtime.Composable
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
import com.kilombino.kilowallet.core.crypto.Bip32
import com.kilombino.kilowallet.core.crypto.ScriptType
import com.kilombino.kilowallet.app.res.Res
import com.kilombino.kilowallet.app.res.logo
import com.kilombino.kilowallet.core.crypto.Bip39
import org.jetbrains.compose.resources.painterResource

@Composable
fun ScreenColumn(content: @Composable () -> Unit) = ScreenColumn(rememberScrollState(), content)

/** The same, scrolled by [scroll] (a screen that moves itself, as Tor's log to its end). */
@Composable
fun ScreenColumn(scroll: androidx.compose.foundation.ScrollState, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 12.dp),
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
fun WelcomeScreen(model: WalletModel, state: com.kilombino.kilowallet.app.UiState) {
    ScreenColumn {
        // Adding the other wallet next to one that exists (Android: both side by side): a way back.
        if (state.kind != null) BackLink { model.go(com.kilombino.kilowallet.app.Screen.Home) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Logo(150) }
        Text("Kilowallet", style = MaterialTheme.typography.displayLarge, color = Purple)
        Text("BITCOIN BLAKE2b (BTC) WALLET", style = MaterialTheme.typography.titleLarge, color = TextSoft)
        Explain("Self-custodial: your keys are made on this iPhone and never leave it. No account, no tracking. " +
            "The app only talks to the Electrum server you choose.")
        Panel {
            SectionLabel("Hot wallet")
            Explain("Create a wallet you can send from. Its recovery words are generated on THIS device and kept " +
                "in the iOS Keychain, unlocked only by Face ID, Touch ID or your passcode. Native SegWit (bc1q).", color = TextSoft)
            PrimaryButton("New wallet · 12 words", Modifier.fillMaxWidth()) { model.startCreate(12) }
            SecondaryButton("New wallet · 24 words", Modifier.fillMaxWidth()) { model.startCreate(24) }
            SecondaryButton("New wallet · from dice", Modifier.fillMaxWidth()) { model.startDice() }
            SecondaryButton("Restore from words", Modifier.fillMaxWidth(), color = TextMain) { model.go(com.kilombino.kilowallet.app.Screen.Restore) }
            SecondaryButton("Restore from a backup file (.kab)", Modifier.fillMaxWidth(), color = TextMain) { model.pickBackupFile() }
        }
        Panel(accent = Orange) {
            SectionLabel("Watch-only wallet", Orange)
            Explain("Paste an extended PUBLIC key (zpub, also ypub/xpub) to watch balances without any way to spend. " +
                "Careful: it reveals every address you will ever use — keep it like a bank statement.", color = TextSoft)
            SecondaryButton("Watch a zpub", Modifier.fillMaxWidth(), color = Orange) { model.go(com.kilombino.kilowallet.app.Screen.Watch) }
        }
        Explain("BTC here is Bitcoin on the BLAKE2b proof-of-work chain: same addresses and history as Bitcoin " +
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
    val valid = (words.size == 12 || words.size == 24) && Bip39.isValid(words)
    ScreenColumn {
        BackLink { model.back() }
        Text("Restore from words", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("Type your 12 or 24 recovery words in order, separated by spaces. A BIP-39 / BIP-84 backup from " +
            "Kilowallet for Android or any standard wallet works.", color = TextSoft)
        Field(text, { text = it }, "recovery words", singleLine = false, minLines = 4)
        if (suggestions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s ->
                LinkText(s, color = Purple) { text = (words.dropLast(1) + s).joinToString(" ") + " " }
            }
        }
        val status = when {
            unknown.isNotEmpty() -> "Not in the word list: ${unknown.joinToString(", ")}"
            words.size != 12 && words.size != 24 -> "${words.size} words"
            !valid -> "${words.size} words, but the checksum does not match: check the order"
            else -> "${words.size} words · valid"
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (valid) Good else if (unknown.isNotEmpty()) Bad else TextSoft)
        if (showPass) {
            Field(pass, { pass = it }, "passphrase (the \"25th word\", if you set one)")
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
        // A plain xpub doesn't say which addresses it makes: its type is asked (Android 1.2.4).
        val parsed = remember(key) { runCatching { Bip32.parseExtendedPubKey(key.filterNot { it.isWhitespace() }) }.getOrNull() }
        var picked by remember(key) { mutableStateOf<ScriptType?>(null) }
        val needsType = parsed?.scriptType == ScriptType.P2PKH
        if (parsed != null && parsed.depth != 3) Explain("This key is at depth ${parsed.depth}, not an account key (m/84'/0'/0', m/86'/0'/0'…): " +
            "its addresses won't match your wallet's. Export the account xpub instead.", color = Bad)
        if (needsType) Panel(accent = Orange) {
            Explain("A plain xpub doesn't say which addresses it makes: pick the type your wallet uses.", color = TextSoft)
            listOf(
                ScriptType.P2WPKH to "Native SegWit · bc1q… (BIP84)",
                ScriptType.P2TR to "Taproot · bc1p… (BIP86)",
                ScriptType.P2SH_P2WPKH to "Nested SegWit · 3… (BIP49)",
                ScriptType.P2PKH to "Legacy · 1… (BIP44)",
            ).forEach { (t, name) ->
                LinkText((if (t == picked) "● " else "○ ") + name, color = if (t == picked) Orange else TextMain,
                    modifier = Modifier.fillMaxWidth()) { picked = t }
            }
        }
        PrimaryButton("Watch", Modifier.fillMaxWidth(), enabled = key.length > 100 && (!needsType || picked != null), color = Orange) {
            model.watch(key, picked)
        }
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

/**
 * New wallet from physical dice, SeedSigner's way (Android 1.2, `DiceScreen`): every roll is a digit
 * 1–6, 50 make 12 words and 99 make 24; the same rolls in a SeedSigner make the same seed. Rolls
 * that look made up are flagged before the words are shown ([com.kilombino.kilowallet.core.crypto.DiceCheck]).
 */
@Composable
fun DiceScreen(model: WalletModel) {
    var rolls by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(50) }
    var warned by remember { mutableStateOf<List<String>?>(null) }
    ScreenColumn {
        BackLink { model.back() }
        Text("Roll dice for your seed", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("Roll a real die and tap the number that comes up, $target times. Nothing from this phone's random " +
            "generator goes into the seed: the words come from your rolls only (SHA-256 of them, as SeedSigner does), " +
            "so a SeedSigner given the same rolls shows the same words.", color = TextSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(50 to "12 words · 50 rolls", 99 to "24 words · 99 rolls").forEach { (n, label) ->
                LinkText((if (n == target) "● " else "○ ") + label, color = if (n == target) Purple else TextMain) {
                    target = n; if (rolls.length > n) rolls = rolls.take(n)
                }
            }
        }
        Text("${rolls.length} / $target", style = MaterialTheme.typography.titleMedium, color = if (rolls.length == target) Good else TextMain)
        Text(rolls.chunked(5).joinToString(" "), style = MaterialTheme.typography.bodyMedium, color = TextSoft)
        for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6))) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { d ->
                PrimaryButton("$d", Modifier.weight(1f), enabled = rolls.length < target) { rolls += d.toString() }
            }
        }
        // Well below the dice, so a slip off a die never undoes or clears anything.
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Undo last", Modifier.weight(1f), color = TextSoft, enabled = rolls.isNotEmpty()) { rolls = rolls.dropLast(1) }
            SecondaryButton("Clear", Modifier.weight(1f), color = TextSoft, enabled = rolls.isNotEmpty()) { rolls = "" }
        }
        PrimaryButton("Make the words", Modifier.fillMaxWidth(), enabled = rolls.length == target) {
            val why = com.kilombino.kilowallet.core.crypto.DiceCheck.problems(rolls)
            if (why.isEmpty()) model.diceWords(rolls) else warned = why
        }
    }
    warned?.let { why ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { warned = null },
            title = { Text("These don't look like real dice", style = MaterialTheme.typography.titleMedium) },
            text = { Text(why.joinToString("\n\n") + "\n\nRoll a real die: made-up numbers are far easier to guess than they look.",
                style = MaterialTheme.typography.bodySmall) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { warned = null; rolls = "" }) { Text("ROLL AGAIN", color = Purple) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { warned = null; model.diceWords(rolls) }) { Text("USE THEM ANYWAY", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }
}

/** The password of a backup file picked to restore from (Android `RestoreFromFileButton`). */
@Composable
fun RestoreFileScreen(model: WalletModel, bytes: ByteArray) {
    var password by remember { mutableStateOf("") }
    ScreenColumn {
        BackLink { model.back() }
        Text("Restore from a backup file", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("Enter the password the file was saved with. Its recovery words become this phone's spending wallet " +
            "and its settings come back; servers are never taken from a file. A file from Kilowallet for Android works too.", color = TextSoft)
        Field(password, { password = it }, "the file's password", password = true)
        PrimaryButton("Restore", Modifier.fillMaxWidth(), enabled = password.isNotEmpty()) { model.restoreBackup(bytes, password) }
    }
}

/** Write a backup file (.kab): words, passphrase and settings, sealed with a password (Android `FullBackupPanel`). */
@Composable
fun BackupScreen(model: WalletModel) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    ScreenColumn {
        BackLink { model.go(com.kilombino.kilowallet.app.Screen.Settings) }
        Text("Backup file", style = MaterialTheme.typography.titleLarge, color = TextMain)
        Explain("One encrypted file with your recovery words, their passphrase if you set one, the app's settings, " +
            "your Ark wallet and Lightning's channel backup. " +
            "It restores on another iPhone or on Kilowallet for Android. Keep the file and its password apart: " +
            "together they open the wallet. Your words on paper still work without it.", color = TextSoft)
        Field(p1, { p1 = it }, "password (${com.kilombino.kilowallet.core.backup.Kab.MIN_PASSWORD}+ characters)", password = true)
        Field(p2, { p2 = it }, "the same password again", password = true)
        if (p2.isNotEmpty() && p1 != p2) Text("The two passwords differ.", style = MaterialTheme.typography.bodySmall, color = Bad)
        PrimaryButton("Save the backup file", Modifier.fillMaxWidth(),
            enabled = p1.length >= com.kilombino.kilowallet.core.backup.Kab.MIN_PASSWORD && p1 == p2) { model.exportBackup(p1) }
    }
}
