package org.tasks.data

import com.todoroo.astrid.core.SortHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.filters.CaldavFilter
import org.tasks.filters.PickSearchFilter
import org.tasks.preferences.DefaultQueryPreferences

/**
 * Pick mode must order the list pane exactly like the list: the wrapper
 * narrows the filter's WHERE clause, never its sort identity. A wrapper that
 * presents its own anonymous identity makes [TaskListQuery] fall back to the
 * generic sort strategy and the pane visibly re-sorts the moment the move
 * panel opens.
 */
class PickSearchOrderTest {

    private val caldav = CaldavFilter(
        calendar = CaldavCalendar(uuid = "cal-1", account = "acct-1"),
        account = CaldavAccount(uuid = "acct-1"),
    )

    private fun pickOf(query: String = "") = PickSearchFilter(
        title = caldav.title,
        base = caldav,
        query = query,
        excludedTaskIds = setOf(9L),
    )

    // The recursive CTE's ORDER BY block: the pane's full ordering, free of
    // the WHERE-clause differences between the list and pick queries.
    private fun orderBlock(query: String): String =
        query.substringAfter("ORDER BY\n").substringBefore(")")

    @Test
    fun aManuallySortedListKeepsItsManualOrderInPickMode() {
        val prefs = DefaultQueryPreferences().apply { isManualSort = true }
        val list = TaskListQuery.getQuery(prefs, caldav)
        val pick = TaskListQuery.getQuery(prefs, pickOf())

        assertEquals("pick mode must order exactly like the list", orderBlock(list), orderBlock(pick))
        val manualSelect = SortHelper.orderSelectForSortTypeRecursive(SortHelper.SORT_CALDAV, false)
        assertTrue(
            "the manual order column must drive pick mode, got: $pick",
            pick.contains("$manualSelect AS primary_sort"),
        )
        // Manual CalDAV sorting collapses grouping to none:
        val noGrouping = SortHelper.orderSelectForSortTypeRecursive(SortHelper.GROUP_NONE, true)
        assertTrue(pick.contains("$noGrouping AS primary_group"))
    }

    @Test
    fun aRegularlySortedListKeepsItsSortOrderInPickMode() {
        val prefs = DefaultQueryPreferences() // isManualSort = false, sortMode = SORT_DUE
        val list = TaskListQuery.getQuery(prefs, caldav)
        val pick = TaskListQuery.getQuery(prefs, pickOf("blue whale"))

        assertEquals("pick mode must order exactly like the list", orderBlock(list), orderBlock(pick))
        val dueSelect = SortHelper.orderSelectForSortTypeRecursive(SortHelper.SORT_DUE, false)
        assertTrue(
            "the list's sort field must drive pick mode, got: $pick",
            pick.contains("$dueSelect AS primary_sort"),
        )
    }
}
