package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.Screen
import com.kilombino.kilowallet.app.WalletModel

@Composable
fun App(platform: AppPlatform, model: WalletModel = remember { WalletModel(platform) }) {
    LaunchedEffect(Unit) { platform.demoScreen?.let { model.setUpDemo(it) } }
    val state by model.state.collectAsState()

    KilowalletTheme {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                key(state.walletId) {
                    when (val s = state.screen) {
                        Screen.Welcome -> WelcomeScreen(model)
                        is Screen.CreateShow -> CreateShowScreen(model, s.words)
                        is Screen.CreateConfirm -> CreateConfirmScreen(model, s.words, s.positions)
                        Screen.Restore -> RestoreScreen(model)
                        Screen.Watch -> WatchScreen(model, platform)
                        Screen.Wallets -> WalletsScreen(model, state)
                        Screen.Home -> HomeScreen(model, state, platform)
                        Screen.Receive -> ReceiveScreen(model, state, platform)
                        Screen.BackupCheck -> BackupCheckScreen(model, state)
                        Screen.Send -> SendScreen(model, state, platform)
                        Screen.Settings -> SettingsScreen(model, state, platform)
                        is Screen.ShowWords -> ShowWordsScreen(model, s.words)
                    }
                }
            }
            state.message?.let { msg ->
                AlertDialog(
                    onDismissRequest = model::dismissMessage,
                    text = { Text(msg, style = MaterialTheme.typography.bodyMedium) },
                    confirmButton = { TextButton(onClick = model::dismissMessage) { Text("OK", color = Purple) } },
                    containerColor = PanelBg, textContentColor = TextMain,
                )
            }
            state.certChanged?.let { fp ->
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(if (state.pinned == null) "Trust this server certificate?" else "Server certificate changed", style = MaterialTheme.typography.titleMedium) },
                    text = {
                        Column {
                            Text("${state.endpoint}: verify this fingerprint with the server operator using a separate trusted channel. " +
                                "No wallet queries have been sent. A wrong fingerprint could expose your addresses and allow false balance information.",
                                style = MaterialTheme.typography.bodySmall, color = TextSoft)
                            Text("\nNew SHA-256 fingerprint:\n${chunked(fp)}", style = MaterialTheme.typography.bodySmall, color = Warn)
                            state.pinned?.let { Text("\nTrusted until now:\n${chunked(it)}", style = MaterialTheme.typography.bodySmall, color = TextFaint) }
                        }
                    },
                    confirmButton = { TextButton(onClick = model::trustNewCertificate) { Text("TRUST NEW", color = Warn) } },
                    dismissButton = { TextButton(onClick = { model.go(Screen.Settings) ; model.dismissCertificate() }) { Text("NOT NOW", color = TextSoft) } },
                    containerColor = PanelBg, titleContentColor = TextMain,
                )
            }
        }
    }
}

/** Space between screen sections. */
internal val Gap = 14.dp
