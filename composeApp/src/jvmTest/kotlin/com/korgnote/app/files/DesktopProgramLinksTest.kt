package com.korgnote.app.files

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A link in a note opens what the note's author pointed it at, whatever its words say. The desktop
 * would run a program or a script on that click, so those are never opened from a link; documents
 * are. Every file here is made up, in a folder of the test's own.
 */
class DesktopProgramLinksTest {
    private val dir: File = Files.createTempDirectory("korgnote-links").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String, executable: Boolean = false) = File(dir, name).apply {
        writeText("made up")
        setExecutable(executable)
    }

    @Test
    fun programsScriptsAndShortcutsAreNotOpened() {
        for (name in listOf("Invoice.bat", "setup.EXE", "run.cmd", "x.ps1", "x.vbs", "x.js", "x.hta", "x.lnk", "x.command", "x.terminal", "x.sh", "x.desktop", "x.jar", "x.fileloc", "x.py")) {
            assertTrue(DesktopFileOpener.isProgram(file(name)), name)
        }
        // An app on macOS is a folder.
        assertTrue(DesktopFileOpener.isProgram(File(dir, "Calculator.app").apply { mkdirs() }))
        // A file without an extension that may be executed runs in the terminal on macOS.
        assertTrue(DesktopFileOpener.isProgram(file("tool", executable = true)))
    }

    @Test
    fun documentsAndFoldersAre() {
        for (name in listOf("report.pdf", "photo.JPG", "notes.org", "sheet.xlsx", "README", "archive.zip")) {
            assertFalse(DesktopFileOpener.isProgram(file(name)), name)
        }
        assertFalse(DesktopFileOpener.isProgram(File(dir, "Projects").apply { mkdirs() }))
        // A document marked executable, as files on some drives are, is opened by its type.
        assertFalse(DesktopFileOpener.isProgram(file("scan.pdf", executable = true)))
    }

    @Test
    fun aLinkToAProgramSaysWhyItIsNotOpened() = runTest {
        val result = DesktopFileOpener.openAsWritten(file("Invoice.bat").path)
        assertIs<FileOpenResult.Failed>(result)
        assertEquals(true, result.reason.startsWith("KorgNote does not open programs or scripts from a link: Invoice.bat."), result.reason)
    }
}
