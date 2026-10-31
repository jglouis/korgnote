package com.korgnote.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.notes_sync_age_days
import korgnote.composeapp.generated.resources.notes_sync_age_hours
import korgnote.composeapp.generated.resources.notes_sync_age_minutes
import korgnote.composeapp.generated.resources.notes_sync_age_now
import korgnote.composeapp.generated.resources.notes_sync_ago_day
import korgnote.composeapp.generated.resources.notes_sync_ago_days
import korgnote.composeapp.generated.resources.notes_sync_ago_hours
import korgnote.composeapp.generated.resources.notes_sync_ago_minutes
import korgnote.composeapp.generated.resources.notes_sync_ago_now
import korgnote.composeapp.generated.resources.notes_sync_last
import korgnote.composeapp.generated.resources.notes_sync_never
import korgnote.composeapp.generated.resources.notes_sync_running
import korgnote.composeapp.generated.resources.notes_sync_tap
import korgnote.composeapp.generated.resources.settings_sync_never
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * The server's state, for the top bar, as one cloud: turning arrows while a sync runs, a ticked
 * cloud with how long ago it last got through once it has, a crossed-out one while the server does
 * not answer. A tap syncs now; a long press says when the last sync was, and why the last attempt
 * failed when it did.
 *
 * Quiet on purpose: offline, notes still open and edits are kept, so this is a state to glance at,
 * not an error to read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerStatusIcon(
    unreachable: String?,
    syncing: Boolean,
    lastSynced: Instant?,
    onRetry: () -> Unit,
) {
    // A save's sync takes a blink; held a moment, the arrows are seen to turn rather than flicker.
    var spinning by remember { mutableStateOf(syncing) }
    LaunchedEffect(syncing) {
        if (syncing) {
            spinning = true
        } else if (spinning) {
            delay(MIN_SPIN)
            spinning = false
        }
    }
    // The age under the cloud counts on by itself, without a sync to recompose it.
    val now = rememberNow(lastSynced)
    val state = when {
        spinning -> SyncState.SYNCING
        unreachable != null -> SyncState.OFFLINE
        lastSynced != null -> SyncState.SYNCED
        else -> SyncState.NEVER
    }
    val description = syncDescription(state, unreachable, lastSynced, now)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onRetry) {
            AnimatedContent(
                targetState = state,
                // The tick lands with a small pop, so the end of a sync is seen as well as its run.
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() },
                label = "sync state",
            ) { shown ->
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    when (shown) {
                        SyncState.SYNCING -> SpinningSync(description)
                        SyncState.OFFLINE -> Icon(Icons.Filled.CloudOff, contentDescription = description)
                        SyncState.NEVER -> Icon(Icons.Outlined.CloudQueue, contentDescription = description)
                        SyncState.SYNCED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.CloudDone, contentDescription = description, modifier = Modifier.size(20.dp))
                            Text(
                                syncAgeOf(lastSynced!!, now).label(),
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What [ServerStatusIcon] says on a long press, written out, for Settings: that is where one goes to
 * see whether the notes are in step with the server, so the answer should not need a gesture.
 *
 * A sync under way is only mentioned before the first one has got through: a save's push takes a
 * blink, and the line would flicker with every edit.
 */
@Composable
fun SyncStatusText(unreachable: String?, syncing: Boolean, lastSynced: Instant?, modifier: Modifier = Modifier) {
    val now = rememberNow(lastSynced)
    val last = lastSynced?.let { stringResource(Res.string.notes_sync_last, syncAgeOf(it, now).sentence(), clockTime(it)) }
    val text = when {
        unreachable != null && !syncing -> listOfNotNull(unreachable, last).joinToString(" ")
        last != null -> last
        syncing -> stringResource(Res.string.notes_sync_running)
        else -> stringResource(Res.string.settings_sync_never)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** The time now, brought up to date every [AGE_TICK] and whenever [lastSynced] changes. */
@Composable
private fun rememberNow(lastSynced: Instant?): Instant {
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(lastSynced) {
        while (true) {
            now = Clock.System.now()
            delay(AGE_TICK)
        }
    }
    return now
}

private enum class SyncState { SYNCING, OFFLINE, SYNCED, NEVER }

@Composable
private fun SpinningSync(description: String) {
    val angle by rememberInfiniteTransition(label = "sync spin").animateFloat(
        initialValue = 0f,
        // Counter-clockwise, the way the arrows of the icon point.
        targetValue = -360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing), RepeatMode.Restart),
        label = "sync angle",
    )
    Icon(
        Icons.Filled.Sync,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.rotate(angle),
    )
}

/** The shortest a sync is shown turning, however quickly it ended. */
private val MIN_SPIN = 700.milliseconds

/** How often the age under the cloud is brought up to date. */
private val AGE_TICK = 20.seconds

@Composable
private fun syncDescription(state: SyncState, unreachable: String?, lastSynced: Instant?, now: Instant): String {
    val last = lastSynced?.let { stringResource(Res.string.notes_sync_last, syncAgeOf(it, now).sentence(), clockTime(it)) }
    return when (state) {
        SyncState.SYNCING -> stringResource(Res.string.notes_sync_running)
        SyncState.OFFLINE -> listOfNotNull(unreachable, last).joinToString(" ")
        SyncState.SYNCED -> "$last ${stringResource(Res.string.notes_sync_tap)}"
        SyncState.NEVER -> stringResource(Res.string.notes_sync_never)
    }
}

/** How long ago the last sync was, in the largest whole unit it has reached. */
internal data class SyncAge(val span: Span, val count: Long) {
    enum class Span { NOW, MINUTES, HOURS, DAYS }
}

internal fun syncAgeOf(lastSynced: Instant, now: Instant): SyncAge {
    val age = (now - lastSynced).coerceAtLeast(Duration.ZERO)
    return when {
        age < 1.minutes -> SyncAge(SyncAge.Span.NOW, 0)
        age < 1.hours -> SyncAge(SyncAge.Span.MINUTES, age.inWholeMinutes)
        age < 1.days -> SyncAge(SyncAge.Span.HOURS, age.inWholeHours)
        else -> SyncAge(SyncAge.Span.DAYS, age.inWholeDays)
    }
}

/** The age as the cloud shows it: `now`, `5m`, `3h`, `2d`. */
@Composable
private fun SyncAge.label(): String = when (span) {
    SyncAge.Span.NOW -> stringResource(Res.string.notes_sync_age_now)
    SyncAge.Span.MINUTES -> stringResource(Res.string.notes_sync_age_minutes, count)
    SyncAge.Span.HOURS -> stringResource(Res.string.notes_sync_age_hours, count)
    SyncAge.Span.DAYS -> stringResource(Res.string.notes_sync_age_days, count)
}

/** The same in words, for the tooltip and screen readers: `just now`, `5 min ago`, `3 h ago`. */
@Composable
private fun SyncAge.sentence(): String = when (span) {
    SyncAge.Span.NOW -> stringResource(Res.string.notes_sync_ago_now)
    SyncAge.Span.MINUTES -> stringResource(Res.string.notes_sync_ago_minutes, count)
    SyncAge.Span.HOURS -> stringResource(Res.string.notes_sync_ago_hours, count)
    SyncAge.Span.DAYS -> if (count == 1L) stringResource(Res.string.notes_sync_ago_day) else stringResource(Res.string.notes_sync_ago_days, count)
}

/** [SyncAge.label] in English. */
internal fun syncAgeLabel(lastSynced: Instant, now: Instant): String {
    val age = syncAgeOf(lastSynced, now)
    return when (age.span) {
        SyncAge.Span.NOW -> "now"
        SyncAge.Span.MINUTES -> "${age.count}m"
        SyncAge.Span.HOURS -> "${age.count}h"
        SyncAge.Span.DAYS -> "${age.count}d"
    }
}

/** [SyncAge.sentence] in English. */
internal fun syncAgeSentence(lastSynced: Instant, now: Instant): String {
    val age = syncAgeOf(lastSynced, now)
    return when (age.span) {
        SyncAge.Span.NOW -> "just now"
        SyncAge.Span.MINUTES -> "${age.count} min ago"
        SyncAge.Span.HOURS -> "${age.count} h ago"
        SyncAge.Span.DAYS -> if (age.count == 1L) "a day ago" else "${age.count} days ago"
    }
}

private fun clockTime(at: Instant): String {
    val time = at.toLocalDateTime(TimeZone.currentSystemDefault()).time
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
