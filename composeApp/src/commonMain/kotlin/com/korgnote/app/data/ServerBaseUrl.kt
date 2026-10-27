package com.korgnote.app.data

/** The server on this computer, as a base URL: where [SettingsRepository.getServerHost] starts out. */
internal expect fun defaultServerBaseUrl(): String

/**
 * Whether this platform talks to a server on its own computer when it is not paired, over the
 * plain port that only that computer can reach: the desktop app and the web client do. A phone has
 * no server of its own; it pairs, or has none.
 */
internal expect val usesLocalServer: Boolean

/** Whether this platform pairs with a computer: everywhere but the browser, which cannot pin a certificate. */
internal expect val canPair: Boolean
