package org.tasks.filters

import org.tasks.CommonParcelize
import org.tasks.data.dao.TaskDao
import org.tasks.data.sql.QueryTemplate
import org.tasks.resources.getString
import org.tasks.themes.TasksIcons
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.filter_all_tasks

/**
 * Every active task, in every list, at every depth. The widen target for
 * the edit pane's "move into another task" find field: [MyTasksFilter] is
 * top-level only (`PARENT = 0`), but the pick has to be able to target a
 * subtask, so the search cannot run over the My Tasks scope. Transient by
 * design - it is only ever selected while the panel's find field is
 * focused, and never listed in the drawer.
 */
@CommonParcelize
data class PickAllFilter(
    override val title: String,
    override var filterOverride: String? = null,
) : AstridOrderingFilter() {
    override val icon: String
        get() = TasksIcons.ALL_INBOX

    override val sql: String
        get() = QueryTemplate()
            .where(TaskDao.TaskCriteria.activeAndVisible())
            .toString()

    override fun areItemsTheSame(other: FilterListItem): Boolean {
        return other is PickAllFilter
    }

    companion object {
        suspend fun create() = PickAllFilter(getString(Res.string.filter_all_tasks))
    }
}
