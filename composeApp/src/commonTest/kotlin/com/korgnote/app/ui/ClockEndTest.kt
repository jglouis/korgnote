package com.korgnote.app.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** When a clock left running stops, for a time of day picked the morning after. */
class ClockEndTest {
    private val start = LocalDateTime(2026, 10, 31, 14, 0)
    private val now = LocalDateTime(2026, 11, 1, 9, 30)

    @Test
    fun aTimeLaterThanNowIsYesterdays() =
        assertEquals(LocalDateTime(2026, 10, 31, 18, 0), clockEndAt(start, now, LocalTime(18, 0)))

    @Test
    fun aTimeEarlierThanNowIsTodays() =
        assertEquals(LocalDateTime(2026, 11, 1, 8, 0), clockEndAt(start, now, LocalTime(8, 0)))

    @Test
    fun aTimeBeforeTheStartStopsAtTheStart() =
        assertEquals(start, clockEndAt(start, now, LocalTime(11, 0)))
}
