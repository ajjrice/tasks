package org.tasks.filters

import org.tasks.CommonParcelize
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Geofence
import org.tasks.data.entity.Place
import org.tasks.data.entity.Tag
import org.tasks.data.entity.Task
import org.tasks.data.entity.UserActivity
import org.tasks.data.sql.Criterion
import org.tasks.data.sql.Join
import org.tasks.data.sql.Query
import org.tasks.data.sql.QueryTemplate

@CommonParcelize
data class SearchFilter(
    override val title: String,
    val query: String,
    // Task ids the search must never match: the pick-parent flow hides the
    // task being edited and its whole subtree, so a task cannot be chosen as
    // its own ancestor. Empty for every non-pick search, so behaviour is
    // unchanged by default.
    val excludedTaskIds: Set<Long> = emptySet(),
) : Filter() {
    override val sql: String
        get() = QueryTemplate()
            .where(
                if (excludedTaskIds.isEmpty()) {
                    activeTaskSearchCriterion(query)
                } else {
                    Criterion.and(activeTaskSearchCriterion(query), Task.ID.notIn(excludedTaskIds.toList()))
                }
            )
            .toString()

    override fun areItemsTheSame(other: FilterListItem): Boolean {
        return other is SearchFilter
    }

    override fun supportsHiddenTasks() = false
}

/**
 * The title/notes/tag/place/activity/calendar matcher a task search is built
 * from. Shared by [SearchFilter] (search over all tasks) and [PickSearchFilter]
 * (the same search narrowed to the selected filter's scope).
 */
internal fun activeTaskSearchCriterion(query: String): Criterion {
    val matcher = "%$query%"
    return Criterion.and(
        Task.DELETION_DATE.eq(0),
        Criterion.or(
            Task.NOTES.like(matcher),
            Task.TITLE.like(matcher),
            Task.ID.`in`(
                Query.select(Tag.TASK)
                    .from(Tag.TABLE)
                    .where(Tag.NAME.like(matcher))
            ),
            Task.UUID.`in`(
                Query.select(UserActivity.TASK)
                    .from(UserActivity.TABLE)
                    .where(UserActivity.MESSAGE.like(matcher))
            ),
            Task.ID.`in`(
                Query.select(Geofence.TASK)
                    .from(Geofence.TABLE)
                    .join(Join.inner(Place.TABLE, Place.UID.eq(Geofence.PLACE)))
                    .where(
                        Criterion.or(
                            Place.NAME.like(matcher),
                            Place.ADDRESS.like(matcher)
                        )
                    )
            ),
            Task.ID.`in`(
                Query.select(CaldavTask.TASK)
                    .from(CaldavTask.TABLE)
                    .join(
                        Join.inner(
                            CaldavCalendar.TABLE,
                            CaldavCalendar.UUID.eq(CaldavTask.CALENDAR)
                        )
                    )
                    .where(CaldavCalendar.NAME.like(matcher))
            ),
        ),
    )
}