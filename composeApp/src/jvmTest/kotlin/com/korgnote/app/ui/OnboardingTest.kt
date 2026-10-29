package com.korgnote.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.SettingsRepository
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The welcome screens: the two choices side by side, and each one's next step. */
@OptIn(ExperimentalTestApi::class)
class OnboardingTest {

    @BeforeTest
    fun setUp() {
        startKoin { modules(module { single<SettingsRepository> { InMemorySettingsRepository(initialNotesSource = null) } }) }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun bothChoicesAreShownWithWhatEachCosts() = runComposeUiTest {
        setContent { Onboarding(current = null, onChosen = {}, onCancel = null) }

        onNodeWithText("Welcome to KorgNote").assertExists()
        onNodeWithText("A folder on this device").assertExists()
        onNodeWithText("My computer, with the KorgNote server").assertExists()
        onNodeWithText("A note edited on both sides is merged.").assertExists()
        // Not WebDAV: there is no replica for it here, as on the desktop and the web.
        onNodeWithText("A WebDAV server (experimental)").assertDoesNotExist()
        // Nothing to go back to on the first start.
        onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun theServerCanBePairedLater() = runComposeUiTest {
        val chosen = mutableListOf<NotesSource>()
        setContent { Onboarding(current = null, onChosen = { chosen += it }, onCancel = null) }

        onNodeWithText("My computer, with the KorgNote server").performClick()
        onNodeWithText("Connect to your computer").assertExists()
        onNodeWithText("Continue").assertExists()
        onNodeWithText("Pair later").performClick()

        assertEquals(listOf<NotesSource>(NotesSource.Server), chosen)
    }

    @Test
    fun backLeadsFromAStepToTheChoice() = runComposeUiTest {
        setContent { Onboarding(current = null, onChosen = {}, onCancel = null) }

        onNodeWithText("A folder on this device").performClick()
        onNodeWithText("Choose your notes folder").assertExists()
        onNodeWithContentDescription("Back").performClick()

        onNodeWithText("Welcome to KorgNote").assertExists()
    }

    @Test
    fun fromSettingsTheChoiceCanBeLeftAsItWas() = runComposeUiTest {
        var cancelled = false
        setContent { Onboarding(current = NotesSource.Server, onChosen = {}, onCancel = { cancelled = true }) }

        onNodeWithText("Where are your notes?").assertExists()
        onNodeWithContentDescription("Back").performClick()

        assertTrue(cancelled)
    }
}
