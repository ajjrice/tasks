package org.tasks.backup

import kotlinx.serialization.json.Json
import org.tasks.caldav.VtodoCache

/**
 * Writes a full-dump backup file in the native Tasks.org format
 * (`version` + `timestamp` + `data` with tasks, places, tags, filters,
 * CalDAV accounts/calendars, list metadata, attachments and prefs).
 *
 * Key names and the per-task shape mirror the Android
 * `TasksJsonExporter` exactly, so files are interchangeable between the
 * Android and desktop apps. Desktop prefs are typed DataStore values
 * without a generic snapshot, so the five `*Prefs` maps are written
 * empty (documented limitation: a desktop backup restores no settings).
 *
 * The result is returned as a [String] so the class stays free of
 * platform file APIs; the caller decides where the file goes. Desktop
 * backup sizes make whole-string construction safe.
 */
class LocalBackupExporter(
    private val database: org.tasks.data.db.Database,
    private val vtodoCache: VtodoCache,
) {
    /** The serialized backup file and how many tasks it contains. */
    data class ExportedBackup(
        val content: String,
        val taskCount: Int,
    )

    suspend fun export(
        version: Int,
        timestamp: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ExportedBackup {
        val taskIds = database.taskDao().getAllTaskIds()
        val jsonWriter = JsonWriter()
        jsonWriter.write("{")
        jsonWriter.write("version", version)
        jsonWriter.write("timestamp", timestamp)
        jsonWriter.write("\"data\":{")
        jsonWriter.write("\"tasks\":[")
        val dirtyStates = database.dirtyDao().getDirtyStateByTaskIds(taskIds)
        taskIds.forEachIndexed { index, id ->
            onProgress(index, taskIds.size)
            jsonWriter.write("{")
            jsonWriter.write("task", database.taskDao().fetch(id)!!)
            dirtyStates[id]?.let {
                jsonWriter.write("dirtyVersion", it.dirtyVersion)
                jsonWriter.write("syncedVersion", it.syncedVersion)
            }
            jsonWriter.write("alarms", database.alarmDao().getAlarms(id))
            jsonWriter.write("geofences", database.locationDao().getGeofencesForTask(id))
            jsonWriter.write("tags", database.tagDao().getTagsForTask(id))
            jsonWriter.write("comments", database.userActivityDao().getComments(id))
            jsonWriter.write("attachments", database.taskAttachmentDao().getAttachmentsForTask(id))
            val caldavTasks = database.caldavDao().getTasks(id)
            vtodoCache
                .getVtodo(caldavTasks.firstOrNull { !it.isDeleted() })
                ?.let { jsonWriter.write("vtodo", it) }
            jsonWriter.write("caldavTasks", caldavTasks, lastItem = true)
            jsonWriter.write("}")
            if (index < taskIds.size - 1) jsonWriter.write(",")
        }
        jsonWriter.write("],")
        jsonWriter.write("places", database.locationDao().getPlaces())
        jsonWriter.write("tags", database.tagDataDao().getAll())
        jsonWriter.write("filters", database.filterDao().getFilters())
        jsonWriter.write("caldavAccounts", database.caldavDao().getAccounts())
        jsonWriter.write("caldavCalendars", database.caldavDao().getCalendars())
        jsonWriter.write("taskListMetadata", database.taskListMetadataDao().getAll())
        jsonWriter.write("taskAttachments", database.taskAttachmentDao().getAttachments())
        jsonWriter.write("\"intPrefs\":{},")
        jsonWriter.write("\"longPrefs\":{},")
        jsonWriter.write("\"stringPrefs\":{},")
        jsonWriter.write("\"boolPrefs\":{},")
        jsonWriter.write("\"setPrefs\":{}")
        jsonWriter.write("}")
        jsonWriter.write("}")
        return ExportedBackup(jsonWriter.toString(), taskIds.size)
    }

    private class JsonWriter(private val json: Json = Json) {
        private val sb = StringBuilder()

        fun write(data: String) {
            sb.append(data)
        }

        inline fun <reified T> write(
            key: String,
            value: T,
            lastItem: Boolean = false,
        ) where T : Any {
            sb.append("\"$key\":${json.encodeToString(value)}")
            if (!lastItem) sb.append(",")
        }

        override fun toString(): String = sb.toString()
    }
}
