package com.kilombino.kilowallet.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.kilombino.kilowallet.app.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A bordered panel, the app's one container shape (Android: ui/Components.kt). */
@Composable
fun Panel(modifier: Modifier = Modifier, accent: Color = Purple, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelBg)
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, color: Color = Purple) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
fun Explain(text: String, modifier: Modifier = Modifier, color: Color = TextFaint) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Start, modifier = modifier.fillMaxWidth())
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Purple, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Ink,
            disabledContainerColor = color.copy(alpha = 0.25f), disabledContentColor = Ink.copy(alpha = 0.6f)),
    ) { Text(text.uppercase(), style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Purple, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = color,
            disabledContainerColor = PanelSoft, disabledContentColor = TextFaint),
    ) { Text(text.uppercase(), style = MaterialTheme.typography.labelLarge) }
}

/** A small selectable pill (Android: the settings screen's chips). */
@Composable
fun Chip(text: String, selected: Boolean, color: Color = Purple, onClick: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (selected) Ink else TextMain,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) color else PanelSoft)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
fun LinkText(text: String, color: Color = TextSoft, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp))
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, minLines: Int = 1,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = singleLine, minLines = minLines,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Purple, unfocusedBorderColor = TextFaint,
            focusedLabelColor = Purple, unfocusedLabelColor = TextSoft, cursorColor = Purple,
            focusedTextColor = TextMain, unfocusedTextColor = TextMain,
        ),
    )
}

/** A slowly breathing dot — "this is live". */
@Composable
fun PulseDot(color: Color, size: Int = 8) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = a)))
}

/** The gap-limit ring: fills as consecutive empty addresses accumulate; the scan stops when it closes. */
@Composable
fun GapRing(used: Int, limit: Int, color: Color, modifier: Modifier = Modifier) {
    val frac = (used.toFloat() / limit.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(46.dp).drawBehind {
            val stroke = 3.dp.toPx(); val inset = stroke / 2
            val sz = Size(size.width - stroke, size.height - stroke)
            drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(stroke))
            drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), sz, style = Stroke(stroke))
        })
        Text("$used", style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = TextMain) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = TextSoft, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, textAlign = TextAlign.End)
    }
}

fun shortAddress(a: String): String = if (a.length <= 20) a else "${a.take(10)}…${a.takeLast(8)}"
fun shortTxid(t: String): String = "${t.take(8)}…${t.takeLast(6)}"

/** 4 groups of 4 for reading an address or fingerprint aloud. */
fun chunked(s: String, n: Int = 4): String = s.chunked(n).joinToString(" ")
