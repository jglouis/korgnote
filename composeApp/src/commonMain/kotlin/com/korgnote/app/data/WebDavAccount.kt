package com.korgnote.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The WebDAV folder the notes sync with, and how to sign in there. Saved in settings as JSON, as
 * the paired server's token is; null there until the user sets one up.
 *
 * Experimental: see [WebDavSyncApi].
 */
@Serializable
data class WebDavAccount(
    /** The folder holding the notes, as the user typed it: `https://cloud.example.com/remote.php/dav/files/me/notes`. */
    val url: String,
    val username: String,
    val password: String,
) {
    fun encode(): String = Json.encodeToString(this)

    companion object {
        fun decode(stored: String?): WebDavAccount? =
            stored?.takeIf { it.isNotBlank() }?.let { runCatching { Json.decodeFromString<WebDavAccount>(it) }.getOrNull() }
    }
}

/** The WebDAV server answered, and refused the user name or password. */
class WebDavRefusedException(message: String) : Exception(message)
