package org.tasks.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.tasks.InMemoryDataStore
import org.tasks.data.dao.TaskDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.filters.CaldavFilter
import org.tasks.filters.SearchFilter
import org.tasks.preferences.DataStoreQueryPreferences
import org.tasks.preferences.TasksPreferences

/**
 * Pins the pick-parent contract in [TaskListViewModel.queryFilterFor] that the
 * edit pane's "move into another task" panel relies on: while pick mode is
 * active the list shows a search over ALL tasks with the picked-into task and
 * its subtree excluded - even before the user has typed anything into the
 * panel's find field (a blank query in pick mode must not fall through to
 * "My Tasks" or the selected filter, or the subtree would be visible as pick
 * candidates).
 */
class TaskListViewModelPickParentTest {
    companion object {
        const val TEST_SEARCH_QUERY = "blue whale"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun caldavFilter() = CaldavFilter(
        calendar = CaldavCalendar(uuid = "cal-1", account = "acct-1"),
        account = CaldavAccount(uuid = "acct-1"),
    )

    private suspend fun viewModel(): TaskListViewModel {
        // A no-op child query: the exclusion set is then just the picked task
        // itself, which is all these tests care about. fetchTasks is stubbed the
        // same way as in the search-restore test: state changes kick the VM's
        // query flow, which must not NPE on an unstubbed fetch.
        val taskDao: TaskDao = mock {
            on { fetchTasks(any()) } doAnswer { emptyList() }
            on { getChildren(any<List<Long>>()) } doAnswer { emptyList() }
        }
        return TaskListViewModel(
            taskDao = taskDao,
            taskDeleter = mock(),
            deletionDao = mock(),
            taskSaver = mock(),
            taskCompleter = mock(),
            tasksPreferences = TasksPreferences(InMemoryDataStore()),
            headerFormatter = mock(),
            queryPreferences = DataStoreQueryPreferences(TasksPreferences(InMemoryDataStore())),
            refreshFlow = emptyFlow(),
        )
    }

    @Test
    fun pickModeSearchesAllTasksEvenWithABlankQuery() = runTest {
        val vm = viewModel()
        val caldav = caldavFilter()
        vm.setFilter(caldav)
        vm.setPickParent(42L)
        // setPickParent stores the exclusion set in state without touching the
        // search query: before the first keystroke the query is still null.
        val beforeTyping = vm.queryFilterFor(vm.state.value)
        assertTrue(
            "expected a SearchFilter the moment pick mode starts, got $beforeTyping",
            beforeTyping is SearchFilter,
        )
        assertEquals(42L, (beforeTyping as SearchFilter).excludedTaskIds.single())

        // A blank query (the find field, untouched) keeps the same exclusion:
        // the subtree must be hidden before any text is typed, not only after.
        vm.setSearchQuery("")
        val duringPick = vm.queryFilterFor(vm.state.value) as SearchFilter
        assertEquals("", duringPick.query)
        assertEquals(setOf(42L), duringPick.excludedTaskIds)

        // And a typed query filters within the same exclusion.
        vm.setSearchQuery(TEST_SEARCH_QUERY)
        val withQuery = vm.queryFilterFor(vm.state.value) as SearchFilter
        assertEquals(TEST_SEARCH_QUERY, withQuery.query)
        assertEquals(setOf(42L), withQuery.excludedTaskIds)
    }

    @Test
    fun leavingPickModeRestoresTheActiveFilter() = runTest {
        val vm = viewModel()
        val caldav = caldavFilter()
        vm.setFilter(caldav)

        vm.setPickParent(42L)
        vm.setSearchQuery("")
        assertTrue(vm.queryFilterFor(vm.state.value) is SearchFilter)

        // The UI leaves pick mode with setPickParent(null) + setSearchQuery(null).
        vm.setPickParent(null)
        vm.setSearchQuery(null)
        assertEquals(caldav, vm.queryFilterFor(vm.state.value))
    }
}
