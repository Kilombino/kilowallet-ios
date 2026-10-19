package com.kilombino.kilowallet.app.i18n

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The language tables and the translator, as the app ships them (Android app: ui/I18nTest.kt). */
class I18nTest {
    private val dir = File("src/commonMain/composeResources/files/i18n")
    private val codes = I18n.LANGUAGES.map { it.first }.filter { it != I18n.EN }

    @BeforeTest fun tables() {
        I18n.loader = { l -> File(dir, "$l.json").takeIf { it.exists() }?.readText() }
        runBlocking { codes.forEach { I18n.ensure(it) } }
    }

    @Test fun eachLanguageAndEnglishAsWritten() {
        I18n.lang.value = I18n.ES
        assertEquals("Recibir", I18n.tr("Receive"))
        assertEquals("Monedero caliente", I18n.tr("Hot wallet"))
        I18n.lang.value = I18n.EN
        assertEquals("Receive", I18n.tr("Receive"))
        I18n.lang.value = I18n.ZH
        assertEquals("接收", I18n.tr("Receive"))
        I18n.lang.value = I18n.ES
    }

    @Test fun templatesUpperCaseAndListItems() {
        I18n.lang.value = I18n.ES
        assertEquals("\"pato\" no es una palabra de recuperación.", I18n.tr("\"pato\" is not a seed word."))
        assertEquals("CONTROL DE MONEDAS", I18n.tr("COIN CONTROL"))
        assertEquals("•  Recibir", I18n.tr("•  Receive"))
        assertEquals("Recibir · Monedero caliente", I18n.tr("Receive · Hot wallet"))
        // Pieces with their own spaces around the dot keep them.
        assertEquals("m/84'/0'/0'/0/1  ·  recibir", I18n.tr("m/84'/0'/0'/0/1  ·  receive"))
        // Not in the table: as is.
        assertEquals("xyzzy 42", I18n.tr("xyzzy 42"))
    }

    @Test fun phoneLanguageOrEnglish() {
        val prefs = object : com.kilombino.kilowallet.app.Prefs {
            val m = HashMap<String, String>()
            override fun get(key: String) = m[key]
            override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        }
        I18n.init(prefs, listOf("eu-ES", "zh-Hans-CN", "en"))
        assertEquals(I18n.ZH, I18n.lang.value)
        I18n.init(prefs, listOf("ja-JP"))
        assertEquals(I18n.EN, I18n.lang.value)
        I18n.set("ca")
        I18n.init(prefs, listOf("ja-JP"))
        assertEquals("ca", I18n.lang.value)
        I18n.set(I18n.AUTO)
        assertEquals(I18n.EN, I18n.lang.value)
        I18n.lang.value = I18n.ES
    }

    @Test fun everyTableKeepsThePlaceholdersAndTheEdges() {
        for (l in codes) {
            val o = Json.parseToJsonElement(File(dir, "$l.json").readText()).jsonObject
            for ((k, e) in o) {
                val v = e.jsonPrimitive.content
                val ph = { s: String -> Regex("""\{\d+\}""").findAll(s).map { it.value }.sorted().toList() }
                assertEquals(ph(k), ph(v), "$l: $k")
                assertTrue(k.takeWhile { it.isWhitespace() } == v.takeWhile { it.isWhitespace() } &&
                    k.takeLastWhile { it.isWhitespace() } == v.takeLastWhile { it.isWhitespace() }, "$l edges: $k")
            }
        }
    }

    /** App Store naming: the SHA-256 chain is Corecoin, the coinjoin Kilojoin; never the Android app's nicknames. */
    private val banned = Regex("spam|coinjoin|poolcoin|poolsat|垃圾", RegexOption.IGNORE_CASE)

    @Test fun noAndroidNicknamesInTheTables() {
        for (l in codes) {
            val o = Json.parseToJsonElement(File(dir, "$l.json").readText()).jsonObject
            o.forEach { (k, v) -> assertTrue(!banned.containsMatchIn(k) && !banned.containsMatchIn(v.jsonPrimitive.content), "$l: $k") }
        }
    }

    /**
     * Every string literal the iOS screens show, with sample values in its templates, in every
     * language: none may come out with an Android nickname. Prints the ones Spanish leaves in
     * English (run with -i to see them).
     */
    @Test fun iosStringsInEveryLanguage() {
        val sources = File("src/commonMain/kotlin/com/kilombino/kilowallet/app").walkTopDown().filter { it.extension == "kt" && "i18n" !in it.path } +
            File("../core/src/commonMain/kotlin/com/kilombino/kilowallet/core/wallet/Send.kt")
        val strings = sources.flatMap { iosStrings(it.readText()) }.toSortedSet()
        val untranslated = mutableListOf<String>()
        for (l in codes) {
            I18n.lang.value = l
            for (s in strings) {
                val out = I18n.tr(s)
                assertTrue(!banned.containsMatchIn(out) || banned.containsMatchIn(s), "$l: \"$s\" -> \"$out\"")
                if (l == I18n.ES && out == s) untranslated += s
            }
        }
        I18n.lang.value = I18n.ES
        println("iOS strings: ${strings.size}, left in English in Spanish: ${untranslated.size}")
        untranslated.forEach { println("  EN: $it") }
    }

    private fun iosStrings(src: String): List<String> {
        // "a " +\n "b" written over several lines is one string at run time.
        var t = src.replace(Regex("\"\\s*\\+\\s*\\n\\s*\""), "")
        // ${…} with sample values, so templates match as they would on screen.
        val expr = Regex("\\$\\{((?:[^{}\"]|\"[^\"]*\")*)\\}")
        t = expr.replace(t) { m ->
            val e = m.groupValues[1]
            when {
                "Chain.COIN" in e -> "BTC"; "Chain.TICKER" in e -> "XBT"; "Chain.DISPLAY" in e -> "BLAKE2b"
                "EXPLORER" in e -> "mempool.kilombino.com"; "unlockName" in e -> "Face ID"
                "endpoint" in e -> "fulcrum.kilombino.com:17717"
                else -> "12 345"
            }
        }
        t = Regex("\\$[a-zA-Z_][a-zA-Z0-9_]*").replace(t, "12")
        // Buttons and section labels upper-case their text before it is shown.
        t = Regex("(PrimaryButton|SecondaryButton|SectionLabel)\\(\"([^\"\\n]*)\"").replace(t) { m ->
            m.groupValues[1] + "(\"" + m.groupValues[2].uppercase() + "\""
        }
        return Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").findAll(t).map { it.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n") }
            .filter { s -> s.any { it.isLetter() } && !Regex("^[a-z]+(\\.[a-z]+)+$").matches(s) && !s.startsWith("http") }
            .toList()
    }
}
