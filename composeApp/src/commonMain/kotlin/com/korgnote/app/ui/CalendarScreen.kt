package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.tooling.preview.Preview
import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgPlanning
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

const val SLIDE_DURATION_MS = 100
const val FADE_DURATION_MS = 100

@OptIn(ExperimentalTime::class)
@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier.background(MaterialTheme.colorScheme.surface),
    scheduledByDate: Map<LocalDate, List<Pair<String, OrgNode>>> = emptyMap(),
    onOpenScheduled: (Pair<String, OrgNode>) -> Unit = {},
    initialMode: CalendarMode? = null,
    initialAnchorDate: LocalDate? = null,
    initialDialogDate: LocalDate? = null
) {
    var today by remember {
        mutableStateOf(
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        )
    }
    var anchorDate by remember { mutableStateOf(initialAnchorDate ?: today) }
    var mode by remember { mutableStateOf(initialMode ?: CalendarMode.Month) }
    var slideDirection by remember { mutableStateOf(0) } // -1 for left (next), +1 for right (prev)

    Column(modifier = modifier.padding(8.dp)) {
        // Header with month/year and navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                Text(
                    text = "${
                        anchorDate.month.name.lowercase().replaceFirstChar { it.titlecase() }
                    } ${anchorDate.year}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Today",
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clickable { slideDirection = 0; anchorDate = today },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "Previous",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { slideDirection = 1; anchorDate = mode.prev(anchorDate) }
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Next",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { slideDirection = -1; anchorDate = mode.next(anchorDate) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Mode switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = mode == CalendarMode.Week,
                onClick = { slideDirection = 0; mode = CalendarMode.Week },
                label = { Text("Week") }
            )
            FilterChip(
                selected = mode == CalendarMode.Month,
                onClick = { slideDirection = 0; mode = CalendarMode.Month },
                label = { Text("Month") }
            )
        }

        Spacer(Modifier.height(8.dp))

        // Week day headers
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            for (d in days) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        d,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        var dialogDate by remember { mutableStateOf(initialDialogDate) }
        val dialogEntries = dialogDate?.let { scheduledByDate[it] }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(mode, anchorDate) {
                    var triggered = false
                    var totalDx = 0f
                    val threshold = 60f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            if (triggered) return@detectHorizontalDragGestures
                            totalDx += dragAmount
                            if (totalDx <= -threshold) {
                                slideDirection = -1
                                anchorDate = mode.next(anchorDate)
                                triggered = true
                            } else if (totalDx >= threshold) {
                                slideDirection = 1
                                anchorDate = mode.prev(anchorDate)
                                triggered = true
                            }
                        },
                        onDragEnd = {
                            triggered = false
                            totalDx = 0f
                        },
                        onDragCancel = {
                            triggered = false
                            totalDx = 0f
                        }
                    )
                }
        ) {
            AnimatedContent(
                targetState = Pair(mode, anchorDate),
                transitionSpec = {
                    val dir = slideDirection
                    if (dir < 0) {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(SLIDE_DURATION_MS)
                        ) + fadeIn(animationSpec = tween(SLIDE_DURATION_MS)) togetherWith
                                slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Left,
                                    animationSpec = tween(SLIDE_DURATION_MS)
                                ) + fadeOut(animationSpec = tween(SLIDE_DURATION_MS))
                    } else if (dir > 0) {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(SLIDE_DURATION_MS)
                        ) + fadeIn(animationSpec = tween(SLIDE_DURATION_MS)) togetherWith
                                slideOutOfContainer(
                                    AnimatedContentTransitionScope.SlideDirection.Right,
                                    animationSpec = tween(SLIDE_DURATION_MS)
                                ) + fadeOut(animationSpec = tween(SLIDE_DURATION_MS))
                    } else {
                        fadeIn(animationSpec = tween(FADE_DURATION_MS)) togetherWith fadeOut(
                            animationSpec = tween(FADE_DURATION_MS)
                        )
                    }
                }, label = "calendarPager"
            ) { (calendarMode, aDate) ->
                when (calendarMode) {
                    CalendarMode.Month -> MonthGrid(
                        anchorDate = aDate,
                        today = today,
                        scheduledByDate = scheduledByDate
                    ) { date ->
                        if (!scheduledByDate[date].isNullOrEmpty()) dialogDate = date
                    }

                    CalendarMode.Week -> WeekRow(
                        anchorDate = aDate,
                        today = today,
                        scheduledByDate = scheduledByDate
                    ) { date ->
                        if (!scheduledByDate[date].isNullOrEmpty()) dialogDate = date
                    }
                }
            }
        }

        // Dialog showing scheduled entries for selected date
        if (dialogEntries != null && dialogEntries.isNotEmpty() && dialogDate != null) {
            AlertDialog(
                onDismissRequest = { dialogDate = null },
                confirmButton = {
                    Text(
                        text = "Close",
                        modifier = Modifier.clickable { dialogDate = null },
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                title = { Text("$dialogDate") },
                text = {
                    Column {
                        dialogEntries.forEach { e ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenScheduled(e)
                                        dialogDate = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Leading affordance dot
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        )
                                )
                                Spacer(Modifier.width(8.dp))
                                // Reuse OrgText composable to render the org node consistently
                                OrgText(content = e.second)
                            }
                        }
                    }
                }
            )
        }
    }
}

enum class CalendarMode {
    Week, Month;

    fun next(date: LocalDate): LocalDate = when (this) {
        Week -> date.plus(7, DateTimeUnit.DAY)
        Month -> date.plus(1, DateTimeUnit.MONTH)
    }

    fun prev(date: LocalDate): LocalDate = when (this) {
        Week -> date.minus(7, DateTimeUnit.DAY)
        Month -> date.minus(1, DateTimeUnit.MONTH)
    }
}

@Composable
private fun WeekRow(
    anchorDate: LocalDate,
    today: LocalDate,
    scheduledByDate: Map<LocalDate, List<Pair<String, OrgNode>>>,
    onDateClick: (LocalDate) -> Unit
) {
    val start = anchorDate.startOfWeek()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0 until 7) {
            val d = start.plus(i, DateTimeUnit.DAY)
            val has = !scheduledByDate[d].isNullOrEmpty()
            Box(modifier = Modifier.weight(1f)) {
                DayCell(date = d, isToday = d == today, isOtherMonth = false, hasSchedule = has) {
                    if (has) onDateClick(d)
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    anchorDate: LocalDate,
    today: LocalDate,
    scheduledByDate: Map<LocalDate, List<Pair<String, OrgNode>>>,
    onDateClick: (LocalDate) -> Unit
) {
    val firstOfMonth = LocalDate(anchorDate.year, anchorDate.month, 1)
    val start = firstOfMonth.startOfWeek()
    Column(modifier = Modifier.fillMaxWidth()) {
        for (row in 0 until 6) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (col in 0 until 7) {
                    val idx = row * 7 + col
                    val date = start.plus(idx, DateTimeUnit.DAY)
                    val isOther = date.month != anchorDate.month
                    val hasSchedule = !scheduledByDate[date].isNullOrEmpty()
                    Box(modifier = Modifier.weight(1f)) {
                        DayCell(
                            date = date,
                            isToday = date == today,
                            isOtherMonth = isOther,
                            hasSchedule = hasSchedule
                        ) {
                            if (hasSchedule) onDateClick(date)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isOtherMonth: Boolean,
    hasSchedule: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val backgroundColor =
        if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
    val textColor = when {
        isOtherMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val clickableMod = if (hasSchedule) Modifier.clickable { onClick?.invoke() } else Modifier
    Column(
        modifier = Modifier
            .padding(2.dp)
            .then(clickableMod)
            .background(backgroundColor)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = date.day.toString(), color = textColor)
        if (hasSchedule) {
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
            )
        }
    }
}

private fun LocalDate.startOfWeek(): LocalDate {
    // Assume Monday as first day
    val dayIndex = (this.dayOfWeek.isoDayNumber - 1) // 0..6
    return this.minus(dayIndex, DateTimeUnit.DAY)
}


// -----------------------
// Previews
// -----------------------

private fun previewSampleScheduled(): Map<LocalDate, List<Pair<String, OrgNode>>> {
    // Fixed sample data focused on September 2025
    val date1 = LocalDate(2025, 9, 10)
    val date2 = LocalDate(2025, 9, 15)
    val date3 = LocalDate(2025, 9, 21)
    val date4 = LocalDate(2025, 9, 30)
    return mapOf(
        date1 to listOf(
            "sep-2025.org" to OrgPlanning("SCHEDULED", "<2025-09-10 Wed>")
        ),
        date2 to listOf(
            "sep-2025.org" to OrgPlanning("SCHEDULED", "<2025-09-15 Mon>")
        ),
        date3 to listOf(
            "next-actions.org" to OrgPlanning("SCHEDULED", "<2025-09-21 Sun>")
        ),
        date4 to listOf(
            "sep-2025.org" to OrgPlanning("SCHEDULED", "<2025-09-30 Tue>")
        )
    )
}

@Preview
@Composable
private fun Preview_Calendar_Month_NoDialog() {
    CalendarScreen(
        scheduledByDate = previewSampleScheduled(),
        initialMode = CalendarMode.Month,
        initialAnchorDate = LocalDate(2025, 9, 15)
    )
}

@Preview
@Composable
private fun Preview_Calendar_Week_NoDialog() {
    CalendarScreen(
        scheduledByDate = previewSampleScheduled(),
        initialMode = CalendarMode.Week,
        initialAnchorDate = LocalDate(2025, 9, 15)
    )
}

@Preview
@Composable
private fun Preview_Calendar_Month_WithDialog() {
    val dateWithEntries = LocalDate(2025, 9, 10)
    CalendarScreen(
        scheduledByDate = previewSampleScheduled(),
        initialMode = CalendarMode.Month,
        initialAnchorDate = LocalDate(2025, 9, 1),
        initialDialogDate = dateWithEntries
    )
}

@Preview
@Composable
private fun Preview_Calendar_Week_WithDialog() {
    val dateWithEntries = LocalDate(2025, 9, 21)
    CalendarScreen(
        scheduledByDate = previewSampleScheduled(),
        initialMode = CalendarMode.Week,
        initialAnchorDate = LocalDate(2025, 9, 21),
        initialDialogDate = dateWithEntries
    )
}
