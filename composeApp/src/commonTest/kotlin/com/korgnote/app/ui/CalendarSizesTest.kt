package com.korgnote.app.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which sizes the day, the week and the month draw at, for the room the calendar has. */
class CalendarSizesTest {
    @Test
    fun aPhoneKeepsItsSizesUprightAndOnItsSide() {
        assertEquals(CalendarSizes.Compact, CalendarSizes.fitting(374.dp, 640.dp))
        // As wide as a tablet, and short of the height larger text would take.
        assertEquals(CalendarSizes.Compact, CalendarSizes.fitting(860.dp, 280.dp))
    }

    @Test
    fun aSmallTabletOrANarrowWindowDrawsLarger() {
        assertEquals(CalendarSizes.Medium, CalendarSizes.fitting(600.dp, 800.dp))
        assertEquals(CalendarSizes.Medium, CalendarSizes.fitting(1400.dp, 450.dp))
    }

    @Test
    fun aBrowserWindowOrATabletOnItsSideDrawsLargest() {
        assertEquals(CalendarSizes.Large, CalendarSizes.fitting(1264.dp, 640.dp))
    }
}
