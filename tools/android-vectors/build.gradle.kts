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
)

val copyAndroid = tasks.register<Sync>("copyAndroidSources") {
    from(pkg) { include(files.map { "$it.kt" }) }
    into(layout.buildDirectory.dir("androidSrc"))
}
sourceSets["main"].kotlin.srcDir(copyAndroid)

kotlin { jvmToolchain(25) }
application { mainClass.set("GenerateKt") }
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    args = listOf(
        file("../../core/src/commonTest/kotlin/com/kilombino/kilowallet/core/AndroidVectors.kt").absolutePath,
        providers.exec { commandLine("git", "-C", androidRepo, "rev-parse", "HEAD") }.standardOutput.asText.get().trim(),
    )
}
