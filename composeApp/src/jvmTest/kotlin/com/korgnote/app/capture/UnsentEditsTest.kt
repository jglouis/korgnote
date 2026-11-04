package com.korgnote.app.capture

import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.di.WEBDAV
import com.korgnote.app.di.appModule
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the background sync asks before trying again when the server could not be reached: whether an
 * edit waits to be sent, in the notes in use. Asked of the server's replica on WebDAV, the answer was
 * no, and an edit made offline waited for the hourly sync.
 */
class UnsentEditsTest {
    private val settings = InMemorySettingsRepository()

    @BeforeTest
    fun setUp() {
        startKoin {
            modules(
                appModule,
                module {
                    single<SettingsRepository> { settings }
                    single<ReplicaStorage> { InMemoryReplicaStorage() }
                    single<ReplicaStorage>(WEBDAV) { InMemoryReplicaStorage() }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun anEditWaitingInTheWebDavNotesIsSeenOnWebDav() = runTest {
        val koin = KoinPlatform.getKoin()
        settings.setNotesSource(NotesSource.WebDav)
        koin.get<DocumentStore>(WEBDAV).save("note.org", "* Edited offline\n")

        assertTrue(koin.hasUnsentEdits())
    }

    @Test
    fun anEditLeftInTheServersNotesIsNotAskedAboutOnWebDav() = runTest {
        val koin = KoinPlatform.getKoin()
        settings.setNotesSource(NotesSource.WebDav)
        koin.get<DocumentStore>().save("note.org", "* Left from before\n")

        assertFalse(koin.hasUnsentEdits())
    }

    @Test
    fun anEditWaitingInTheServersNotesIsSeenOnTheServer() = runTest {
        val koin = KoinPlatform.getKoin()
        settings.setNotesSource(NotesSource.Server)
        koin.get<DocumentStore>().save("note.org", "* Edited offline\n")

        assertTrue(koin.hasUnsentEdits())
    }
}
