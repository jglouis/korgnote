package com.korgnote.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.korgnote.common.org.OrgTodoConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keyword colours in the editors. The element editor holds one heading, which never contains the
 * file's `#+TODO:` lines, so it is handed the file's keywords as a fallback; a declaration in the
 * text being edited still wins, so editing the declaration itself restyles at once.
 */
class OrgTodoKeywordsHighlightTest {
    private val fileKeywords = OrgTodoConfig(todo = setOf("EVENT"), done = setOf("FINISHED"))

    private fun colorOf(word: String, text: String, fallback: OrgTodoConfig? = null): Color? {
        val transformation = OrgSyntaxHighlightTransformation(Color.Red)
        if (fallback != null) transformation.fallbackKeywords = fallback
        val styled = transformation.filter(AnnotatedString(text)).text
        val at = text.indexOf(word)
        return styled.spanStyles.lastOrNull { at >= it.start && at < it.end && it.item.color != Color.Unspecified }?.item?.color
    }

    @Test
    fun anElementIsStyledWithItsFilesKeywords() {
        assertEquals(OrgColors.done, colorOf("FINISHED", "* FINISHED Dentist\n", fileKeywords))
        assertEquals(OrgColors.todo, colorOf("EVENT", "* EVENT Party\n", fileKeywords))
    }

    @Test
    fun withoutThemAnElementOnlyKnowsOrgsOwn() {
        // Not a keyword, so it takes the heading's colour like the rest of the title.
        assertEquals(Color.Red, colorOf("FINISHED", "* FINISHED Dentist\n"))
        assertEquals(OrgColors.done, colorOf("DONE", "* DONE Dentist\n"))
    }

    @Test
    fun aDeclarationInTheEditedTextWins() {
        val text = "#+TODO: NEXT | SHIPPED\n* SHIPPED Release\n* FINISHED Not a keyword here\n"
        assertEquals(OrgColors.done, colorOf("SHIPPED Release", text, fileKeywords))
        assertEquals(Color.Red, colorOf("FINISHED", text, fileKeywords))
    }

    @Test
    fun changingTheFallbackRestylesTheSameText() {
        val transformation = OrgSyntaxHighlightTransformation(Color.Red)
        val text = AnnotatedString("* FINISHED Dentist\n")
        val before = transformation.filter(text).text.spanStyles
        transformation.fallbackKeywords = fileKeywords
        val after = transformation.filter(text).text.spanStyles
        assertEquals(true, before != after, "the cache must not serve the result styled with the old keywords")
    }
}
