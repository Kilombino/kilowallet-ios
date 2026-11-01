package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.ScanPhase
import com.kilombino.kilowallet.app.Screen
import com.kilombino.kilowallet.app.Tab
import com.kilombino.kilowallet.app.UiState
import com.kilombino.kilowallet.app.WalletKind
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.i18n.Text

/**
 * The bar over every tab, as Android 1.4.4's: the wallet switch (🔥 HOT | 👁 WATCH), "simple",
 * ⚙ settings, ⏻ and the logo. On iPhone ⏻ locks the wallet on screen: iOS apps do not quit
 * themselves.
 */
@Composable
fun WalletHeader(model: WalletModel, state: UiState, platform: AppPlatform) {
    var askLock by remember { mutableStateOf(false) }
    var askSwitch by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { WalletSwitch(state) { askSwitch = true } }
        LinkText(if (state.simple) "advanced" else "simple", color = TextSoft) { model.setSimple(!state.simple) }
        LinkText("⚙", color = TextSoft) { model.go(Screen.Settings) }
        Box(Modifier.size(30.dp).clip(CircleShape).clickable { askLock = true }, contentAlignment = Alignment.Center) {
            Text("⏻", style = MaterialTheme.typography.titleLarge, color = TextSoft)
        }
        Spacer(Modifier.width(4.dp))
        Logo(34)
    }
    if (askLock) AlertDialog(
        onDismissRequest = { askLock = false },
        title = { Text("Lock Kilowallet?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text("The wallet is hidden until you unlock it again with ${platform.unlockName}. A Kilojoin round still " +
                "going waits for you to open the app.", style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { TextButton(onClick = { askLock = false; model.lock() }) { Text("LOCK", color = Purple) } },
        dismissButton = { TextButton(onClick = { askLock = false }) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
    if (askSwitch) AlertDialog(
        onDismissRequest = { askSwitch = false },
        title = { Text(if (state.kind == WalletKind.HOT) "Watch another wallet" else "Back to the hot wallet", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text("On iPhone this version keeps one wallet at a time: a spending wallet or a watch-only one. " +
                "To change it, use SWITCH / NEW WALLET in settings.", style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { TextButton(onClick = { askSwitch = false; model.go(Screen.Settings) }) { Text("SETTINGS", color = Purple) } },
        dismissButton = { TextButton(onClick = { askSwitch = false }) { Text("CANCEL", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

/** 🔥 HOT | 👁 WATCH: the wallet on screen says its name, the other only its emoji (Android `WalletModeSwitch`). */
@Composable
private fun WalletSwitch(state: UiState, onOther: () -> Unit) {
    val hotOn = state.kind == WalletKind.HOT
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(PanelSoft).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        @Composable fun seg(label: String, on: Boolean) = Text(if (on) label else label.substringBefore(' '),
            style = MaterialTheme.typography.labelSmall, color = if (on) Ink else TextFaint,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Purple else Color.Transparent)
                .clickable { if (!on) onOther() }.padding(horizontal = 8.dp, vertical = 6.dp))
        seg("🔥 HOT", hotOn)
        seg("👁 WATCH", !hotOn)
    }
}

/** BTC | LIGHTNING | KILOJOIN | ARK, each its symbol over its name and its own colour (Android `AdvancedTabs`). */
@Composable
fun MainTabs(tab: Tab, hot: Boolean, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PanelSoft).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val tabs = buildList {
            add(Triple(Tab.BTC, "⛓" to "BTC", Orange))
            if (hot) {
                add(Triple(Tab.LIGHTNING, "⚡" to "LIGHTNING", LnGold))
                add(Triple(Tab.POOLS, "🌀" to "KILOJOIN", PoolBlue))
                add(Triple(Tab.ARK, "⛵" to "ARK", ArkRed))
            }
        }
        tabs.forEach { (t, label, color) ->
            val active = t == tab
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (active) color.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(t) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label.first, style = MaterialTheme.typography.titleMedium, color = if (active) color else TextFaint, maxLines = 1)
                Text(label.second, style = MaterialTheme.typography.labelSmall, color = if (active) color else TextFaint,
                    maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The ARK tab before Ark is set up, as Android's warnings; the engine comes in a later build. */
@Composable
fun ArkTab() {
    Panel(accent = ArkRed) {
        SectionLabel("Ark", ArkRed)
        Explain("Ark will let you send BTC instantly between wallets and pay Lightning invoices " +
            "without opening channels, through the Paperclip Ark server (ark.paperclippool.xyz).")
        Text("The Ark engine for iPhone comes with this version's next build: until then Ark cannot be turned on.",
            style = MaterialTheme.typography.bodySmall, color = Warn)
    }
    Panel(accent = Warn) {
        SectionLabel("Before you use Ark", Warn)
        listOf(
            "Ark funds expire after 4,320 blocks (about 30 days). The wallet must go online before then to renew them, or they have to be withdrawn on-chain with fees and waiting times.",
            "Minimum to move funds into Ark: 20,000 sats.",
            "Maximum per Ark coin: 1,000,000 sats.",
            "Lightning: up to 250,000 sats per payment, no channels needed.",
            "Rounds every 60 seconds; moving funds in needs 3 confirmations.",
            "Ark is beta software. Start with small amounts.",
        ).forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = TextSoft) }
    }
}

/** Simple mode: the balance and RECEIVE / SEND, nothing else ("advanced" in the bar brings the tabs back). */
@Composable
fun SimpleHome(model: WalletModel, state: UiState) {
    val hot = state.kind == WalletKind.HOT
    TabColumn {
        BalanceCard(model, state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("RECEIVE", Modifier.weight(1f)) { model.go(Screen.Receive) }
            if (hot) PrimaryButton("SEND", Modifier.weight(1f), enabled = state.scan is ScanPhase.Done && state.total > 0) { model.go(Screen.Send) }
        }
    }
}

/** Locked with ⏻: only the logo and the way back in. */
@Composable
fun LockedScreen(model: WalletModel, platform: AppPlatform) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(Gap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(120)
        Text("Kilowallet", style = MaterialTheme.typography.displayLarge, color = Purple)
        Text("Locked", style = MaterialTheme.typography.titleMedium, color = TextSoft)
        PrimaryButton("UNLOCK WITH ${platform.unlockName.uppercase()}", Modifier.fillMaxWidth()) { model.unlock() }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
