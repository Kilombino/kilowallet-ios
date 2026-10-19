package com.kilombino.kilowallet.app.i18n

internal actual fun phoneLanguages(): List<String> =
    listOfNotNull(System.getProperty("kw.lang"), java.util.Locale.getDefault().toLanguageTag())
