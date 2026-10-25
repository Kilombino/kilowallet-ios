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
    "XChaCha20Poly1305",
)
// The pool (Kilojoin) code: the protocol, the transaction, blind signatures, nostr, and the session
// itself (the interop tool runs it against this port's).
val coinjoin = listOf("Protocol", "CoinjoinTx", "BlindRsa", "NostrEvent", "Nip44", "CoinjoinPayment", "PoolSession", "RelayClient")

val copyAndroid = tasks.register<Sync>("copyAndroidSources") {
    from(pkg) { include(files.map { "$it.kt" }); into("crypto") }
    from("$androidRepo/app/src/main/java/com/kilombino/pyblockwatch/coinjoin") { include(coinjoin.map { "$it.kt" }); into("coinjoin") }
    into(layout.buildDirectory.dir("androidSrc"))
}
sourceSets["main"].kotlin.srcDir(copyAndroid)

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
