package com.kilombino.kilowallet.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kilombino.kilowallet.app.AppPlatform
import com.kilombino.kilowallet.app.WalletModel
import com.kilombino.kilowallet.app.i18n.Text
import com.kilombino.kilowallet.core.chain.NodeRpcBackend
import com.kilombino.kilowallet.core.chain.RpcConn
import kotlinx.coroutines.launch

/**
 * Your own Bitcoin node (Knots with BLAKE2b) by RPC, instead of an Electrum server (Android 1.6.0's
 * RpcNodePanel): one or more ways to reach it (at home, a .onion from outside), tried in order,
 * typed, pasted or read from the node's QR code. The node gets a watch-only wallet for this account
 * (public key only) and answers everything; the keys stay on the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RpcNodePanel(model: WalletModel, platform: AppPlatform) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val conns = remember { mutableStateListOf<RpcConn>().apply { addAll(model.rpcConns()) } }
    var on by remember { mutableStateOf(model.useRpc()) }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    // An https node's certificate, shown for the user to compare before any password goes out.
    var toTrust by remember { mutableStateOf<Pair<RpcConn, String>?>(null) }
    var addAfterTrust by remember { mutableStateOf(false) }
    fun fill(text: String) {
        runCatching { RpcConn.parse(text) }.onSuccess { c ->
            url = c.url; if (c.user.isNotEmpty()) user = c.user; if (c.pass.isNotEmpty()) pass = c.pass; msg = null
        }.onFailure { msg = "Not a node address: ${it.message}" }
    }
    fun save() { model.setRpcConns(conns.toList()) }
    fun typed(): RpcConn? = runCatching { RpcConn.parse(url).copy(user = user, pass = pass) }.getOrElse { msg = it.message; null }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Or your own node by RPC", style = MaterialTheme.typography.bodyMedium, color = TextMain)
        Explain("Connect straight to your Bitcoin node (Knots with BLAKE2b), no Electrum server needed. It gets a " +
            "watch-only wallet for this account (public key only); your keys never leave the phone. Add more than one " +
            "way to reach it (at home, a .onion from outside): they are tried in order. The node's wallet feature must be on. " +
            "On StartOS, copy the address from the node's RPC interface (https://… at home, http://….onion from outside) and " +
            "make a user with its Generate RPC User Credentials action.")
        conns.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${c.url}" + if (c.user.isNotEmpty()) " (${c.user})" else "", style = MaterialTheme.typography.bodySmall,
                    color = TextSoft, modifier = Modifier.weight(1f))
                if (i > 0) TextButton(onClick = { conns.add(i - 1, conns.removeAt(i)); save() }) { Text("▲", color = Purple) }
                TextButton(onClick = { conns.removeAt(i); save(); if (conns.isEmpty()) on = false }) { Text("✕", color = Bad) }
            }
        }
        if (conns.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Read BTC from my node (RPC)", style = MaterialTheme.typography.bodyMedium, color = TextMain)
                Explain(if (on) "On: your node answers everything. The first time it looks for this wallet's coins, which can take a few minutes."
                    else "Off: the Electrum server above is used.")
            }
            Switch(checked = on, onCheckedChange = { on = it; model.setUseRpc(it) })
        }
        Field(url, { url = it.trim(); msg = null }, "the RPC address, port included")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(user, { user = it.trim() }, "RPC user", modifier = Modifier.weight(1f))
            Field(pass, { pass = it }, "password", modifier = Modifier.weight(1f), password = true)
        }
        FlowRow {
            if (platform.canScanQr) TextButton(onClick = { platform.scanQr { t -> if (t != null) fill(t) } }) {
                Text("📷 SCAN QR", color = Purple, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { clipboard.getText()?.text?.let { fill(it) } }) {
                Text("PASTE", color = Purple, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                val c = typed() ?: return@TextButton
                if (c.isTls) {
                    // First the certificate alone, for the user to check; the login only after.
                    msg = "reading the certificate…"; addAfterTrust = false
                    scope.launch { model.probeRpcCertificate(c).onSuccess { msg = null; toTrust = c to it }.onFailure { msg = "✗ " + (it.message ?: "no answer") } }
                    return@TextButton
                }
                msg = "testing…"
                scope.launch { msg = model.testRpc(c) }
            }) { Text("TEST", color = Purple, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = {
                val c = typed() ?: return@TextButton
                if (!c.isTls) { conns.add(c); url = ""; user = ""; pass = ""; msg = "added"; save(); return@TextButton }
                // https (a StartOS node): its certificate is trusted only once the user says it matches.
                msg = "reading the certificate…"; addAfterTrust = true
                scope.launch { model.probeRpcCertificate(c).onSuccess { msg = null; toTrust = c to it }.onFailure { msg = "✗ " + (it.message ?: "no answer") } }
            }) { Text("ADD", color = Good, style = MaterialTheme.typography.bodySmall) }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✗")) Bad else TextFaint) }
    }
    toTrust?.let { (c, fp) ->
        AlertDialog(onDismissRequest = { toTrust = null },
            title = { Text("Is this your node's certificate?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${c.url} shows this certificate (SHA-256):", style = MaterialTheme.typography.bodySmall)
                    Text(NodeRpcBackend.spaced(fp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = TextMain)
                    Text("Compare it with the one your node shows (on StartOS, the RPC interface's certificate). Only if it is the same, " +
                        "trust it: the password is then sent to this certificate only. Someone on your network could show another one.",
                        style = MaterialTheme.typography.bodySmall, color = Warn)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    toTrust = null
                    val pinned = c.copy(pin = fp)
                    msg = "testing…"
                    scope.launch {
                        val t = model.testRpc(pinned)
                        if (t.startsWith("✗") || !addAfterTrust) { msg = t; return@launch }
                        conns.add(pinned); url = ""; user = ""; pass = ""
                        msg = "added · $t"; save()
                    }
                }) { Text("IT MATCHES: TRUST IT", color = Good) }
            },
            dismissButton = { TextButton(onClick = { toTrust = null; msg = "not trusted: nothing was sent" }) { Text("CANCEL") } })
    }
}

