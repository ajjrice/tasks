package org.tasks.compose.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tasks.PickedTask

@OptIn(ExperimentalTestApi::class)
class MoveIntoTaskPanelTest {

    private val acceptedNew = mutableListOf<String>()
    private val acceptedExisting = mutableListOf<Long>()
    private var cancelled = 0
    private var clearedPicked = 0
    private var pickClicked = 0

    @Composable
    private fun panel(
        working: Boolean = false,
        pickedTask: PickedTask? = null,
    ) {
        MoveIntoTaskPanel(
            working = working,
            pickedTask = pickedTask,
            onPickExistingClick = { pickClicked++ },
            onClearPicked = { clearedPicked++ },
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
    fun aPickEnablesAcceptAndRoutesToExisting() = runComposeUiTest {
        setContent { panel(pickedTask = PickedTask(id = 42, title = "Big project")) }
        onNodeWithText("Big project", substring = false).assertExists()
        // The pick shows in the field and the floppy is live immediately.
        onNodeWithContentDescription("Move into new task").assertIsEnabled()
        onNodeWithContentDescription("Move into new task").performClick()
        assertEquals(listOf(42L), acceptedExisting)
    }

    @Test
    fun typingATitleClearsThePickAndDisablesTheLauncher() = runComposeUiTest {
        setContent { panel(pickedTask = PickedTask(id = 42, title = "Big project")) }
        onNodeWithText("Create new task...", substring = false).performTextInput("New one")
        assertEquals(1, clearedPicked)
        // The title now owns the destination: accepting routes to create, not the pick.
        onNodeWithContentDescription("Move into new task").performClick()
        assertEquals(listOf("New one"), acceptedNew)
        assertEquals(emptyList<Long>(), acceptedExisting)
    }

    @Test
    fun thePickFieldOpensPickSearchWhenTapped() = runComposeUiTest {
        setContent { panel() }
        onNodeWithText("Find existing task...", substring = false).performClick()
        assertEquals(1, pickClicked)
    }

    @Test
    fun enterAcceptsAndEscapeCancels() = runComposeUiTest {
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

        onNodeWithText("Create new task...", substring = false).assertIsNotEnabled()
        onNodeWithContentDescription("Move into new task").assertIsNotEnabled()
        // Nothing is left to cancel while create/nest is in flight; the op is a fast DB
        // write and the button re-enables when the working flag clears.
        onNodeWithContentDescription("Cancel").assertIsNotEnabled()
    }

    @Test
    fun thePanelRendersExactlyOneEditableInput() = runComposeUiTest {
        setContent { panel() }
        // The existing-task field is a launcher (a read-only styled box), so the
        // only editable input in the panel is the new-task title.
        assertEquals(1, onAllNodes(isEditable()).fetchSemanticsNodes().size)
    }
}
