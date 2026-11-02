package com.korgnote.server

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes

/**
 * The Devices page's word on the watcher. A phone cannot tell a server that stopped noticing edits
 * from one with nothing new, so whatever is off has to be said here, as a warning.
 */
class NotesFolderSectionTest {

    private val now = 1_800_000_000_000L
    private val healthy = WatcherStatus(
        state = WatcherStatus.State.WATCHING,
        since = now - 3_600_000,
        folders = 4,
        notes = 120,
        lastChangeAt = now - 120_000,
        lastCheckAt = now - 20_000,
        checkEvery = 1.minutes,
    )

    @Test
    fun aHealthyWatcherSaysSoWithoutAWarning() {
        val html = notesFolderSection(healthy, now)
        assertContains(html, "Watching for edits made on this computer.")
        assertContains(html, "120 notes in 4 folders")
        assertContains(html, "last full check <time")
        assertFalse("warning" in html)
    }

    @Test
    fun aWatcherThatStoppedCheckingIsWarnedAbout() {
        val html = notesFolderSection(healthy.copy(lastCheckAt = now - 10.minutes.inWholeMilliseconds), now)
        assertContains(html, "class=warning")
        assertContains(html, "has not been checked since")
        assertFalse("Watching for edits" in html)
    }

    @Test
    fun editsFoundOnlyByTheCheckAreWarnedAboutWithTheirPaths() {
        val html = notesFolderSection(
            healthy.copy(missed = 2, lastMissedAt = now - 60_000, lastMissedPaths = listOf("inbox.org", "a<b>.org")),
            now,
        )
        assertContains(html, "class=warning")
        assertContains(html, "2 edits made on this computer were found only by the check")
        assertContains(html, "inbox.org, a&lt;b&gt;.org")
    }

    @Test
    fun aFailingWatcherIsWarnedAboutWithTheError() {
        val html = notesFolderSection(
            healthy.copy(
                state = WatcherStatus.State.FAILING,
                lastFailure = "java.io.IOException: User limit of inotify watches reached",
                lastFailureAt = now,
            ),
            now,
        )
        assertContains(html, "class=warning")
        assertContains(html, "cannot watch the notes folder")
        assertContains(html, "User limit of inotify watches reached")
    }

    @Test
    fun aWatcherThatIsNotRunningSaysEditsWillNotArrive() {
        val html = notesFolderSection(
            healthy.copy(state = WatcherStatus.State.NOT_RUNNING, lastFailure = "/notes is not a folder"),
            now,
        )
        assertContains(html, "class=warning")
        assertContains(html, "will not reach the devices until the server is restarted")
    }

    @Test
    fun aRecoveredFailureIsMentionedQuietly() {
        val html = notesFolderSection(healthy.copy(lastFailure = "java.io.IOException: gone", lastFailureAt = now - 60_000), now)
        assertFalse("class=warning" in html)
        assertContains(html, "since recovered")
    }

    @Test
    fun noWatcherIsSaid() {
        assertContains(notesFolderSection(null, now), "not watched")
    }
}
