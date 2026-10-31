package org.tasks.compose.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.tasks.PickedTask
import org.tasks.compose.components.SymbolIcon
import org.tasks.themes.TasksIcons
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.cancel
import tasks.kmp.generated.resources.existing_task_placeholder
import tasks.kmp.generated.resources.make_parent_task
import tasks.kmp.generated.resources.make_parent_task_panel_title
import tasks.kmp.generated.resources.make_parent_task_title

/**
 * The inline "move into another task" panel. One explainer title, then two
 * ways to pick a destination, in the shared [InlineActionPanel] container:
 *
 * - *Find existing task...* — a launcher field, not a text input. Tapping it
 *   opens the list pane's task search in pick mode (the list pane and the
 *   pick state live in App, which hands the pick back here as [pickedTask]);
 *   the field then shows the picked task's title, and tapping it again
 *   re-opens the search.
 * - *Create new task...* — a plain text field; accepting creates the task
 *   and nests this one under it.
 *
 * The two fields are mutually exclusive: once one has content the other is
 * disabled and its content cleared (a pick clears the new-title draft here;
 * typing a new title clears the pick through [onClearPicked]). The single
 * floppy commits whichever field has content.
 *
 * Deliberately not a dialog, and the user-facing words avoid "parent/child":
 * the app calls children *subtasks*, so the panel is phrased from that side.
 */
@Composable
fun MoveIntoTaskPanel(
    modifier: Modifier = Modifier,
    working: Boolean,
    keyboardController: SoftwareKeyboardController? = null,
    pickedTask: PickedTask?,
    onPickExistingClick: () -> Unit = {},
    onClearPicked: () -> Unit = {},
    onAcceptNew: (title: String) -> Unit,
    onAcceptExisting: (taskId: Long) -> Unit,
    onCancel: () -> Unit,
) {
    var newTitle by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Mutual exclusion, pick side: landing a pick clears any new-title draft,
    // so the floppy's destination is unambiguous.
    LaunchedEffect(pickedTask) {
        if (pickedTask != null) newTitle = ""
    }
    val canAccept = !working && (newTitle.isNotBlank() || pickedTask != null)

    fun accept() {
        if (newTitle.isNotBlank()) {
            onAcceptNew(newTitle.trim())
        } else {
            pickedTask?.let { onAcceptExisting(it.id) }
        }
    }

    Column(modifier = modifier) {
        // The explainer sits above the hairline container, not inside it: the
        // box then holds just the action content and reads as balanced, while
        // the line still introduces the same control as the delete panel's.
        Text(
            text = stringResource(Res.string.make_parent_task_panel_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        InlineActionPanel {
            Column {
                ExistingTaskLauncherField(
                    title = pickedTask?.title.orEmpty(),
                    enabled = !working && newTitle.isEmpty(),
                    onClick = onPickExistingClick,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = {
                        // Mutual exclusion, title side: starting to type a new
                        // task clears the existing-task pick.
                        if (pickedTask != null && it.isNotEmpty()) onClearPicked()
                        newTitle = it
                    },
                    // The field is an item of the edit pane: the pane's card
                    // radius and the app's hairline (outlineVariant) line.
                    shape = RoundedCornerShape(InlineActionPanelRadius),
                    colors = TextFieldDefaults.colors(
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.key == Key.Enter -> {
                                    if (canAccept) {
                                        keyboardController?.hide()
                                        accept()
                                    }
                                    true
                                }
                                event.key == Key.Escape -> {
                                    onCancel()
                                    true
                                }
                                else -> false
                            }
                        },
                    placeholder = { Text(stringResource(Res.string.make_parent_task_title)) },
                    singleLine = true,
                    // Stays enabled even with a pick in the other field: typing the first
                    // character is what clears the pick (onClearPicked), so disabling it
                    // here would make "switch to create-new" impossible.
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (canAccept) accept()
                    }),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel, enabled = !working) {
                        SymbolIcon(
                            name = TasksIcons.CLEAR,
                            contentDescription = stringResource(Res.string.cancel),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            keyboardController?.hide()
                            accept()
                        },
                        enabled = canAccept,
                    ) {
                        SymbolIcon(
                            name = TasksIcons.SAVE,
                            contentDescription = stringResource(Res.string.make_parent_task),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The "find existing task" field: a read-only field-styled box that launches
 * the list pane's pick search. Styled to sit visually beside the Outlined
 * TextField below it (same radius, hairline and content box).
 */
@Composable
private fun ExistingTaskLauncherField(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(InlineActionPanelRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(Color.Transparent)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title.ifEmpty { stringResource(Res.string.existing_task_placeholder) },
            style = MaterialTheme.typography.bodyLarge,
            color = if (title.isEmpty()) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
