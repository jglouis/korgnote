package com.korgnote.common

import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgSection

/**
 * Helpers for asserting on the nested tree T4 introduced.
 *
 * A section's body is child nodes now, not a string, so these derive the couple of views the parser
 * tests care about instead of every test re-walking `children`.
 */

/** The section's own body paragraphs, joined — what the pre-T4 `content` property held. */
fun OrgSection.bodyText(): String =
    children.filterIsInstance<OrgRaw>().joinToString("\n") { it.content }

/** The headings nested directly under this one. */
val OrgSection.subsections: List<OrgSection>
    get() = children.filterIsInstance<OrgSection>()

/** Every node in the subtree rooted here, parents before children. */
fun OrgNode.walk(): List<OrgNode> = buildList {
    add(this@walk)
    children.forEach { addAll(it.walk()) }
}
