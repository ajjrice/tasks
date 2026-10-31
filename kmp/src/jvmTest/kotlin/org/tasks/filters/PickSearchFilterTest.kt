package org.tasks.filters

import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickSearchFilterTest {

    private val myTasks = MyTasksFilter(title = "My Tasks")

    @Test
    fun blankQueryWithoutExclusionLeavesTheBaseScopeUnchanged() {
        val sql = PickSearchFilter(
            title = "My Tasks",
            base = myTasks,
            query = "",
        ).sql
        assertEquals("nothing to AND in, the base clause passes through", myTasks.sql, sql)
    }

    @Test
    fun blankQueryStillExcludesTheSubtree() {
        val sql = PickSearchFilter(
            title = "My Tasks",
            base = myTasks,
            query = "",
            excludedTaskIds = setOf(42L, 43L),
        ).sql
        assertTrue(sql.contains("NOT IN (42,43)"))
        // The base scope survives the exclusion, it is wrapped, not replaced.
        assertTrue(sql.startsWith(myTasks.sql.substringBefore("WHERE ") + "WHERE"))
        assertFalse("a blank query must not run the LIKE matcher", sql.contains("LIKE"))
    }

    @Test
    fun aTypedQueryAndsTheSearchOntoTheBaseScope() {
        val sql = PickSearchFilter(
            title = "My Tasks",
            base = myTasks,
            query = "blue whale",
            excludedTaskIds = setOf(42L),
        ).sql
        assertTrue(sql.contains("LIKE '%blue whale%'"))
        assertTrue(sql.contains("NOT IN (42)"))
        // The base scope's own WHERE leads; the search is ANDed onto it.
        assertTrue(sql.startsWith("WHERE"))
    }

    @Test
    fun aJoinCarryingBaseKeepsItsJoins() {
        // CaldavFilter's sql starts with a LEFT JOIN before its WHERE.
        val caldav = CaldavFilter(
            calendar = CaldavCalendar(uuid = "cal-1", account = "acct-1"),
            account = CaldavAccount(uuid = "acct-1"),
        )
        val base = caldav.sql
        assertTrue(base.startsWith("LEFT JOIN "))
        val sql = PickSearchFilter(
            title = "Inbox",
            base = caldav,
            query = "test",
        ).sql
        // The join prefix is preserved verbatim up to the top-level WHERE.
        val joinPrefix = base.substringBefore("WHERE ")
        assertTrue(sql.startsWith(joinPrefix + "WHERE"))
        assertTrue(sql.contains("LIKE '%test%'"))
    }

    @Test
    fun subqueryWheresDoNotConfuseTheTopLevelSplit() {
        // A WHERE inside the base's own subquery must not be the split point:
        // the top-level one is, and the subquery keeps its clause intact.
        val base = "WHERE (tasks.id IN (SELECT task FROM tags WHERE name = 'x')) "
        val sql = pickSearchSql(base, "test", setOf(7L))
        val subquery = "SELECT task FROM tags WHERE name = 'x'"
        assertTrue("subquery must survive intact, got: $sql", sql.contains(subquery))
        // The search lands at the top level, after the subquery's closing paren.
        assertTrue(sql.contains("LIKE '%test%'"))
        assertTrue(sql.contains("NOT IN (7)"))
    }

    @Test
    fun missingTopLevelWhereFallsBackToWrappingTheWholeClause() {
        val sql = pickSearchSql("0", "test", emptySet())
        assertTrue(sql.startsWith("WHERE ((0) AND "))
        assertTrue(sql.contains("LIKE '%test%'"))
    }

    @Test
    fun sortIdentityIsTheWrappedFilter() {
        val pick = PickSearchFilter(title = "Inbox", base = myTasks, query = "")
        assertTrue(
            "the query machinery must see the wrapped list, not the wrapper",
            pick.sortIdentity === myTasks,
        )
    }

    @Test
    fun sortCapabilitiesFollowTheWrappedFilter() {
        val caldav = CaldavFilter(
            calendar = CaldavCalendar(uuid = "cal-1", account = "acct-1"),
            account = CaldavAccount(uuid = "acct-1"),
        )
        val pick = PickSearchFilter(title = "Inbox", base = caldav, query = "")
        assertTrue("a manual-sort list must keep manual sort while picking", pick.supportsManualSort())
        assertTrue(pick.supportsSorting())
    }
}
