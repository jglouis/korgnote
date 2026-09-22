package com.korgnote.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.complete.CompletionItem
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The suggestions, as a strip of chips under the field being edited.
 *
 * A strip rather than a floating popup, on every platform: the popup the design sketched suits a
 * desktop with a hardware keyboard, but it fights the IME and the selection handles on a phone,
 * which is where these notes are mostly written. One presentation also means one thing to keep
 * working. The keyboard path (Tab to accept, arrows to move, Esc to dismiss) is handled by the
 * field itself, so a desktop user never has to reach for the chips.
 */
@Composable
fun CompletionStrip(
    items: List<CompletionItem>,
    selectedIndex: Int,
    onAccept: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val listState = rememberLazyListState()
    // Keyboard selection can walk past the edge of the strip; keep the chosen chip in sight.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in items.indices) listState.animateScrollToItem(selectedIndex)
    }

    LazyRow(
        modifier = modifier,
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(items) { index, item ->
            val selected = index == selectedIndex
            AssistChip(
                onClick = { onAccept(item) },
                label = { Text(item.label, style = MaterialTheme.typography.labelLarge) },
                colors = if (selected) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
            )
        }
    }
}

/**
 * The date picker behind the "Pick a date…" suggestion, for the dates the shortcuts do not cover.
 *
 * The picker hands back UTC midnight for the day that was tapped, so the conversion reads it in
 * UTC: reading it in the device's zone would land on the previous day for anyone west of Greenwich.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrgDatePickerDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    onPick(if (millis == null) initial else millis.toUtcDate())
                }
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

private fun Long.toUtcDate(): LocalDate =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC).date
