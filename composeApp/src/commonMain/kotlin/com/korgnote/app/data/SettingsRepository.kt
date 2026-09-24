package com.korgnote.app.data

import com.korgnote.app.notifications.ReminderSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val serverHostFlow: Flow<String>
    val serverPortFlow: Flow<Int>

    fun getServerHost(): String
    fun getServerPort(): Int

    fun setServerHost(host: String)
    fun setServerPort(port: Int)

    /**
     * Bearer token for the server, or empty when it does not ask for one.
     *
     * A server started without `KORGNOTE_TOKEN` serves loopback only, or the LAN if it was told to;
     * either way it accepts requests without a token, so leaving this blank is normal while testing.
     */
    val serverTokenFlow: Flow<String>

    fun getServerToken(): String

    fun setServerToken(token: String)

    /** Reminder lead time and daily digest (T18). */
    fun getReminderSettings(): ReminderSettings

    fun setReminderSettings(settings: ReminderSettings)

    /**
     * Where a new note goes (T20), relative to the notes directory; empty for its top. A capture
     * inbox: the folder a note typed in a hurry lands in, to be filed properly later.
     */
    fun getNewNotesFolder(): String

    fun setNewNotesFolder(folder: String)
}

