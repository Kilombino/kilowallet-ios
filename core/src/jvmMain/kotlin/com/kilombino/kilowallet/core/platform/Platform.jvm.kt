package com.kilombino.kilowallet.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.security.SecureRandom
import java.text.Normalizer

private val rng = SecureRandom()

actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

actual fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO
