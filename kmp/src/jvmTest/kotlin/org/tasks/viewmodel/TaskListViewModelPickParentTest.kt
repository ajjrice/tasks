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
import org.tasks.filters.PickSearchFilter
import org.tasks.preferences.DataStoreQueryPreferences
import org.tasks.preferences.TasksPreferences

/**
 * Pins the pick-parent contract in [TaskListViewModel.queryFilterFor] that the
 * edit pane's "move into another task" panel relies on: while pick mode is
 * active the list shows the selected filter's scope narrowed by the panel's
 * live search, with the picked-into task and its subtree excluded - even
 * before the user has typed anything (a blank query in pick mode must not
 * fall through to "My Tasks" or drop the exclusion, or the subtree would be
 * visible as pick candidates).
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
    fun pickModeKeepsTheSelectedFilterInScopeEvenWithABlankQuery() = runTest {
        val vm = viewModel()
        val caldav = caldavFilter()
        vm.setFilter(caldav)
        vm.setPickParent(42L)
        // setPickParent stores the exclusion set in state without touching the
        // search query: before the first keystroke the query is still null.
        val beforeTyping = vm.queryFilterFor(vm.state.value)
        assertTrue(
            "expected a PickSearchFilter the moment pick mode starts, got $beforeTyping",
            beforeTyping is PickSearchFilter,
        )
        val pick = beforeTyping as PickSearchFilter
        assertEquals(setOf(42L), pick.excludedTaskIds)
        // The drawer's selection stays in play: the base scope is preserved,
        // not replaced by an all-task search.
        assertTrue("the selected list must stay the base scope", pick.base === caldav)
        assertEquals("", pick.query)

        // A blank query (the find field, untouched) keeps the same exclusion:
        // the subtree must be hidden before any text is typed, not only after.
        vm.setSearchQuery("")
        val duringPick = vm.queryFilterFor(vm.state.value) as PickSearchFilter
        assertEquals("", duringPick.query)
        assertEquals(setOf(42L), duringPick.excludedTaskIds)
        assertTrue(duringPick.base === caldav)

        // And a typed query filters within the same base scope and exclusion.
        vm.setSearchQuery(TEST_SEARCH_QUERY)
        val withQuery = vm.queryFilterFor(vm.state.value) as PickSearchFilter
        assertEquals(TEST_SEARCH_QUERY, withQuery.query)
        assertEquals(setOf(42L), withQuery.excludedTaskIds)
        assertTrue(withQuery.base === caldav)
    }

    @Test
    fun switchingTheDrawerFilterWhilePickingReScopesTheSearch() = runTest {
        val vm = viewModel()
        val caldav = caldavFilter()
        vm.setFilter(caldav)
        vm.setPickParent(42L)
        vm.setSearchQuery(TEST_SEARCH_QUERY)

        // The drawer's list switch goes through setFilter while pick mode is
        // active: the pick search must re-scope to the new base filter.
        val myTasks = org.tasks.filters.MyTasksFilter(title = "My Tasks")
        vm.setFilter(myTasks)
        val reScoped = vm.queryFilterFor(vm.state.value) as PickSearchFilter
        assertEquals(TEST_SEARCH_QUERY, reScoped.query)
        assertTrue("re-scope must keep the new drawer selection as base", reScoped.base === myTasks)
        assertEquals(setOf(42L), reScoped.excludedTaskIds)
    }

    @Test
    fun leavingPickModeRestoresTheActiveFilter() = runTest {
        val vm = viewModel()
        val caldav = caldavFilter()
        vm.setFilter(caldav)

        vm.setPickParent(42L)
        vm.setSearchQuery("")
        assertTrue(vm.queryFilterFor(vm.state.value) is PickSearchFilter)

        // The UI leaves pick mode with setPickParent(null) + setSearchQuery(null).
        vm.setPickParent(null)
        vm.setSearchQuery(null)
        assertEquals(caldav, vm.queryFilterFor(vm.state.value))
    }
}
