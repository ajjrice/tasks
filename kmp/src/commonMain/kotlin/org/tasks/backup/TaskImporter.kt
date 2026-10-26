package org.tasks.backup

import org.tasks.caldav.VtodoCache
import org.tasks.data.TaskSaver
import org.tasks.data.db.Database
import org.tasks.data.entity.Alarm
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Geofence
import org.tasks.data.entity.Place
import org.tasks.data.entity.Tag
import org.tasks.data.entity.TagData
import org.tasks.data.entity.Task
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Per-task section of a [LocalBackupImporter] run.
 *
 * Owns everything that happens for a single task row (alarms, links,
 * comments, locations, tags, attachments) so the importer class stays
 * focused on orchestration.
 */
internal class TaskImporter(
    private val database: Database,
    private val taskSaver: TaskSaver,
    private val vtodoCache: VtodoCache,
    private val defaultDueTimeEnabled: Boolean,
    private val findTagData: suspend (tag: Tag) -> TagData?,
) {
    suspend fun importTask(
        backup: TaskBackup,
        taskJson: JsonObject,
        version: Int,
        result: ImportResult,
        calendarUuidMap: Map<String, String>,
    ) {
        val task = backup.task
        val perTaskDirty: Pair<Long, Long>? = when {
            backup.dirtyVersion != null && backup.syncedVersion != null ->
                backup.dirtyVersion to backup.syncedVersion
            version >= DIRTY_FLAG_VERSION -> {
                val synced = backup.syncedVersion ?: 0L
                val dirty = backup.dirtyVersion ?: (synced + 1)
                dirty to synced
            }
            else -> null
        }
        val googleLastSync =
            if (perTaskDirty == null) lastSyncValues(taskJson, "google") else emptyList()
        val caldavLastSync =
            if (perTaskDirty == null) lastSyncValues(taskJson, "caldavTasks") else emptyList()
        if (database.taskDao().fetch(task.uuid) != null) {
            result.skipCount++
            return
        }
        if (backup.caldavTasks
            ?.filter { it.deleted == 0L }
            ?.any { caldavTaskAlreadyExists(calendarUuidMap, it) } == true
        ) {
            result.skipCount++
            return
        }
        task.suppressRefresh()
        task.suppressSync()
        database.taskDao().createNew(task)
        val taskId = task.id
        insertAlarms(backup, task, version)
        if (version < BackupVersions.V12_4) {
            applyLegacyRingFlags(task)
        }
        if (version < BackupVersions.V12_8) {
            task.repeatFrom = task.recurrence.repeatFrom()
            task.recurrence = task.recurrence.withoutFrom()
        }
        importComments(backup, task, version)
        importGoogleLinks(backup, task, perTaskDirty, googleLastSync)
        importLocations(backup, task)
        importTags(backup, task)
        importGeofences(backup, task)
        importAttachments(backup, task)
        importCaldavLinks(backup, task, perTaskDirty, caldavLastSync, calendarUuidMap)
        importVtodo(backup, calendarUuidMap)
        result.importCount++
    }

    private suspend fun insertAlarms(
        backup: TaskBackup,
        task: Task,
        version: Int,
    ) {
        val skipAllDayAlarms = version < BackupVersions.UPGRADE_14_13
            && task.hasDueDate()
            && !task.hasDueTime()
            && !defaultDueTimeEnabled
        backup.alarms
            ?.map { it.copy(task = task.id) }
            ?.let { if (skipAllDayAlarms) emptyList() else it }
            ?.let { database.alarmDao().insert(it) }
        if (version < BackupVersions.V12_4) {
            val alarms = legacyAlarmsFromFlags(task.ringFlags, task.id)
                .filter { alarm ->
                    when (alarm.type) {
                        Alarm.TYPE_REL_START -> task.hasStartDate()
                        Alarm.TYPE_REL_END -> task.hasDueDate()
                        else -> true
                    }
                }
                .let { if (skipAllDayAlarms) emptyList() else it }
                .let {
                    if (task.randomReminder > 0) {
                        it + Alarm(time = task.randomReminder, type = Alarm.TYPE_RANDOM)
                    } else {
                        it
                    }
                }
            database.alarmDao().insert(alarms)
        }
    }

    private suspend fun applyLegacyRingFlags(task: Task) {
        task.ringFlags = when {
            task.isNotifyModeFive -> Task.NOTIFY_MODE_FIVE
            task.isNotifyModeNonstop -> Task.NOTIFY_MODE_NONSTOP
            else -> 0
        }
        taskSaver.save(task, null)
    }

    private suspend fun importComments(
        backup: TaskBackup,
        task: Task,
        version: Int,
    ) {
        for (comment in backup.comments.orEmpty()) {
            comment.targetId = task.uuid
            if (version < BackupVersions.V6_4) {
                comment.picture = convertLegacyPictureUri(comment.picture)
            }
            database.userActivityDao().createNew(comment)
        }
    }

    private suspend fun importGoogleLinks(
        backup: TaskBackup,
        task: Task,
        perTaskDirty: Pair<Long, Long>?,
        googleLastSync: List<Long?>,
    ) {
        backup.google?.forEachIndexed { index, googleTask ->
            val id = database.caldavDao().insert(
                CaldavTask(
                    task = task.id,
                    calendar = googleTask.listId,
                    remoteId = googleTask.remoteId,
                    remoteOrder = googleTask.remoteOrder,
                    remoteParent = googleTask.remoteParent,
                    deleted = googleTask.deleted,
                )
            )
            if (googleTask.deleted == 0L) {
                val (dirty, synced) = perTaskDirty
                    ?: reconstructDirtyVersion(
                        googleLastSync.getOrNull(index),
                        task.modificationDate,
                    )
                database.dirtyDao().setDirtyState(id, dirty, synced)
            }
        }
    }

    private suspend fun importLocations(backup: TaskBackup, task: Task) {
        backup.locations?.forEach { location ->
            val place = Place(
                longitude = location.longitude,
                latitude = location.latitude,
                name = location.name,
                address = location.address,
                url = location.url,
                phone = location.phone,
            )
            database.locationDao().insert(place)
            database.locationDao().insert(
                Geofence(
                    task = task.id,
                    place = place.uid,
                    isArrival = location.arrival,
                    isDeparture = location.departure,
                )
            )
        }
    }

    private suspend fun importTags(backup: TaskBackup, task: Task) {
        backup.tags?.forEach { tag ->
            val tagData = findTagData(tag) ?: return@forEach
            database.tagDao().insert(
                tag.copy(
                    task = task.id,
                    taskUid = task.remoteId,
                    tagUid = tagData.remoteId,
                )
            )
        }
    }

    private suspend fun importGeofences(backup: TaskBackup, task: Task) {
        backup.geofences?.forEach { geofence ->
            database.locationDao().insert(geofence.copy(task = task.id))
        }
    }

    private suspend fun importAttachments(backup: TaskBackup, task: Task) {
        backup.attachments
            ?.mapNotNull { database.taskAttachmentDao().getAttachment(it.attachmentUid) }
            ?.map {
                Attachment(
                    task = task.id,
                    fileId = it.id!!,
                    attachmentUid = it.remoteId,
                )
            }
            ?.let { database.taskAttachmentDao().insert(it) }
    }

    private suspend fun importCaldavLinks(
        backup: TaskBackup,
        task: Task,
        perTaskDirty: Pair<Long, Long>?,
        caldavLastSync: List<Long?>,
        calendarUuidMap: Map<String, String>,
    ) {
        backup.caldavTasks?.forEachIndexed { index, caldavTask ->
            val remappedCalendar =
                calendarUuidMap[caldavTask.calendar] ?: caldavTask.calendar
            val id = database.caldavDao().insert(
                caldavTask.copy(task = task.id, calendar = remappedCalendar)
            )
            if (!caldavTask.isDeleted()) {
                val (dirty, synced) = perTaskDirty
                    ?: reconstructDirtyVersion(
                        caldavLastSync.getOrNull(index),
                        task.modificationDate,
                    )
                database.dirtyDao().setDirtyState(id, dirty, synced)
            }
        }
    }

    private suspend fun importVtodo(
        backup: TaskBackup,
        calendarUuidMap: Map<String, String>,
    ) {
        backup.vtodo?.let { vtodo ->
            val caldavTask =
                backup.caldavTasks?.firstOrNull { !it.isDeleted() } ?: return@let
            val remappedCalendar =
                calendarUuidMap[caldavTask.calendar] ?: caldavTask.calendar
            val caldavCalendar =
                database.caldavDao().getCalendar(remappedCalendar!!) ?: return@let
            vtodoCache.putVtodo(
                caldavCalendar,
                caldavTask.copy(calendar = remappedCalendar),
                vtodo,
            )
        }
    }

    private suspend fun caldavTaskAlreadyExists(
        calendarUuidMap: Map<String, String>,
        caldavTask: CaldavTask,
    ): Boolean {
        val calendar = calendarUuidMap[caldavTask.calendar] ?: caldavTask.calendar
        val existing = if (caldavTask.obj.isNullOrBlank() ||
            caldavTask.obj == NULL_ICS // caused by an old bug
        ) {
            caldavTask.remoteId?.let { database.caldavDao().getTaskByRemoteId(calendar!!, it) }
        } else {
            database.caldavDao().getTask(calendar!!, caldavTask.obj!!)
        }
        return existing != null
    }

    private fun lastSyncValues(taskJson: JsonObject, key: String): List<Long?> =
        (taskJson[key] as? JsonArray)
            ?.map { item ->
                (item as? JsonObject)?.let { obj ->
                    obj["cd_last_sync"]?.jsonPrimitive?.longOrNull
                        ?: obj["lastSync"]?.jsonPrimitive?.longOrNull
                }
            }
            .orEmpty()
}

private const val NULL_ICS = "null.ics"
