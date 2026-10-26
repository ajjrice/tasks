package org.tasks.compose.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.tasks.compose.components.TasksIcon
import org.tasks.themes.TasksIcons
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.cancel
import tasks.kmp.generated.resources.estimated_time
import tasks.kmp.generated.resources.invalid_duration
import tasks.kmp.generated.resources.no_estimate
import tasks.kmp.generated.resources.ok
import tasks.kmp.generated.resources.reminder_hours
import tasks.kmp.generated.resources.reminder_minutes

/**
 * Estimate row on the task edit screen. Shows the formatted estimate (or a
 * greyed "Unset") and edits it through a dialog with separate hour and
 * minute steppers plus a free-text field accepting formats like `1h30`.
 */
@Composable
fun EstimateRow(
    estimatedSeconds: Int,
    onEstimateChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEditor by remember { mutableStateOf(false) }
    val hasEstimate = estimatedSeconds > 0
    TaskEditCardRow(
        value = if (hasEstimate) {
            DurationFormat.format(estimatedSeconds)
        } else {
            stringResource(Res.string.no_estimate)
        },
        valueColor =
        if (hasEstimate) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        title = stringResource(Res.string.estimated_time),
        icon = TasksIcons.TIMER,
        onClick = { showEditor = true },
        modifier = modifier,
    )
    if (showEditor) {
        EstimateEditorDialog(
            initialSeconds = estimatedSeconds,
            onApply = {
                onEstimateChanged(it)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EstimateEditorDialog(
    initialSeconds: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var seconds by remember { mutableIntStateOf(initialSeconds) }
    var input by remember {
        mutableStateOf(if (initialSeconds > 0) DurationFormat.format(initialSeconds) else "")
    }
    var invalid by remember { mutableStateOf(false) }

    fun nudge(deltaSeconds: Int) {
        seconds = (seconds + deltaSeconds).coerceAtLeast(0)
        input = if (seconds > 0) DurationFormat.format(seconds) else ""
        invalid = false
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.estimated_time),
                    style = MaterialTheme.typography.titleMedium,
                )
                StepperRow(
                    label = pluralStringResource(Res.plurals.reminder_hours, 1),
                    value = seconds / HOUR_STEP_SECONDS,
                    onMinus = { nudge(-HOUR_STEP_SECONDS) },
                    onPlus = { nudge(HOUR_STEP_SECONDS) },
                )
                StepperRow(
                    label = pluralStringResource(Res.plurals.reminder_minutes, 1),
                    value = (seconds % HOUR_STEP_SECONDS) / MINUTE_STEP_SECONDS,
                    onMinus = { nudge(-MINUTE_STEP_SECONDS) },
                    onPlus = { nudge(MINUTE_STEP_SECONDS) },
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(text = "1h30, 90, 45m, 1:30") },
                    singleLine = true,
                    isError = invalid,
                    supportingText = {
                        if (invalid) {
                            Text(text = stringResource(Res.string.invalid_duration))
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Text(
                        text = stringResource(Res.string.ok),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable {
                                val parsed = DurationFormat.parse(input)
                                if (parsed == null) {
                                    invalid = true
                                } else {
                                    onApply(parsed)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        StepperButton(icon = TasksIcons.REMOVE_CIRCLE_OUTLINE, onClick = onMinus)
        Text(
            text = value.toString(),
            modifier = Modifier
                .width(48.dp)
                .padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        StepperButton(icon = TasksIcons.ADD, onClick = onPlus)
    }
}

@Composable
private fun StepperButton(
    icon: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        TasksIcon(label = icon)
    }
}

private const val HOUR_STEP_SECONDS = 3600
private const val MINUTE_STEP_SECONDS = 300
