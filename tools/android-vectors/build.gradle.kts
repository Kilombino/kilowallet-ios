// Generates the signing/derivation vectors in core/src/commonTest from the ANDROID app's own
// Kotlin code (Kilombino/kilowallet), compiled here unchanged on the JVM. Run:
//   ./gradlew -p tools/android-vectors run -PandroidRepo=/path/to/kilowallet
// and commit the regenerated core/src/commonTest/kotlin/.../AndroidVectors.kt.
plugins {
    kotlin("jvm") version "2.3.10"
    application
}

val androidRepo = (findProperty("androidRepo") as String?) ?: "../../../kilowallet"
val pkg = "$androidRepo/app/src/main/java/com/kilombino/pyblockwatch/crypto"
val files = listOf(
    "Hashes", "Ripemd160", "Secp256k1", "NativeSecp", "Ecdsa", "Bip32", "Bip32Priv", "Bip39",
    "Bip39Wordlist", "Base58", "Bech32", "Address", "TxBuilder", "Schnorr", "TxParse", "Blake2b", "BlockHeader",
    "XChaCha20Poly1305", "DiceCheck", "Psbt", "Ur", "Aezeed", "Aez", "Scrypt",
)
// The pool (Kilojoin) code: the protocol, the transaction, blind signatures, nostr, and the session
// itself (the interop tool runs it against this port's).
val coinjoin = listOf("Protocol", "CoinjoinTx", "BlindRsa", "NostrEvent", "Nip44", "CoinjoinPayment", "PoolSession", "RelayClient")

val copyAndroid = tasks.register<Sync>("copyAndroidSources") {
    from(pkg) { include(files.map { "$it.kt" }); into("crypto") }
    from("$androidRepo/app/src/main/java/com/kilombino/pyblockwatch/coinjoin") { include(coinjoin.map { "$it.kt" }); into("coinjoin") }
    // The backup file (.kab) writer/reader, for the on-chain vectors (android.util.Base64 is shimmed).
    from("$androidRepo/app/src/main/java/com/kilombino/pyblockwatch/ark") { include("ArkBackup.kt", "ArkDerive.kt"); into("ark") }
    // LND's channel.backup reader, and the backup LND's own code made that the Android tests pin.
    from("$androidRepo/app/src/main/java/com/kilombino/pyblockwatch/data") { include("Scb.kt"); into("data") }
    from("$androidRepo/app/src/test/resources") { include("scb_vector*"); into("res") }
    into(layout.buildDirectory.dir("androidSrc"))
}
sourceSets["main"].kotlin.srcDir(copyAndroid)

// Coinbase maturity: the rule lives in the Android Scanner's companion, next to code that needs
// the network. Its block (HISTORICAL_MATURITY … immature) is taken out of that file verbatim.
val maturitySrc = tasks.register("extractMaturity") {
    val src = file("$androidRepo/app/src/main/java/com/kilombino/pyblockwatch/data/Scanner.kt")
    val out = layout.buildDirectory.file("maturitySrc/AndroidMaturity.kt")
    inputs.file(src); outputs.file(out)
    doLast {
        val lines = src.readLines()
        val from = lines.indexOfFirst { it.contains("const val HISTORICAL_MATURITY") }
        val to = (from until lines.size).firstOrNull { lines[it].contains("fun immature(coinbase") } ?: -1
        require(from in 0 until to) { "maturity block not found in ${src.path}" }
        val body = lines.subList(from, to + 1).filterNot { it.contains("fun isCoinbase(") }
        out.get().asFile.writeText(
            "package com.kilombino.pyblockwatch.data\n\n" +
                "// Taken verbatim from the Android app's data/Scanner.kt companion.\n" +
                "object AndroidMaturity {\n" + body.joinToString("\n") + "\n}\n"
        )
    }
}
sourceSets["main"].kotlin.srcDir(maturitySrc.map { layout.buildDirectory.dir("maturitySrc") })

// Lightning: the protobuf encoder and transaction sizes (ln/Proto.kt, ln/TxSize.kt, verbatim) and the
// node key derivation (the two pure functions of ln/LnSeed.kt, taken out of it verbatim).
val lnSrc = tasks.register("extractLn") {
    val dir = file("$androidRepo/app/src/main/java/com/kilombino/pyblockwatch/ln")
    val out = layout.buildDirectory.dir("lnSrc")
    inputs.dir(dir); outputs.dir(out)
    doLast {
        val o = out.get().asFile.resolve("ln"); o.mkdirs()
        listOf("Proto", "TxSize").forEach { dir.resolve("$it.kt").copyTo(o.resolve("$it.kt"), overwrite = true) }
        val lines = dir.resolve("LnSeed.kt").readLines()
        val from = lines.indexOfFirst { it.contains("private const val TAG") }
        val to = lines.indexOfFirst { it.contains("fun expectedPubkey(") }
        require(from in 0 until to) { "LnSeed functions not found" }
        val body = lines.subList(from, to).dropLastWhile { it.isBlank() || it.trim().startsWith("/**") || it.trim().startsWith("*") }
        o.resolve("AndroidLnSeed.kt").writeText(
            "package com.kilombino.pyblockwatch.ln\n\nimport com.kilombino.pyblockwatch.crypto.Base58\n" +
                "import com.kilombino.pyblockwatch.crypto.Hashes\nimport com.kilombino.pyblockwatch.crypto.Secp256k1\n" +
                "import java.math.BigInteger\n\n// Taken verbatim from the Android app's ln/LnSeed.kt.\nobject AndroidLnSeed {\n" +
                body.joinToString("\n") + "\n}\n"
        )
    }
}
sourceSets["main"].kotlin.srcDir(lnSrc.map { layout.buildDirectory.dir("lnSrc") })

kotlin { jvmToolchain(25) }
repositories { mavenCentral() }
dependencies {
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // This port's shared code (./gradlew :core:jvmJar first), for the interop rounds.
    implementation(files("../../core/build/libs/core-jvm.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
    implementation("org.kotlincrypto.hash:sha2:0.8.0")
    implementation("org.kotlincrypto.macs:hmac-sha2:0.8.0")
}
application { mainClass.set("GenerateKt") }
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    args = listOf(
        file("../../core/src/commonTest/kotlin/com/kilombino/kilowallet/core/AndroidVectors.kt").absolutePath,
        providers.exec { commandLine("git", "-C", androidRepo, "rev-parse", "HEAD") }.standardOutput.asText.get().trim(),
    )
}

// Interop rounds against a BLAKE2b regtest node (KW_RPC=user:pass@host:port) and relay.kilombino.com.
tasks.register<JavaExec>("interop") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("InteropKt")
    workingDir = rootDir
}

// Coinbase maturity vectors from the Android rule (core/src/commonTest/.../MaturityVectors.kt).
tasks.register<JavaExec>("maturity") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("MaturityKt")
    workingDir = rootDir
    args = listOf(
        file("../../core/src/commonTest/kotlin/com/kilombino/kilowallet/core/MaturityVectors.kt").absolutePath,
        providers.exec { commandLine("git", "-C", androidRepo, "rev-parse", "HEAD") }.standardOutput.asText.get().trim(),
    )
}

// On-chain vectors (Taproot, dice, backup file) from the Android code (core/src/commonTest/.../OnchainVectors.kt).
tasks.register<JavaExec>("onchain") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("OnchainKt")
    workingDir = rootDir
    args = listOf(
        file("../../core/src/commonTest/kotlin/com/kilombino/kilowallet/core/OnchainVectors.kt").absolutePath,
        providers.exec { commandLine("git", "-C", androidRepo, "rev-parse", "HEAD") }.standardOutput.asText.get().trim(),
    )
}

// Lightning vectors from the Android code (core/src/commonTest/.../LnVectors.kt).
tasks.register<JavaExec>("ln") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("LnKt")
    workingDir = rootDir
    args = listOf(
        file("../../core/src/commonTest/kotlin/com/kilombino/kilowallet/core/LnVectors.kt").absolutePath,
        providers.exec { commandLine("git", "-C", androidRepo, "rev-parse", "HEAD") }.standardOutput.asText.get().trim(),
    )
}

// Ark: the Ark wallet's own words made from the BTC wallet's (ark/ArkDerive.kt, verbatim)
// (core/src/commonTest/.../ArkVectors.kt).
tasks.register<JavaExec>("ark") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ArkKt")
    workingDir = rootDir
    args = listOf(
        file("../../core/src/commonTest/kotlin/com/kilombino/kilowallet/core/ArkVectors.kt").absolutePath,
        providers.exec { commandLine("git", "-C", androidRepo, "rev-parse", "HEAD") }.standardOutput.asText.get().trim(),
    )
}
