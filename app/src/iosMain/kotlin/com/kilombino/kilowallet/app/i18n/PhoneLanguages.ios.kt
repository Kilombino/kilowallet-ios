package com.kilombino.kilowallet.app.i18n

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/**
 * Settings › General › Language & Region (or the app's own language in Settings › Kilowallet).
 * Not NSLocale.currentLocale: iOS narrows that to the languages the bundle declares.
 */
internal actual fun phoneLanguages(): List<String> = NSLocale.preferredLanguages.mapNotNull { it as? String }
