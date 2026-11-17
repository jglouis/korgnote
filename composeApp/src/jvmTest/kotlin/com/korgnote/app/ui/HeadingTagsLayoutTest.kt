package com.korgnote.app.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A heading with several tags on a phone-wide screen: the tags wrap within their share of the
 * width, and the title keeps the rest, rather than being squeezed to a few letters a line.
 */
@OptIn(ExperimentalTestApi::class)
class HeadingTagsLayoutTest {
    @Test
    fun tagsWrapAndLeaveTheTitleMostOfTheWidth() = runComposeUiTest {
        val document = "* TODO Water the ferns on the balcony before the weekend :Gardening:Household:Weekend:\n".toOrgDocument()
        setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Box(Modifier.width(380.dp)) {
                    OrgText(content = document)
                }
            }
        }
        val title = onNodeWithText("Water the ferns", substring = true).getBoundsInRoot()
        val first = onNodeWithText("Gardening").getBoundsInRoot()
        val last = onNodeWithText("Weekend").getBoundsInRoot()
        assertTrue(title.right - title.left >= 200.dp, "title width ${title.right - title.left}")
        assertTrue(last.top > first.top, "tags should wrap onto another line: $first $last")
    }
}
