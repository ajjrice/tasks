package org.tasks.compose.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
 * - *Find existing task...* — a live search field. The moment the panel
 *   opens, the list pane is already in pick mode for the edited task (row
 *   taps land the pick instead of opening the task, and the edited task's
 *   subtree is excluded before the first keystroke), so a candidate can be
 *   chosen by clicking straight in the list. Typing into the field (via
 *   [onFindQueryChange]) live-filters those rows, WITHIN the list currently
 *   selected in the drawer: switching lists there narrows the search to that
 *   list's scope, and My Tasks widens it back out. Tapping a result row (or
 *   clicking any row) lands the pick here: the field then shows the picked
 *   task's title and the floppy commits it; typing into it again clears the
 *   pick and resumes searching.
 * - *Create new task...* — a plain text field; accepting creates the task
 *   and nests this one under it.
 *
 * The two fields are mutually exclusive: while the find field has content or
 * a pick, the new-title field is disabled (and a landed pick clears any
 * new-title draft). Clear the find field to switch to create-new. The single
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
    onPickExistingOpen: () -> Unit = {},
    onFindQueryChange: (String) -> Unit = {},
    onClearPicked: () -> Unit = {},
    onAcceptNew: (title: String) -> Unit,
    onAcceptExisting: (taskId: Long) -> Unit,
    onCancel: () -> Unit,
) {
    var newTitle by rememberSaveable { mutableStateOf("") }
    // The find field's text. While a pick is shown the field displays the
    // picked title instead, and editing it clears the pick (see below).
    var findQuery by rememberSaveable { mutableStateOf("") }
    val findFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { findFocusRequester.requestFocus() }
    // Mutual exclusion, pick side: landing a pick clears any new-title draft,
    // so the floppy's destination is unambiguous.
    LaunchedEffect(pickedTask) {
        if (pickedTask != null) newTitle = ""
    }
    // The find field is "active" while it holds a query or a pick: that is
    // exactly when the new-title field gives way.
    val findActive = pickedTask != null || findQuery.isNotEmpty()
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
                OutlinedTextField(
                    // A pick shows the picked task's title; otherwise the
                    // query being typed (or the placeholder).
                    value = pickedTask?.title ?: findQuery,
                    onValueChange = { newText ->
                        // Editing a picked title starts a new search: the
                        // first character clears the pick.
                        if (pickedTask != null) onClearPicked()
                        // Every keystroke makes sure pick mode is on in the
                        // list pane (idempotent) and pushes the live query.
                        onPickExistingOpen()
                        findQuery = newText
                        onFindQueryChange(newText)
                    },
                    // The field is an item of the edit pane: the pane's card
                    // radius and the app's hairline (outlineVariant) line.
                    shape = RoundedCornerShape(InlineActionPanelRadius),
                    colors = TextFieldDefaults.colors(
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(findFocusRequester)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.key == Key.Enter -> {
                                    if (canAccept && pickedTask != null) {
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
                    placeholder = { Text(stringResource(Res.string.existing_task_placeholder)) },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (canAccept) accept()
                    }),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    shape = RoundedCornerShape(InlineActionPanelRadius),
                    colors = TextFieldDefaults.colors(
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
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
                    // Mutually exclusive with the find field: while the find
                    // field holds a query or a pick, this one gives way.
                    // Clear the find field to switch back to create-new.
                    enabled = !working && !findActive,
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
