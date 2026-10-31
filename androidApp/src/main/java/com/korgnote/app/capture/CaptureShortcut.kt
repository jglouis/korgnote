package com.korgnote.app.capture

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.korgnote.androidapp.R

/** The folder in the app's files where captures wait; left out of backups with the notes. */
const val CAPTURE_QUEUE = "capture-queue"

/**
 * "Capture" in the launcher's long-press menu: an empty capture sheet, to type something down
 * without opening the notes. Published from the code rather than declared, since a declared one must
 * name the package, and the debug build's is not the release's.
 */
object CaptureShortcut {
    const val ACTION = "com.korgnote.app.CAPTURE"

    /** The sheet opened on the phone's calendar, to capture one of its events. */
    const val ACTION_EVENT = "com.korgnote.app.CAPTURE_EVENT"

    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        fun shortcut(id: String, short: Int, long: Int, action: String) = ShortcutInfo.Builder(context, id)
            .setShortLabel(context.getString(short))
            .setLongLabel(context.getString(long))
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(action, null, context, CaptureActivity::class.java))
            .build()
        val shortcuts = listOf(
            shortcut("capture", R.string.capture_shortcut_short, R.string.capture_shortcut_long, ACTION),
            shortcut("capture-event", R.string.capture_event_shortcut_short, R.string.capture_event_shortcut_long, ACTION_EVENT),
        )
        // A launcher that refuses shortcuts costs the user the shortcut, not the app.
        runCatching { manager.dynamicShortcuts = shortcuts }
    }
}
