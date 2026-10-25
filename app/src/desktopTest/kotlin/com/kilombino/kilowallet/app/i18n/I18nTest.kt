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

    /**
     * App Store naming (mandatory): the SHA-256 chain is Corecoin and the pools are Kilojoin: never
     * "coinjoin", "mix", "mixing", "mixer", "spamchain", "spamcoin" or "Poolcoin", in any language
     * (their translations too: mezclar, barrejar, mélanger, mescolare, mischen, misturar, 混合/混币).
     * The same list as scripts/sync-i18n.py.
     */
    private val banned = Regex("spam|coin ?join|poolcoin|poolsat|mix|mezcl|barrej|barreg|m[ée]lang|mescol|misch|mistur|混合|混币|混幣|垃圾",
        RegexOption.IGNORE_CASE)

    @Test fun noAndroidNicknamesInTheTables() {
        for (l in codes) {
            val o = Json.parseToJsonElement(File(dir, "$l.json").readText()).jsonObject
            o.forEach { (k, v) -> assertTrue(!banned.containsMatchIn(k) && !banned.containsMatchIn(v.jsonPrimitive.content), "$l: $k") }
        }
    }

    /** The words above in every iOS string table: the language tables, the iOS-only texts, InfoPlist.strings and Info.plist. */
    @Test fun noForbiddenWordInAnyIosStringTable() {
        val tables = (dir.listFiles().orEmpty().toList() + File("../scripts/i18n-ios").listFiles().orEmpty().toList())
            .filter { it.extension == "json" }
        assertTrue(tables.size >= 14, "the tables were not found")
        for (f in tables) {
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            o.forEach { (k, v) -> assertTrue(!banned.containsMatchIn(k) && !banned.containsMatchIn(v.jsonPrimitive.content), "${f.name}: $k") }
        }
        val plists = File("../iosApp/iosApp").walkTopDown().filter { it.name == "InfoPlist.strings" || it.name == "Info.plist" || it.name == "Localizable.strings" }.toList()
        assertTrue(plists.size >= 8, "the InfoPlist.strings were not found")
        for (f in plists) f.readLines().forEachIndexed { i, line -> assertTrue(!banned.containsMatchIn(line), "${f.path}:${i + 1}: $line") }
        // The test itself must catch them.
        for (w in listOf("CoinJoin", "coin join", "mixer", "Mixing", "spamchain", "Poolcoins", "mezclar", "mélangé", "gemischt", "混币"))
            assertTrue(banned.containsMatchIn(w), w)
    }

    /**
     * Every string literal the iOS screens show, with sample values in its templates, in every
     * language: none may come out with an Android nickname, and in Spanish every one must be
     * translated (the others are printed; run with -i to see them). [neutral] are the strings
     * that are the same in every language, or are not shown at all.
     */
    @Test fun iosStringsInEveryLanguage() {
        val sources = File("src/commonMain/kotlin/com/kilombino/kilowallet/app").walkTopDown().filter { it.extension == "kt" && "i18n" !in it.path } +
            File("../core/src/commonMain/kotlin/com/kilombino/kilowallet/core/wallet/Send.kt")
        // What a round can say on screen (why it stopped, why it does not sign), from the shared code:
        // its sentences (three words or more), not its log lines.
        val reasons = File("../core/src/commonMain/kotlin/com/kilombino/kilowallet/core/kilojoin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().filter { l -> "log(" !in l && "Regex(" !in l && !l.trim().startsWith("*") && !l.trim().startsWith("/") } }
            .flatMap { line -> Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(line).map { it.groupValues[1] } }
            .filter { it.count { c -> c == ' ' } >= 2 && !it.startsWith("kilojoin/") && "|" !in it && "->" !in it }
        val strings = (sources.flatMap { iosStrings(it.readText()) } + reasons.flatMap { iosStrings("\"$it\"") })
            .filter { s -> neutral.none { it.matches(s) } }.toSortedSet()
        val left = HashMap<String, List<String>>()
        for (l in codes) {
            I18n.lang.value = l
            left[l] = strings.filter { s ->
                val out = I18n.tr(s)
                assertTrue(!banned.containsMatchIn(out) || banned.containsMatchIn(s), "$l: \"$s\" -> \"$out\"")
                out == s
            }
        }
        I18n.lang.value = I18n.ES
        println("iOS strings: ${strings.size}; left in English: " + codes.joinToString { "$it ${left[it]!!.size}" })
        left.forEach { (l, ss) -> ss.forEach { println("  $l: $it") } }
        assertEquals(emptyList(), left[I18n.ES], "iOS strings with no Spanish translation (add them to scripts/i18n-ios/es.json)")
    }

    /** iOS strings built from values, read as they are on screen. */
    @Test fun iosTemplates() {
        I18n.lang.value = I18n.ES
        assertEquals("ENVIAR CON FACE ID", I18n.tr("SEND WITH FACE ID"))
        assertEquals("ENVIAR CON TU CÓDIGO", I18n.tr("SEND WITH YOUR PASSCODE"))
        assertEquals("direcciones de recepción · 3 usadas hasta ahora", I18n.tr("receive addresses · 3 used so far"))
        assertEquals("sin usar · m/84'/0'/0'/0/7 · 2 de 20", I18n.tr("unused · m/84'/0'/0'/0/7 · 2 de 20".replace(" de ", " of ")))
        assertEquals("fulcrum.kilombino.com:17717 (predeterminado)", I18n.tr("fulcrum.kilombino.com:17717 (default)"))
        assertEquals("\nNueva huella SHA-256:\nAB12 CD34", I18n.tr("\nNew SHA-256 fingerprint:\nAB12 CD34"))
        assertEquals("Palabra n.º 3", I18n.tr("Word #3"))
        assertEquals("12 palabras · válidas", I18n.tr("12 words · valid"))
        assertEquals("MONEDERO NUEVO · 12 PALABRAS", I18n.tr("NEW WALLET · 12 WORDS"))
        I18n.lang.value = I18n.ZH
        assertEquals("未使用 · m/84'/0'/0'/0/7 · 第 2 个，共 20 个", I18n.tr("unused · m/84'/0'/0'/0/7 · 2 of 20"))
        assertEquals("用 TOUCH ID 发送", I18n.tr("SEND WITH TOUCH ID"))
        I18n.lang.value = I18n.ES
    }

    /** Not shown, or the same in every language: demo and preference keys, units, numbers, names. */
    private val neutral = listOf(
        "", "a", "4f", "abandon", "about", "abandon … about", "pulse", "this is live", "receive@es", "bitcoin:", "sp1", "\\\\s+",
        "Kilowallet", "Face ID", "Touch ID", "sats", " sats", "zpub / ypub / xpub", "QR code of the address",
        "github.com/Kilombino/kilowallet-ios", "kilombino.com", "welcome", "create", "restore", "watch", "home", "review",
        "word word …\npassphrase",
        // Currency codes, a preference key and the RBF screenshot's name.
        "USD", "EUR", "fiat", "replace",
        // "Legacy" is the same word in every table but Chinese.
        "Legacy · 1… (BIP44)",
        // The same word in Spanish (and others): "host", "total", "conf".
        "host", "total", "? sats", "12 345 conf",
        // Kilojoin: protocol words, preference keys, screenshot names and made-up demo values, and errors
        // of the arithmetic that no screen shows.
        "open", "equal", "x", "y", "demo1212", "5e", "6a", "6d", "7b", "demo", "demo-o1", "demo-o2", "demo-other", "demo-third", "demo-third-change",
        "pools", "pool-create", "pool-intro", "pool-join", "pool-pay", "pool-round", "(?i)\\\\bmix(ed|ing)?\\\\b",
        "division by zero", "integer too large", "invalid message length", "unexpected input size", "not a boolean",
        "no WebSocket on this platform", "bad blind-signing key", "unsupported blind-signing key", "12 345 min", "12 345 h 12 345 min",
        "12 345:12 345 UTC", "Kilojoin", "KILOJOIN", "12 345 × 12 345 sats", "12 × 12 345 sats", "12 345 sats → 12 345",
        // Pieces of strings the code joins at run time; [iosTemplates] checks the whole ones.
        " · 12 345 of 12 345", "12 345 addresses · 12 345 used so far",
    ).map { Regex(Regex.escape(it)) } + listOf(
        Regex("""[?=+]? ?12( 345)?( sats| sat/vB| BTC( \(XBT\))?)?( · 12 345 sat/vB)?"""), // amounts and rates
        Regex("""m/.*"""), Regex("""pin\..*"""), Regex("""mempool\.kilombino\.com/tx/.*"""), Regex("""bc1q[a-z0-9]{30,}"""),
        Regex(""".*kilombino\.com:17717"""), Regex("""kilojoin\..*"""),
    )

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
