package com.korgnote.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.PairingLinks
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.viewmodel.FakeSyncApi
import com.korgnote.app.viewmodel.NotesSourceChoice
import com.korgnote.app.viewmodel.NotesViewModel
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileStamp
import com.korgnote.common.replica.FolderEntry
import com.korgnote.common.replica.FolderNoteStorage
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.NoteFolder
import com.korgnote.common.replica.SyncEngine
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A pairing link that arrives — from the camera, reading the QR code — while the notes are in a
 * folder: Settings has no pairing then, so the welcome screens open on their server step instead.
 */
@OptIn(ExperimentalTestApi::class)
class AppPairingLinkTest {

    private object EmptyFolder : NoteFolder {
        override suspend fun list() = emptyList<FolderEntry>()
        override suspend fun read(path: String): String? = null
        override suspend fun write(path: String, content: String) = FileStamp(0, 0)
        override suspend fun delete(path: String) {}
        override suspend fun stamp(path: String): FileStamp? = null
    }

    @BeforeTest
    fun setUp() {
        val folder = FolderNoteStorage(EmptyFolder) { "20261029-120000" }
        val replica = DocumentStore(InMemoryReplicaStorage())
        val viewModel = NotesViewModel(SyncEngine(replica, FakeSyncApi(mutableMapOf())), DocumentStore(folder), folder = folder)
        startKoin {
            modules(
                module {
                    single<SettingsRepository> { InMemorySettingsRepository(initialNotesSource = NotesSource.Folder("folder")) }
                    single { NotesSourceChoice(get(), replica, offersFolder = true) }
                    viewModel { viewModel }
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        PairingLinks.pending.value = null
        stopKoin()
    }

    @Test
    fun aPairingLinkOpensTheServerStep() = runComposeUiTest {
        setContent { App() }
        onNodeWithText("Connect to your computer").assertDoesNotExist()

        PairingLinks.pending.value = "korgnote://pair?v=1"
        waitForIdle()

        onNodeWithText("Connect to your computer").assertExists()
    }
}
