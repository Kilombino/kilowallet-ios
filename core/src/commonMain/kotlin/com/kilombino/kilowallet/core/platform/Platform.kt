package com.kilombino.kilowallet.core.platform

import kotlinx.coroutines.CoroutineDispatcher

/** [n] bytes from the operating system's CSPRNG (SecRandomCopyBytes on iOS, SecureRandom on the JVM). */
expect fun secureRandomBytes(n: Int): ByteArray

/** Unicode NFKD normalisation, which BIP-39 requires for the mnemonic and passphrase. */
expect fun nfkd(s: String): String

/** A dispatcher for blocking network calls. */
expect val ioDispatcher: CoroutineDispatcher
