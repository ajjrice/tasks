package org.tasks.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tasks.broadcast.RefreshBroadcaster
import org.tasks.caldav.VtodoCache
import org.tasks.data.TaskMover
import org.tasks.data.TaskSaver
import org.tasks.data.entity.Alarm
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_GOOGLE_TASKS
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Geofence
import org.tasks.data.entity.Task
import org.tasks.data.entity.Tag
import org.tasks.data.entity.TagData
import org.tasks.data.entity.UserActivity

/**
 * Restores a backup file in the native Tasks.org format.
 *
 * Faithful port of the Android `TasksJsonImporter`: metadata (places,
 * tags, filters, CalDAV accounts and calendars, list metadata,
 * attachments) is imported first, then tasks. Existing entities are
 * never overwritten; unknown accounts and calendars are remapped onto
 * their local counterparts; tasks whose uuid (or whose CalDAV remote
 * task) already exists locally are skipped; version-gated migrations
 * run exactly as on Android.
 *
 * Documented deviations (SPEC-backup-desktop.md):
 * - the `*Prefs` sections are not restored (desktop prefs are typed
 *   DataStore values with a different key namespace);
 * - pre-13.2 filter SQL rebuild is skipped (the astrid
 *   `CriterionInstance` machinery is app-only) — the stored criterion
 *   string is kept as-is;
 * - whole-file lenient parse instead of a streaming token walk; the
 *   input is a [String] so the class stays free of platform file APIs;
 * - the 15.10 random-reminder migration reads a legacy Android pref
 *   that is never restored here, so it is a natural no-op.
 */
class LocalBackupImporter(
    private val database: org.tasks.data.db.Database,
    private val taskSaver: TaskSaver,
    private val taskMover: TaskMover,
    private val vtodoCache: VtodoCache,
    private val refreshBroadcaster: RefreshBroadcaster,
    private val defaultDueTimeEnabled: Boolean = false,
) {
    suspend fun import(
        text: String,
        onProgress: (Int) -> Unit = {},
    ): ImportResult {
        val result = ImportResult()
        val root = lenientJson.parseToJsonElement(text).jsonObject
        val version = root["version"]?.jsonPrimitive?.long?.toInt() ?: 0
        val data = root["data"]?.jsonObject ?: JsonObject(emptyMap())

        val accountUuidMap = mutableMapOf<String, String>()
        val calendarUuidMap = mutableMapOf<String, String>()
        val metadataImporter = MetadataImporter(
            database = database,
            findExistingAccount = { account -> findExistingAccount(account) },
            findTagData = { tagData -> findTagData(tagData) },
            themeToColor = { version, color -> themeToColor(version, color) },
        )
        metadataImporter.importMetadata(data, version, accountUuidMap, calendarUuidMap)
        importTasks(data, version, result, calendarUuidMap, onProgress)

        if (version < BackupVersions.V9_6) {
            taskMover.migrateLocalTasks()
        }
        // The 15.10 random-reminder migration reads a legacy Android pref
        // that desktop never restores, so there is nothing to migrate.
        database.caldavDao().updateParents(force = true)
        refreshBroadcaster.broadcastRefresh()
        return result
    }

    private suspend fun importTasks(
        data: JsonObject,
        version: Int,
        result: ImportResult,
        calendarUuidMap: Map<String, String>,
        onProgress: (Int) -> Unit,
    ) {
        val tasks = data["tasks"] as? JsonArray ?: return
        val taskImporter = TaskImporter(
            database = database,
            taskSaver = taskSaver,
            vtodoCache = vtodoCache,
            defaultDueTimeEnabled = defaultDueTimeEnabled,
            findTagData = { tag -> findTagData(tag) },
        )
        for (element in tasks) {
            val backup = lenientJson.decodeFromJsonElement(TaskBackup.serializer(), element)
            result.taskCount++
            onProgress(result.taskCount)
            taskImporter.importTask(backup, element.jsonObject, version, result, calendarUuidMap)
        }
    }

    private suspend fun findExistingAccount(account: CaldavAccount): CaldavAccount? =
        database.caldavDao().getAccounts().firstOrNull { existing ->
            existing.accountType == account.accountType &&
                when {
                    account.url?.isNotBlank() == true ->
                        existing.url == account.url &&
                            existing.username == account.username
                    account.username?.isNotBlank() == true ->
                        existing.username == account.username
                    else -> false
                }
        }

    private suspend fun findTagData(tagData: TagData): TagData? =
        database.tagDataDao().getByUuid(tagData.remoteId!!) ?:
            database.tagDataDao().getTagByName(tagData.name!!)

    private suspend fun findTagData(tag: Tag): TagData? =
        findTagData(tag.tagUid!!, tag.name!!)

    private suspend fun findTagData(uid: String, name: String): TagData? =
        database.tagDataDao().getByUuid(uid) ?: database.tagDataDao().getTagByName(name)

    private fun themeToColor(version: Int, color: Int): Int =
        if (version < BackupVersions.V8_2) legacyColor(color) else color

    private inline fun <reified T> decodeSection(
        element: JsonElement?,
    ): List<T>? = element?.let {
        lenientJson.decodeFromString(it.toString())
    }
}

internal val lenientJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

internal const val DIRTY_FLAG_VERSION = 150706

/** Result of an import: how many tasks the backup held, imported, skipped. */
class ImportResult {
    var taskCount = 0
    var importCount = 0
    var skipCount = 0
}

/**
 * Per-task section of a backup file. Mirrors the Android
 * `TaskBackup` field-for-field; unknown fields are ignored so newer
 * Android versions can write files desktop still ingests.
 */
@Serializable
class TaskBackup(
    val task: Task,
    val dirtyVersion: Long? = null,
    val syncedVersion: Long? = null,
    val alarms: List<Alarm>? = null,
    val geofences: List<Geofence>? = null,
    val tags: List<Tag>? = null,
    val comments: List<UserActivity>? = null,
    val attachments: List<Attachment>? = null,
    val caldavTasks: List<CaldavTask>? = null,
    val vtodo: String? = null,
    val google: List<org.tasks.data.GoogleTask>? = null,
    val locations: List<LegacyLocation>? = null,
)

/** Legacy per-task location block from pre-geofence backups. */
@Deprecated("For backup use only")
@Serializable
class LegacyLocation(
    var name: String? = null,
    var address: String? = null,
    var phone: String? = null,
    var url: String? = null,
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var radius: Int = 0,
    var arrival: Boolean = false,
    var departure: Boolean = false,
)
