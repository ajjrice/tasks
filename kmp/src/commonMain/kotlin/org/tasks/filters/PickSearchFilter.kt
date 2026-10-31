package org.tasks.filters

import org.tasks.data.entity.Task
import org.tasks.data.sql.Criterion
import org.tasks.data.sql.QueryTemplate

/**
 * The list pane's query filter while the edit pane's "move into another task"
 * panel is open: the selected filter's scope, narrowed by the panel's live
 * search and by the moved task's subtree exclusion.
 *
 * Deliberately different from [SearchFilter], which searches ALL tasks: while
 * the panel is open the drawer stays live, so choosing another list or tag
 * there narrows the pick candidates to that list's scope (search on top of
 * the selection), and choosing My Tasks widens them back out. The subtree
 * exclusion applies in every case - even with a blank query, before the user
 * has typed anything - so a task can never be chosen as its own ancestor.
 */
data class PickSearchFilter(
    override val title: String,
    // The selected filter's full clause, joins and all: [Filter.sql] is the
    // text the list query embeds in WHERE position ("WHERE (...)" or
    // "<JOIN> WHERE (...)").
    val baseSql: String,
    val query: String,
    val excludedTaskIds: Set<Long> = emptySet(),
) : Filter() {
    override val sql: String
        get() = pickSearchSql(baseSql, query, excludedTaskIds)

    override fun areItemsTheSame(other: FilterListItem): Boolean {
        return other is PickSearchFilter
    }

    override fun supportsHiddenTasks() = false
}

/**
 * ANDs the search criterion (when the query is non-blank) and the subtree
 * exclusion into [baseSql]'s top-level WHERE. The base clause's own joins and
 * subqueries are preserved; only the first paren-depth-0 "WHERE" is the
 * split point, because "WHERE" also occurs inside subqueries.
 */
internal fun pickSearchSql(
    baseSql: String,
    query: String,
    excludedTaskIds: Set<Long>,
): String {
    val extras = mutableListOf<Criterion>()
    if (query.isNotBlank()) {
        extras += activeTaskSearchCriterion(query)
    }
    if (excludedTaskIds.isNotEmpty()) {
        extras += Task.ID.notIn(excludedTaskIds.toList())
    }
    if (extras.isEmpty()) return baseSql

    val extraSql = extras.joinToString(" AND ")
    val split = topLevelWhereSplit(baseSql) ?: return "WHERE ((${baseSql.trim()}) AND $extraSql)"
    val (beforeWhere, baseCondition) = split
    val prefix = if (beforeWhere.isEmpty()) "" else beforeWhere.trimEnd() + " "
    return prefix + "WHERE ((${baseCondition.trim()}) AND $extraSql)"
}

/**
 * Splits [sql] at its first paren-depth-0 "WHERE " into (text before,
 * condition after). Returns null when there is no top-level WHERE.
 */
private fun topLevelWhereSplit(sql: String): Pair<String, String>? {
    var depth = 0
    var i = 0
    while (i < sql.length) {
        when (sql[i]) {
            '(' -> depth++
            ')' -> depth--
        }
        if (depth == 0 && sql.startsWith("WHERE ", i)) {
            return sql.substring(0, i) to sql.substring(i + "WHERE ".length)
        }
        i++
    }
    return null
}
