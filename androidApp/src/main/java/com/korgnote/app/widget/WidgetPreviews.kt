package com.korgnote.app.widget

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hands the launcher a picture of each widget, drawn by the widget itself on made-up entries, for its
 * widget picker (Android 15 and on; before, the picker has the layouts the widgets' info names).
 *
 * Android allows only a few of these an hour, so once per version of the app and language of the
 * phone: the pictures change with neither otherwise.
 */
object WidgetPreviews {
    private const val PREFS = "korgnote_widget_previews"
    private const val KEY_PUBLISHED = "published"

    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
        val published = "$version ${app.resources.configuration.locales[0].toLanguageTag()}"
        if (prefs.getString(KEY_PUBLISHED, null) == published) return
        CoroutineScope(Dispatchers.Default).launch {
            val manager = GlanceAppWidgetManager(app)
            val results = runCatching {
                listOf(
                    manager.setWidgetPreviews(AgendaWidgetReceiver::class),
                    manager.setWidgetPreviews(CalendarWidgetReceiver::class),
                )
            }.onFailure { println("[DEBUG_LOG][WidgetPreviews] Failed: ${it.message}") }.getOrNull()
            // Rate limited, or failed: tried again the next time the app starts.
            if (results?.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS } == true) {
                prefs.edit { putString(KEY_PUBLISHED, published) }
            }
        }
    }
}
