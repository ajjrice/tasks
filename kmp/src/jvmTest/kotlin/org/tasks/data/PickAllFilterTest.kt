package org.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tasks.TaskListTest
import org.tasks.filters.MyTasksFilter
import org.tasks.filters.PickAllFilter
import org.tasks.filters.PickSearchFilter

/**
 * The pick's find field widens the pane to [PickAllFilter] (every active
 * task at every depth - the same universe the FAB search already proves
 * works). The My Tasks scope cannot serve that role: its WHERE carries
 * PARENT = 0, and the pick search ANDs the LIKE onto that same row-level
 * clause, so only top-level rows can ever match.
 */
class PickAllFilterTest : TaskListTest() {
    override val filter = PickAllFilter(title = "All Tasks")

    @Test
    fun theMyTasksScopeCannotFindANestedTaskBySearch() = runBlocking {
        val officeMove = newTask("Office move")
        newTask("P/ Logistics", parent = officeMove.id)

        val pick = PickSearchFilter(
            title = "My Tasks",
            base = MyTasksFilter(title = "My Tasks"),
            query = "logistics",
        )
        assertEquals(
            "the search ANDs onto the PARENT = 0 row-level scope; no top-level row matches",
            emptyList<String>(),
            rows(filter = pick).map { it.title },
        )
    }

    @Test
    fun theWidenScopeFindsTheSameNestedTask() = runBlocking {
        val officeMove = newTask("Office move")
        newTask("P/ Logistics", parent = officeMove.id)

        val pick = PickSearchFilter(
            title = "All Tasks",
            base = PickAllFilter(title = "All Tasks"),
            query = "logistics",
        )
        assertEquals(
            listOf("P/ Logistics"),
            rows(filter = pick).map { it.title },
        )
    }
}
