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
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(lastSynced) {
        while (true) {
            now = Clock.System.now()
            delay(AGE_TICK)
        }
    }
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
                                syncAgeLabel(lastSynced!!, now),
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

private fun syncDescription(state: SyncState, unreachable: String?, lastSynced: Instant?, now: Instant): String {
    val last = lastSynced?.let { "Last synced ${syncAgeSentence(it, now)}, at ${clockTime(it)}." }
    return when (state) {
        SyncState.SYNCING -> "Syncing with the server…"
        SyncState.OFFLINE -> listOfNotNull(unreachable, last).joinToString(" ")
        SyncState.SYNCED -> "$last Tap to sync now."
        SyncState.NEVER -> "Not synced yet. Tap to sync now."
    }
}

/** The age of the last sync as the cloud shows it: `now`, `5m`, `3h`, `2d`. */
internal fun syncAgeLabel(lastSynced: Instant, now: Instant): String {
    val age = (now - lastSynced).coerceAtLeast(Duration.ZERO)
    return when {
        age < 1.minutes -> "now"
        age < 1.hours -> "${age.inWholeMinutes}m"
        age < 1.days -> "${age.inWholeHours}h"
        else -> "${age.inWholeDays}d"
    }
}

/** The same in words, for the tooltip and screen readers: `just now`, `5 min ago`, `3 h ago`. */
internal fun syncAgeSentence(lastSynced: Instant, now: Instant): String {
    val age = (now - lastSynced).coerceAtLeast(Duration.ZERO)
    return when {
        age < 1.minutes -> "just now"
        age < 1.hours -> "${age.inWholeMinutes} min ago"
        age < 1.days -> "${age.inWholeHours} h ago"
        age.inWholeDays == 1L -> "a day ago"
        else -> "${age.inWholeDays} days ago"
    }
}

private fun clockTime(at: Instant): String {
    val time = at.toLocalDateTime(TimeZone.currentSystemDefault()).time
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
