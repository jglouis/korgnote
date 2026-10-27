package com.korgnote.app.files

import kotlin.test.Test
import kotlin.test.assertEquals

/** The path a link writes for a file chosen in the desktop's file dialog. */
class DesktopLinkPathTest {

    private val home = System.getProperty("user.home")

    @Test
    fun aFileInAMappedFolderIsWrittenAsTheMappingNamesIt() {
        val mappings = listOf(FileLinkMapping("~/Sync", "$home/Dropbox/Sync"))
        assertEquals("~/Sync/a.pdf", desktopLinkPath("$home/Dropbox/Sync/a.pdf", mappings))
    }

    @Test
    fun aMappingsFolderMayBeTypedWithATilde() {
        val mappings = listOf(FileLinkMapping("/srv/shared", "~/Shared"))
        assertEquals("/srv/shared/x/b.png", desktopLinkPath("$home/Shared/x/b.png", mappings))
    }

    @Test
    fun anyOtherFileIsItsPathWithHomeAsATilde() {
        assertEquals("~/Documents/c.pdf", desktopLinkPath("$home/Documents/c.pdf", emptyList()))
        assertEquals("/tmp/d.pdf", desktopLinkPath("/tmp/d.pdf", emptyList()))
    }
}
