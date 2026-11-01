package android.util

/** Stand-in for Android's Base64, so the app's ark/ArkBackup.kt compiles here unchanged. */
object Base64 {
    const val NO_WRAP = 2
    fun encodeToString(b: ByteArray?, flags: Int): String = java.util.Base64.getEncoder().encodeToString(b)
    fun decode(s: String?, flags: Int): ByteArray = java.util.Base64.getDecoder().decode(s)
}
