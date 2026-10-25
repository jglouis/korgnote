package com.korgnote.app

import android.app.Application
import com.korgnote.app.data.PersistentSettingsRepository
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.androidReplica
import com.korgnote.app.files.AndroidFileOpener
import com.korgnote.app.files.FileOpener
import com.korgnote.app.di.initKoin
import com.korgnote.app.notifications.AndroidNotificationScheduler
import com.korgnote.app.notifications.NotificationScheduler
import org.koin.dsl.module

// Koin must be started exactly once per process. Doing it here (instead of in the Activity) survives
// configuration changes such as screen rotation, which recreate the Activity but not the Application.
class KorgNoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val androidModule = module {
            single<SettingsRepository> { PersistentSettingsRepository(this@KorgNoteApplication) }
            androidReplica(this@KorgNoteApplication)
            single<NotificationScheduler> { AndroidNotificationScheduler(this@KorgNoteApplication) }
            single<FileOpener> { AndroidFileOpener(this@KorgNoteApplication) }
        }
        initKoin(extraModules = listOf(androidModule))
    }
}
