package com.korgnote.app

import com.korgnote.common.org.link.LinkTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SavedSearch
import com.korgnote.app.data.LastScreen
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.ui.*
import com.korgnote.app.viewmodel.LinkEdit
import com.korgnote.app.viewmodel.NotesViewModel
import kotlinx.coroutines.launch
import com.korgnote.app.widgets.CalendarTarget
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.app_file
import korgnote.composeapp.generated.resources.app_search
import korgnote.composeapp.generated.resources.calendar
import korgnote.composeapp.generated.resources.settings
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CoroutineScope

/**
 * What the pieces of the notes screens share: which tab shows, where the calendar was left, and what
 * is open over them. Held here rather than handed around one by one, since nearly every piece reads
 * or moves several of these.
 */
@Stable
internal class NotesAppState(
    val source: NotesSource,
    val vm: NotesViewModel,
    val settingsRepo: SettingsRepository,
    val scope: CoroutineScope,
    val drawerState: DrawerState,
    /**
     * The open note's scroll position, held here for the floating buttons to read and move, and
     * kept while another tab shows, so that coming back to File finds the note where it was left.
     * Another note opened moves it to where that one was left; see OpenNote.restoreTo.
     */
    val documentListState: LazyListState,
    selectedScreen: MutableState<BottomItem>,
    calendarView: MutableState<CalendarTarget?>,
    calendarTarget: MutableState<CalendarTarget?>,
    calendarRequests: MutableIntState,
) {
    var selectedScreen by selectedScreen

    /**
     * The calendar's view and period as last seen there, which its tab opens on again, now or at the
     * next launch. A period left on an earlier day is not taken back: the calendar opens on today,
     * in the view it was left in.
     */
    var calendarView by calendarView

    /**
     * The view and day the calendar opens on: from its tab, the one it was left on; when asked from
     * elsewhere — a tapped SCHEDULED or DEADLINE date opens its day, a widget the view it shows —
     * that one; and null for the calendar's own starting view, when it was never left anywhere.
     */
    var calendarTarget by calendarTarget

    /** Each request from elsewhere is counted, so asking for the same day again starts there again, wherever the calendar was moved since. */
    var calendarRequests by calendarRequests

    var dialog by mutableStateOf<NoteDialog?>(null)

    // The calendar's "Capture an event": the capture sheet, open on the phone's calendar.
    var capturingEvent by mutableStateOf(false)

    // Why the note could not be made or renamed, in the dialog that tried.
    var noteDialogError by mutableStateOf<String?>(null)

    // Why something could not be done, shown for a moment.
    val snackbarHostState = SnackbarHostState()

    // Whose version a note in conflict holds, for the banner and the comparison to say.
    val keptSide = if (source is NotesSource.Folder) KeptSide.FOLDER else KeptSide.SERVER

    /** Closes [which], if it is still what is open: what finished may have been dismissed meanwhile. */
    fun close(which: NoteDialog) {
        if (dialog == which) dialog = null
    }

    fun openCalendar(target: CalendarTarget) {
        calendarTarget = target
        calendarRequests++
        selectedScreen = BottomItem.CALENDAR
    }

    /** Opens or starts today's daily, from the drawer. */
    fun openToday() {
        scope.launch {
            vm.noteFiles.openToday()
            selectedScreen = BottomItem.FILE
            drawerState.close()
        }
    }

    fun selectTab(item: BottomItem) {
        // Back to where the calendar was left; its own tab, tapped again, leaves it be.
        if (item == BottomItem.CALENDAR && selectedScreen != BottomItem.CALENDAR) calendarTarget = calendarView
        selectedScreen = item
    }
}

/**
 * What is open over the notes, of what a tap on the screen beneath opens, which a dialog's scrim makes
 * one at a time. Not among them is what comes from elsewhere: the capture sheet, which a link from
 * another app opens over anything, and what the view model asks for, a table or a date to pick.
 */
internal sealed interface NoteDialog {
    data object NewNote : NoteDialog
    data object NoteDetails : NoteDialog

    /** The notes and headings a link can go to, while the link picker is open. */
    data class PickingLink(val targets: List<LinkTarget>) : NoteDialog

    /** The heading being refiled, by element index, with where it can go. */
    data class Refiling(val index: Int, val targets: List<LinkTarget>) : NoteDialog
    data class EditingLink(val edit: LinkEdit) : NoteDialog

    /** A table being made from the editor's Table button, before it goes into the element. */
    data object NewTable : NoteDialog
    data class RenamingNote(val path: String) : NoteDialog
    data class DeletingNote(val path: String, val unsynced: Boolean) : NoteDialog

    /** A search being named: one about to be saved, or a saved one being renamed. */
    data class NamingSearch(val search: SavedSearch, val renaming: Boolean) : NoteDialog

    /** The conflict copy being compared with its note. */
    data class ComparingCopy(val copy: String) : NoteDialog
}

@Composable
internal fun rememberNotesAppState(source: NotesSource, vm: NotesViewModel, settingsRepo: SettingsRepository): NotesAppState {
    // Where the app was left, to open there again: the tab, and the calendar's view.
    val resumed = remember { settingsRepo.getLastScreen() }
    val selectedScreen = rememberSaveable {
        mutableStateOf(BottomItem.entries.firstOrNull { it.name == resumed.tab } ?: BottomItem.FILE)
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val calendarView = rememberSaveable(stateSaver = OptionalCalendarTargetSaver) {
        mutableStateOf(resumed.calendarView(today = currentDate()))
    }
    val calendarTarget = rememberSaveable(stateSaver = OptionalCalendarTargetSaver) { mutableStateOf(calendarView.value) }
    val calendarRequests = rememberSaveable { mutableIntStateOf(0) }
    val documentListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    return remember(vm) {
        NotesAppState(source, vm, settingsRepo, scope, drawerState, documentListState, selectedScreen, calendarView, calendarTarget, calendarRequests)
    }
}

/**
 * The calendar's view as it was left, to open on again [today]: in the same period when it was left
 * today, and on today otherwise. Null when it was never left anywhere.
 */
private fun LastScreen.calendarView(today: LocalDate): CalendarTarget? {
    val mode = CalendarMode.entries.firstOrNull { it.name == calendarMode } ?: return null
    val date = calendarDate
        ?.takeIf { savedOn == today.toString() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return CalendarTarget(mode, date ?: today)
}

internal fun currentDate(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

internal enum class BottomItem {
    FILE, SEARCH, CALENDAR, SETTINGS;

    @Composable
    fun getLabel() = stringResource(
        when (this) {
            FILE -> Res.string.app_file
            SEARCH -> Res.string.app_search
            CALENDAR -> Res.string.calendar
            SETTINGS -> Res.string.settings
        },
    )

    fun getIconRes() = when (this) {
        FILE -> Icons.Filled.Description
        SEARCH -> Icons.Filled.Search
        CALENDAR -> Icons.Filled.CalendarMonth
        SETTINGS -> Icons.Filled.Settings
    }
}
