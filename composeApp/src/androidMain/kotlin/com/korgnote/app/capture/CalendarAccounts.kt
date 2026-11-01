package com.korgnote.app.capture

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.util.Log

/**
 * The accounts whose calendars the phone syncs and shows, a Google account's among them: those a
 * sync is asked of for new events. Empty without the permission to read the calendars.
 */
fun calendarAccounts(context: Context): Set<Account> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptySet()
    val accounts = mutableSetOf<Account>()
    runCatching {
        context.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
            "${Calendars.SYNC_EVENTS} = 1 AND ${Calendars.VISIBLE} = 1 AND ${Calendars.ACCOUNT_TYPE} != ?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { rows ->
            while (rows.moveToNext()) {
                val name = rows.getString(0) ?: continue
                val type = rows.getString(1) ?: continue
                accounts += Account(name, type)
            }
        }
    }.onFailure { Log.w("DeviceCalendar", "Could not read the calendars' accounts", it) }
    return accounts
}

/**
 * Those of [calendarAccounts] the app may not see. Android ignores, without a word, a sync asked of
 * an account the app may not see, and the Google account is one until it is picked in Android's
 * account chooser: then it stays visible to the app.
 */
fun hiddenCalendarAccounts(context: Context): List<Account> {
    // Without GET_ACCOUNTS, which no longer reveals other apps' accounts, these are the visible ones.
    val seen = AccountManager.get(context).accounts.toSet()
    return calendarAccounts(context).filter { it !in seen }
}
