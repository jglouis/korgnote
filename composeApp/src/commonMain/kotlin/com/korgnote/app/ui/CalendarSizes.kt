package com.korgnote.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Size tokens used to lay out and render the calendar UI across day, week, and month views.
 *
 * Groups the typography and dimension values that keep calendar entries, labels, day numbers,
 * icons, and the hour gutter visually consistent for a given available drawing area.
 *
 * Use one of the predefined size sets for common window classes, or select an appropriate set with
 * [fitting] when the available width and height are known.
 */
data class CalendarSizes(
    /** An entry in the week's all-day strip and in the month. */
    val chipFont: TextUnit,
    val chipLine: TextUnit,
    /** An entry in the day's all-day strip, which has the width of the screen. */
    val dayChipFont: TextUnit,
    val dayChipLine: TextUnit,
    /** The title of a block on the hour grid. */
    val titleFont: TextUnit,
    val titleLine: TextUnit,
    /** The time under a block's title. */
    val timeFont: TextUnit,
    val timeLine: TextUnit,
    /** The hours down the side, the weekdays over the week, and the count of what did not fit. */
    val labelFont: TextUnit,
    val labelLine: TextUnit,
    /** A day's number and the weekdays over the month, and the circle the number sits in. */
    val dayNumberFont: TextUnit,
    val dayNumber: Dp,
    /** The kind's icon before an entry. */
    val icon: Dp,
    /** The column the hours are written in. */
    val gutter: Dp,
) {
    companion object {
        /** A phone, upright or on its side. */
        val Compact = CalendarSizes(
            chipFont = 10.sp, chipLine = 12.sp,
            dayChipFont = 11.sp, dayChipLine = 13.sp,
            titleFont = 11.sp, titleLine = 16.sp,
            timeFont = 9.sp, timeLine = 11.sp,
            labelFont = 11.sp, labelLine = 16.sp,
            dayNumberFont = 12.sp, dayNumber = 24.dp,
            icon = 10.dp,
            gutter = 44.dp,
        )

        /** A small tablet, an unfolded phone, a narrow window. */
        val Medium = CalendarSizes(
            chipFont = 12.sp, chipLine = 15.sp,
            dayChipFont = 13.sp, dayChipLine = 16.sp,
            titleFont = 12.sp, titleLine = 16.sp,
            timeFont = 11.sp, timeLine = 13.sp,
            labelFont = 12.sp, labelLine = 16.sp,
            dayNumberFont = 13.sp, dayNumber = 28.dp,
            icon = 12.dp,
            gutter = 48.dp,
        )

        /** A tablet on its side, a browser window. */
        val Large = CalendarSizes(
            chipFont = 14.sp, chipLine = 18.sp,
            dayChipFont = 14.sp, dayChipLine = 18.sp,
            titleFont = 14.sp, titleLine = 18.sp,
            timeFont = 12.sp, timeLine = 15.sp,
            labelFont = 13.sp, labelLine = 18.sp,
            dayNumberFont = 14.sp, dayNumber = 32.dp,
            icon = 14.dp,
            gutter = 56.dp,
        )

        /**
         * Selects the calendar size configuration that best fits the available dimensions.
         *
         * @param width Available width for the calendar layout.
         * @param height Available height for the calendar layout.
         * @return The matching calendar size configuration for the supplied dimensions.
         */
        fun fitting(width: Dp, height: Dp): CalendarSizes = when {
            width >= 960.dp && height >= 520.dp -> Large
            width >= 600.dp && height >= 400.dp -> Medium
            else -> Compact
        }
    }
}

/**
 * Composition local that supplies the calendar sizing configuration for the current Compose
 * hierarchy.
 *
 * Defaults to [CalendarSizes.Compact] when no value is provided by an ancestor.
 */
val LocalCalendarSizes = staticCompositionLocalOf { CalendarSizes.Compact }
