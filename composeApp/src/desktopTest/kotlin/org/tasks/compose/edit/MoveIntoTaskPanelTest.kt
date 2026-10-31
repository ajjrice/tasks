package org.tasks.compose.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tasks.PickedTask

@OptIn(ExperimentalTestApi::class)
class MoveIntoTaskPanelTest {

    private val acceptedNew = mutableListOf<String>()
    private val acceptedExisting = mutableListOf<Long>()
    private val queries = mutableListOf<String>()
    private var cancelled = 0
    private var clearedPicked = 0
    private var pickOpened = 0

    @Composable
    private fun panel(
        working: Boolean = false,
        pickedTask: PickedTask? = null,
    ) {
        // Mirror the real wiring: a pick cleared through the panel is gone for
        // subsequent keystrokes, so onClearPicked drops the pick this panel sees.
        var effectivePick by remember(pickedTask) { mutableStateOf(pickedTask) }
        MoveIntoTaskPanel(
            working = working,
            pickedTask = effectivePick,
            onPickExistingOpen = { pickOpened++ },
            onFindQueryChange = { queries.add(it) },
            onClearPicked = {
                clearedPicked++
                effectivePick = null
            },
            onAcceptNew = { acceptedNew.add(it) },
            onAcceptExisting = { acceptedExisting.add(it) },
            onCancel = { cancelled++ },
        )
    }

    @Test
    fun bothFieldsRenderAndAcceptIsDisabledUntilOneHasContent() = runComposeUiTest {
        setContent { panel() }
        onNodeWithText("Find existing task...", substring = false).assertExists()
        onNodeWithText("Create new task...", substring = false).assertExists()
        onNodeWithContentDescription("Move into new task").assertIsNotEnabled()

        onNodeWithText("Create new task...", substring = false).performTextInput("Project")
        onNodeWithContentDescription("Move into new task").assertIsEnabled()
        onNodeWithContentDescription("Move into new task").performClick()
        assertEquals(listOf("Project"), acceptedNew)
    }

    @Test
    fun theFindFieldIsALiveSearchInput() = runComposeUiTest {
        setContent { panel() }
        onNodeWithText("Find existing task...", substring = false).performTextInput("proj")
        // Typing makes sure pick mode is on and pushes the live query to the
        // list pane.
        assertEquals(1, pickOpened)
        assertEquals(listOf("proj"), queries)

        // While the find field is active the new-title field gives way.
        onNodeWithText("Create new task...", substring = false).assertIsNotEnabled()
    }

    @Test
    fun clearingTheFindFieldReEnablesTheTitleField() = runComposeUiTest {
        setContent { panel() }
        onNodeWithText("Find existing task...", substring = false).performTextInput("proj")
        onNodeWithText("Create new task...", substring = false).assertIsNotEnabled()

        onNodeWithText("proj", substring = false).performTextClearance()
        onNodeWithText("Create new task...", substring = false).assertIsEnabled()
    }

    @Test
    fun aPickEnablesAcceptAndRoutesToExisting() = runComposeUiTest {
        setContent { panel(pickedTask = PickedTask(id = 42, title = "Big project")) }
        // The pick shows in the find field and the floppy is live immediately.
        onNodeWithText("Big project", substring = false).assertExists()
        onNodeWithContentDescription("Move into new task").assertIsEnabled()
        // A pick keeps the new-title field disabled as well.
        onNodeWithText("Create new task...", substring = false).assertIsNotEnabled()
        onNodeWithContentDescription("Move into new task").performClick()
        assertEquals(listOf(42L), acceptedExisting)
    }

    @Test
    fun typingInTheFindFieldClearsThePickAndResumesSearching() = runComposeUiTest {
        setContent { panel(pickedTask = PickedTask(id = 42, title = "Big project")) }
        onNodeWithText("Big project", substring = false).performTextClearance()
        // The cleared, focused field no longer carries a text node to select on
        // (the placeholder is not matchable while the field is focused), so the
        // field itself is addressed through its text action.
        onAllNodes(hasSetTextAction())[0].performTextInput("New search")
        assertEquals(1, clearedPicked)
        // The clear and the new query both reach the list pane.
        assertEquals(listOf("", "New search"), queries)
        // The pick is gone: accepting now has nothing to route to.
        onNodeWithContentDescription("Move into new task").assertIsNotEnabled()
    }

    @Test
    fun aLandedPickClearsTheNewTitleDraft() = runComposeUiTest {
        var pick by mutableStateOf<PickedTask?>(null)
        setContent { panel(pickedTask = pick) }
        // Type a draft while no pick is in flight...
        onNodeWithText("Create new task...", substring = false).performTextInput("Draft")
        // ...then a pick lands from the list pane.
        runOnIdle { pick = PickedTask(id = 42, title = "Big project") }
        onNodeWithText("Big project", substring = false).assertExists()
        // The draft is gone and the title field is disabled while the pick shows.
        onNodeWithText("Draft", substring = false).assertDoesNotExist()
        onNodeWithText("Create new task...", substring = false).assertIsNotEnabled()
    }

    @Test
    fun enterAcceptsAPickFromTheFindFieldAndEscapeCancels() = runComposeUiTest {
        setContent { panel(pickedTask = PickedTask(id = 42, title = "Big project")) }
        // The field's declared IME action is Done; driving it directly instead of
        // synthesising an Enter key avoids the test dispatcher's key-state clash
        // on a freshly auto-focused field.
        onNodeWithText("Big project", substring = false).performImeAction()
        assertEquals(listOf(42L), acceptedExisting)

        setContent { panel() }
        onNodeWithText("Create new task...", substring = false)
            .performKeyInput { keyDown(Key.Escape) }
        assertEquals(1, cancelled)

        onNodeWithText("Create new task...", substring = false).performTextInput("Project")
        onNodeWithText("Project").performKeyInput { keyDown(Key.Enter) }
        assertEquals(listOf("Project"), acceptedNew)
    }

    @Test
    fun aWorkingPanelTakesNoInput() = runComposeUiTest {
        setContent { panel(working = true) }

        onNodeWithText("Find existing task...", substring = false).assertIsNotEnabled()
        onNodeWithText("Create new task...", substring = false).assertIsNotEnabled()
        onNodeWithContentDescription("Move into new task").assertIsNotEnabled()
        // Nothing is left to cancel while create/nest is in flight; the op is a fast DB
        // write and the button re-enables when the working flag clears.
        onNodeWithContentDescription("Cancel").assertIsNotEnabled()
    }

    @Test
    fun thePanelRendersExactlyTwoEditableInputs() = runComposeUiTest {
        setContent { panel() }
        // Both fields are real text inputs: the find field is the live search,
        // the title field the create-new destination.
        assertEquals(2, onAllNodes(isEditable()).fetchSemanticsNodes().size)
    }
}
