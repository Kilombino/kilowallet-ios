package com.kilombino.kilowallet.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    if (n == 0) return out
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.toULong(), it.addressOf(0)) }
    check(status == errSecSuccess) { "the system random number generator failed ($status)" }
    return out
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun nfkd(s: String): String = NSString.create(string = s).decomposedStringWithCompatibilityMapping

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO
