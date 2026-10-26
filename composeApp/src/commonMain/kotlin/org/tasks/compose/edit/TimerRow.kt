package org.tasks.compose.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.stringResource
import org.tasks.compose.components.TasksIcon
import org.tasks.themes.TasksIcons
import org.tasks.themes.accentSurface
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.timer

private val RowVerticalPadding = 16.dp
private val IconChipSize = 40.dp
private val IconChipRadius = 10.dp
private val IconTextGap = 16.dp
private val ButtonGap = 8.dp
private val TickIntervalMs = 1_000L
private val MillisPerSecond = 1_000L

/**
 * Live timer row on the task edit screen. Shows the elapsed time, ticking
 * once per second while a session is running, with three controls:
 * play starts a session, tick (check) stops and banks it, cross stops and
 * banks without acknowledgment. There is deliberately no pause: stopping
 * means stopping.
 */
@Composable
fun TimerRow(
    timerStart: Long,
    elapsedSeconds: Int,
    onPlay: () -> Unit,
    onTick: () -> Unit,
    onCross: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = timerStart > 0
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(timerStart) {
        while (true) {
            now = Clock.System.now().toEpochMilliseconds()
            if (!running) {
                break
            }
            delay(TickIntervalMs)
        }
    }
    val displayedSeconds = if (running && now > 0) {
        elapsedSeconds + ((now - timerStart) / MillisPerSecond).toInt()
    } else {
        elapsedSeconds
    }
    TaskEditCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = TaskEditCardHorizontalPadding,
                vertical = RowVerticalPadding,
            ),
        ) {
            IconChip(icon = TasksIcons.TIMER, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(
                modifier = Modifier.padding(start = IconTextGap),
            ) {
                TaskEditSectionLabel(text = stringResource(Res.string.timer))
                val hasTime = displayedSeconds > 0
                Text(
                    text = DurationFormat.format(displayedSeconds),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color =
                        if (hasTime) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TimerButton(icon = TasksIcons.PLAY_ARROW, enabled = !running, onClick = onPlay)
            TimerButton(icon = TasksIcons.CHECK, enabled = running, onClick = onTick)
            TimerButton(icon = TasksIcons.CLEAR, enabled = running, onClick = onCross)
        }
    }
}

@Composable
private fun IconChip(icon: String, tint: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(IconChipSize)
            .background(
                color = accentSurface(tint),
                shape = RoundedCornerShape(IconChipRadius),
            ),
    ) {
        TasksIcon(label = icon, tint = tint)
    }
}

@Composable
private fun TimerButton(
    icon: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(40.dp)
            .padding(ButtonGap),
    ) {
        TasksIcon(label = icon)
    }
}
