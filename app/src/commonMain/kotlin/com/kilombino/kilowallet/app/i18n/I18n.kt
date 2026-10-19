package com.kilombino.kilowallet.app.i18n

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.kilombino.kilowallet.app.Prefs
import com.kilombino.kilowallet.app.res.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The app's language, ported from the Android app's `ui/i18n/I18n.kt`. The code is written in
 * English; every text shown goes through [tr], which looks it up in the language's table
 * (composeResources/files/i18n/<lang>.json, the Android app's own tables, copied by
 * `scripts/sync-i18n.py`). Strings the code builds with values ("{0} sats each") match as
 * templates; ones it builds by joining pieces are translated piece by piece. Anything not found
 * stays in English. By default the phone's language (English when the app doesn't have it);
 * settings can fix one.
 *
 * Unlike Android, tables load with a suspend read (Compose resources): [ensure] builds a table
 * away from the screen and [loaded] redraws the texts when it is ready. Everything else runs on
 * the main thread, so the maps are plain ones.
 */
object I18n {
    const val AUTO = "auto"
    const val ES = "es"; const val EN = "en"; const val ZH = "zh"
    /** Each named in its own language. */
    val LANGUAGES = listOf(
        ES to "Español", EN to "English", ZH to "中文", "fr" to "Français", "it" to "Italiano",
        "de" to "Deutsch", "ca" to "Català", "pt" to "Português",
    )
    private const val KEY = "ui.lang"

    /** The language shown, read by every [Text]: changing it redraws the screens. */
    val lang = mutableStateOf(EN)
    /** What settings say: [AUTO] or a language's code. */
    val choice = mutableStateOf(AUTO)
    /** Bumped when a table is ready, so texts drawn before it was are drawn again. */
    val loaded = mutableIntStateOf(0)

    /** Where a language's table comes from: the app's resources, or (tests) any reader. */
    var loader: suspend (String) -> String? = { l ->
        runCatching { Res.readBytes("files/i18n/$l.json").decodeToString() }.getOrNull()
    }

    private var prefs: Prefs? = null
    private var preferred: List<String> = emptyList()

    /** The first of the phone's preferred languages the app has (as iOS picks an app's language), else English. */
    fun system(): String = preferred.map { it.lowercase().substringBefore('-').substringBefore('_') }
        .firstOrNull { l -> LANGUAGES.any { it.first == l } } ?: EN

    /** Once, at start: the language chosen before, or the phone's ([phoneLanguages], most preferred first). */
    fun init(p: Prefs, phoneLanguages: List<String>) {
        prefs = p
        preferred = phoneLanguages
        val saved = p.get(KEY)?.takeIf { l -> LANGUAGES.any { it.first == l } }
        choice.value = saved ?: AUTO
        lang.value = saved ?: system()
    }

    /** [l]: a language's code, or [AUTO] to follow the phone. */
    fun set(l: String) {
        choice.value = l
        lang.value = if (l == AUTO) system() else l
        prefs?.put(KEY, if (l == AUTO) null else l)
    }

    /** Read and build [l]'s table (~200 KB of JSON) off the main thread, once. */
    suspend fun ensure(l: String) {
        if (l == EN || tables.containsKey(l) || l in missing) return
        val text = loader(l)
        val t = text?.let { withContext(Dispatchers.Default) { build(it) } }
        if (t == null) { missing += l; return }
        tables[l] = t
        cache.clear()
        loaded.intValue++
    }

    /**
     * [head]: the text before the first placeholder. A string that doesn't start with it can't
     * match, so most templates are skipped without their regex, which is only compiled when a
     * string gets that far.
     */
    private class Template(val head: String, pattern: String, val out: String, val fragment: Boolean) {
        // (?s): "." also matches a line break (RegexOption.DOT_MATCHES_ALL is JVM-only).
        val regex by lazy { Regex("(?s)$pattern") }
        val length = pattern.length
    }
    private class Table(
        val exact: Map<String, String>, val upper: Map<String, String>, val templates: List<Template>,
        val prefixes: List<Pair<String, String>>, val suffixes: List<Pair<String, String>>,
    )

    private val tables = HashMap<String, Table>()
    private val missing = HashSet<String>()
    private val cache = HashMap<String, String>()
    private val bullet = Regex("""(?s)^([•·\-–]\s+)(.+)$""")
    private val placeholder = Regex("""\{\d+\}""")

    private fun build(json: String): Table? {
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val exact = HashMap<String, String>(); val upper = HashMap<String, String>()
        val templates = ArrayList<Template>(); val prefixes = ArrayList<Pair<String, String>>(); val suffixes = ArrayList<Pair<String, String>>()
        for ((en, v) in o) {
            val out = runCatching { v.jsonPrimitive.content }.getOrNull() ?: continue
            if (placeholder.containsMatchIn(en)) {
                val parts = en.split(placeholder)
                val pattern = parts.joinToString("(.+?)") { Regex.escape(it) }
                val frag = en.last().isWhitespace() || en.last() in "(≈:—-"
                templates += Template(parts.first(), "^$pattern" + if (frag) "" else "$", out, frag)
            } else {
                exact[en] = out
                // "BEFORE YOU TURN IT ON": the code upper-cases a mixed-case string.
                if (en.any { it.isLowerCase() } && en.uppercase() !in upper) upper[en.uppercase()] = out.uppercase()
                if (en.length >= 3 && (en.last().isWhitespace() || en.endsWith(": ") || en.endsWith("("))) prefixes += en to out
                if (en.length >= 3 && (en.first().isWhitespace() || en.first() in ".,)—")) suffixes += en to out
            }
        }
        prefixes.sortByDescending { it.first.length }; suffixes.sortByDescending { it.first.length }
        templates.sortByDescending { it.length } // the most specific first
        return Table(exact, upper, templates, prefixes, suffixes)
    }

    /** One piece of a joined string, its surrounding spaces kept ("m/84'/0'/0'/0/1  ·  receive"). */
    private fun piece(t: Table, p: String, depth: Int): String {
        val core = p.trim()
        if (core.isEmpty()) return p
        val lead = p.substring(0, p.indexOf(core)); val trail = p.substring(p.indexOf(core) + core.length)
        return lead + translate(t, core, depth + 1) + trail
    }

    /** [s] in the app's language (as is in English, or when there is no translation). */
    fun tr(s: String): String {
        val l = lang.value
        if (l == EN || s.isBlank()) return s
        val key = "$l:$s"
        cache[key]?.let { return it }
        val t = tables[l] ?: return s
        val out = translate(t, s, 0)
        if (cache.size > 20_000) cache.clear()
        cache[key] = out
        return out
    }

    private fun translate(t: Table, s: String, depth: Int): String {
        t.exact[s]?.let { return it }
        t.upper[s]?.let { return it }
        if (s.isEmpty()) return s
        // iOS only: the same words with the first letter's case the other way ("Settings" for
        // the Android table's "settings"), the translation's first letter cased like ours.
        val first = s[0]
        if (first.isLetter()) {
            val flipped = (if (first.isUpperCase()) first.lowercaseChar() else first.uppercaseChar()) + s.substring(1)
            t.exact[flipped]?.let { out ->
                return if (out.isEmpty()) out else (if (first.isUpperCase()) out[0].uppercaseChar() else out[0].lowercaseChar()) + out.substring(1)
            }
        }
        if (depth > 4) return s
        for (tp in t.templates) {
            if (!s.startsWith(tp.head)) continue
            val m = tp.regex.find(s) ?: continue
            var out = tp.out
            // The placeholders' values, translated themselves when they are words ("Taproot", "OPEN"…).
            m.groupValues.drop(1).forEachIndexed { i, v -> out = out.replace("{$i}", translate(t, v, depth + 1)) }
            return if (tp.fragment) out + translate(t, s.substring(m.range.last + 1), depth + 1) else out
        }
        // A list item ("•  …"), pieces joined with " · ", or paragraphs: each piece on its own.
        bullet.find(s)?.let { m -> return m.groupValues[1] + translate(t, m.groupValues[2], depth + 1) }
        if (" · " in s) return s.split(" · ").joinToString(" · ") { piece(t, it, depth) }
        if ("\n\n" in s) return s.split("\n\n").joinToString("\n\n") { piece(t, it, depth) }
        for ((en, out) in t.prefixes) if (s.startsWith(en)) return out + translate(t, s.substring(en.length), depth + 1)
        for ((en, out) in t.suffixes) if (s.endsWith(en)) return translate(t, s.substring(0, s.length - en.length), depth + 1) + out
        return s
    }
}
