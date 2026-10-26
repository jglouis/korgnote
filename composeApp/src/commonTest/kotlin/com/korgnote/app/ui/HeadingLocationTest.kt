package com.korgnote.app.ui

import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.toOrgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeadingLocationTest {

    private fun firstHeading(text: String) = text.toOrgDocument().children.filterIsInstance<OrgSection>().first()

    @Test
    fun aHeadingsLocationComesFromItsPropertyDrawer() {
        val heading = firstHeading("* Lunch\n:PROPERTIES:\n:ID: x\n:location:  Place du Jeu de Balle \n:END:\nBody\n")
        assertEquals("Place du Jeu de Balle", heading.location())
    }

    @Test
    fun noDrawerOrABlankLocationIsNone() {
        assertNull(firstHeading("* Lunch\nBody\n").location())
        assertNull(firstHeading("* Lunch\n:PROPERTIES:\n:LOCATION:\n:END:\n").location())
    }
}
