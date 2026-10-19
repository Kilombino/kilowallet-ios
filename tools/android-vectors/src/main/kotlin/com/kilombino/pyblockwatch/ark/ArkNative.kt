package com.kilombino.pyblockwatch.ark

/** Stand-in for the Android app's JNI bridge: absent, so the app's pure-Kotlin path signs. */
object ArkNative {
    val available: Boolean = false
    fun secpPubkey(secret: ByteArray): ByteArray? = null
    fun secpEcdsaSign(secret: ByteArray, hash: ByteArray, lowR: Boolean): ByteArray? = null
    fun secpSchnorrSign(secret: ByteArray, msg: ByteArray, aux: ByteArray): ByteArray? = null
    fun secpEcdh(secret: ByteArray, point: ByteArray): ByteArray? = null
    fun secpTweakAdd(secret: ByteArray, tweak: ByteArray): ByteArray? = null
}
