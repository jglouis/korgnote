package com.korgnote.app

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
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    scheduledByDate: Map<LocalDate, List<ScheduledEntry>> = emptyMap(),
    onOpenScheduled: (ScheduledEntry) -> Unit = {}
) {
    var today by remember {
        mutableStateOf(
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        )
    }
    var anchorDate by remember { mutableStateOf(today) }
    var mode by remember { mutableStateOf(CalendarMode.Month) }

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
                        .clickable { anchorDate = today },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "Previous",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { anchorDate = mode.prev(anchorDate) }
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Next",
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { anchorDate = mode.next(anchorDate) }
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
                onClick = { mode = CalendarMode.Week },
                label = { Text("Week") }
            )
            FilterChip(
                selected = mode == CalendarMode.Month,
                onClick = { mode = CalendarMode.Month },
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

        var dialogDate by remember { mutableStateOf<LocalDate?>(null) }
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
                                anchorDate = mode.next(anchorDate)
                                triggered = true
                            } else if (totalDx >= threshold) {
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
            when (mode) {
                CalendarMode.Month -> MonthGrid(
                    anchorDate = anchorDate,
                    today = today,
                    scheduledByDate = scheduledByDate
                ) { d ->
                    if (!scheduledByDate[d].isNullOrEmpty()) dialogDate = d
                }

                CalendarMode.Week -> WeekRow(
                    anchorDate = anchorDate,
                    today = today,
                    scheduledByDate = scheduledByDate
                ) { d ->
                    if (!scheduledByDate[d].isNullOrEmpty()) dialogDate = d
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
                            // Build annotated string with same highlighting as main content
                            val annotated = buildOrgAnnotatedString("• ${e.title} (${e.fileName})")
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
                                        .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = annotated,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
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
    scheduledByDate: Map<LocalDate, List<ScheduledEntry>>,
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
    scheduledByDate: Map<LocalDate, List<ScheduledEntry>>,
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
                    val d = start.plus(idx, DateTimeUnit.DAY)
                    val isOther = d.month != anchorDate.month
                    val has = !scheduledByDate[d].isNullOrEmpty()
                    Box(modifier = Modifier.weight(1f)) {
                        DayCell(
                            date = d,
                            isToday = d == today,
                            isOtherMonth = isOther,
                            hasSchedule = has
                        ) {
                            if (has) onDateClick(d)
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
    val bg =
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
            .background(bg)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = date.dayOfMonth.toString(), color = textColor)
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
