package com.korgnote.app.viewmodel

import com.korgnote.app.notifications.NotificationScheduler
import com.korgnote.app.notifications.Reminder
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.app.notifications.ReminderTarget
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * When the device's reminders are replaced (T18): after the notes are read, whenever they change,
 * and not otherwise — a sync that changes nothing must leave the device's notifications alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderSchedulingTest {
    private val work = "work.org"
    private val workText = "* Design review <2026-09-23 Wed 10:00>\n* Other\n"

    /** Every list the view model handed over, in order. */
    private val handedOver = mutableListOf<List<Reminder>>()
    private val scheduler = object : NotificationScheduler {
        override suspend fun replaceAll(reminders: List<Reminder>) {
            handedOver += reminders
        }
    }
    private var settings = ReminderSettings()
    private lateinit var api: FakeSyncApi
    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        api = FakeSyncApi(mutableMapOf(work to workText))
        viewModel = NotesViewModel(
            SyncEngine(store, api),
            store,
            today = { LocalDate(2026, 9, 23) },
            notifications = scheduler,
            reminderSettings = { settings },
            now = { LocalDateTime(2026, 9, 23, 8, 0) },
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun nothingIsReplacedBeforeTheNotesHaveBeenRead() {
        // Coming to the foreground before the replica is read would otherwise cancel every
        // reminder the device holds.
        viewModel.agenda.refreshReminders()
        assertEquals(emptyList(), handedOver)
    }

    @Test
    fun readingTheNotesSchedulesTheirReminders() = runTest {
        viewModel.server.load()
        assertEquals(listOf(listOf("Design review")), handedOver.map { plan -> plan.map { it.title } })
    }

    @Test
    fun aSyncThatChangesNothingLeavesThemAlone() = runTest {
        viewModel.server.load()
        viewModel.server.load()
        viewModel.agenda.refreshReminders()
        assertEquals(1, handedOver.size)
    }

    @Test
    fun anEditThatAddsAnAppointmentReplacesThem() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)

        viewModel.note.onContentChanged(workText.replace("* Other", "* Lunch <2026-09-23 Wed 12:30>"))
        viewModel.saveCurrentFile()

        assertEquals(listOf("Design review", "Lunch"), handedOver.last().map { it.title })
    }

    @Test
    fun aChangedSettingAppliesOnTheNextPlan() = runTest {
        viewModel.server.load()

        settings = ReminderSettings(enabled = false)
        viewModel.agenda.refreshReminders()

        assertEquals(emptyList(), handedOver.last())
    }

    @Test
    fun aTapThatArrivesBeforeTheNotesOpensTheNoteOnceTheyAreRead() = runTest {
        viewModel.agenda.openReminderTarget(ReminderTarget(work, offset = workText.indexOf("* Other")))

        viewModel.server.load()

        assertEquals(workText, viewModel.note.content)
        assertEquals(1, viewModel.note.scrollToElementIndex)
    }

    @Test
    fun aTapForANoteThatIsGoneIsDropped() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(work)

        viewModel.agenda.openReminderTarget(ReminderTarget("deleted.org", offset = 0))
        // A later sync must not suddenly act on it either.
        viewModel.server.load()

        assertEquals(workText, viewModel.note.content)
        assertEquals(null, viewModel.note.scrollToElementIndex)
    }
}
