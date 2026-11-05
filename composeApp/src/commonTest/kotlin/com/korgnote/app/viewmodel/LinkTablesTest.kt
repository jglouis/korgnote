package com.korgnote.app.viewmodel

import com.korgnote.common.index.factsOf
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkTablesTest {

    /** Every screen that names a note has to name it alike: this title goes in completed links. */
    @Test
    fun aNotesTitleIsReadAsTheOtherScreensReadIt() {
        val tables = LinkNavigator.Tables(
            mapOf(
                "blank.org" to "#+TITLE:\n#+TITLE: Second\n",
                "code.org" to "#+BEGIN_SRC org\n#+TITLE: Code\n#+END_SRC\n",
                "indented.org" to "  #+TITLE: Garden\n",
            ).mapValues { (path, text) -> factsOf(path, text) },
            textOf = { null },
        )
        assertEquals(mapOf("blank.org" to "Second", "indented.org" to "Garden"), tables.noteTitles)
    }
}
