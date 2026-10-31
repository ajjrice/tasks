package org.tasks.filters

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterTest {

    @Test
    fun theDefaultFilterHasNoExclusion() {
        val sql = SearchFilter(title = "wire", query = "wire").sql
        assertFalse("a plain search must not carry a NOT IN clause", sql.contains("NOT IN"))
        assertTrue(sql.contains("wire"))
    }

    @Test
    fun excludedTaskIdsAddsANotInClause() {
        val sql = SearchFilter(title = "wire", query = "wire", excludedTaskIds = setOf(7L, 42L)).sql
        assertTrue("the edited task and its subtree must be excluded", sql.contains("NOT IN (7,42)"))
        // The exclusion wraps the match, it does not replace it.
        assertTrue(sql.contains("wire"))
    }
}
