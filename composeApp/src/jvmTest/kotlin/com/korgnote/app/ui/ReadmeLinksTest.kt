package com.korgnote.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The app sends people to sections of the README, and the README's headings get rewritten: a
 * link to one that is gone opens the top of the page, and nobody notices.
 */
class ReadmeLinksTest {

    /** The anchors GitHub gives the README's headings: lower case, punctuation gone, spaces as `-`. */
    private val anchors: Set<String> by lazy {
        val readme = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "README.md") }
            .first { it.isFile }
        readme.readLines()
            .filter { it.startsWith("#") }
            .map { heading ->
                heading.trimStart('#').trim().lowercase()
                    .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
                    .replace(' ', '-')
            }
            .toSet()
    }

    @Test
    fun theInstallLinkLeadsToAHeadingThatIsThere() {
        val anchor = INSTALL_URL.substringAfter('#')
        assertTrue(anchor in anchors, "README.md has no heading for #$anchor; it has ${anchors.sorted()}")
    }
}
