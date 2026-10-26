package org.tasks.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.tasks.data.db.Database
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_GOOGLE_TASKS
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.Filter
import org.tasks.data.entity.Place
import org.tasks.data.entity.TagData
import org.tasks.data.entity.TaskAttachment
import org.tasks.data.entity.TaskListMetadata

/**
 * Non-task sections of a [LocalBackupImporter] run (places, tags, filters,
 * CalDAV accounts/calendars, list metadata, attachments, Google Tasks).
 *
 * Extracted so the per-section loops stay individually small; the importer
 * class keeps orchestration.
 */
internal class MetadataImporter(
    private val database: Database,
    private val findExistingAccount: suspend (account: CaldavAccount) -> CaldavAccount?,
    private val findTagData: suspend (tagData: TagData) -> TagData?,
    private val themeToColor: (version: Int, color: Int) -> Int,
) {
    /**
     * Restores every metadata section, filling [accountUuidMap] and
     * [calendarUuidMap] with old-uuid → existing-uuid remappings for the
     * task import pass.
     */
    suspend fun importMetadata(
        data: JsonObject,
        version: Int,
        accountUuidMap: MutableMap<String, String>,
        calendarUuidMap: MutableMap<String, String>,
    ) {
        val importedAccounts = importPlaces(data)
            .let { importTags(data, version, it) }
            .let { importFilters(data, version, it) }
            .let { importCaldavAccounts(data, version, it, accountUuidMap) }
        importCaldavCalendars(data, version, importedAccounts, accountUuidMap, calendarUuidMap)
        importListMetadata(data)
        importAttachments(data)
        // The five *Prefs sections are intentionally not restored on
        // desktop (typed DataStore schema, different key namespace).
        importGoogleTasks(data, version)
    }

    private suspend fun importPlaces(data: JsonObject): Unit {
        for (place in decodeSection<Place>(data["places"]) ?: emptyList()) {
            if (database.locationDao().getByUid(place.uid!!) == null) {
                database.locationDao().insert(place.copy(icon = place.icon.migrateLegacyIcon()))
            }
        }
    }

    private suspend fun importTags(
        data: JsonObject,
        version: Int,
        previous: Unit,
    ): Unit {
        for (tagData in decodeSection<TagData>(data["tags"]) ?: emptyList()) {
            if (findTagData(tagData) != null) continue
            database.tagDataDao().insert(
                tagData.copy(
                    color = themeToColor(version, tagData.color ?: 0),
                    icon = tagData.icon.migrateLegacyIcon(),
                )
            )
        }
    }

    private suspend fun importFilters(
        data: JsonObject,
        version: Int,
        previous: Unit,
    ): Unit {
        // Pre-13.2 filters need an astrid-Criterion SQL rebuild that is
        // app-only; their stored criterion string is kept as-is instead.
        for (filter in decodeSection<Filter>(data["filters"]) ?: emptyList()) {
            if (database.filterDao().getByName(filter.title!!) == null) {
                database.filterDao().insert(
                    filter.copy(
                        color = themeToColor(version, filter.color ?: 0),
                        icon = filter.icon.migrateLegacyIcon(),
                    )
                )
            }
        }
    }

    private suspend fun importCaldavAccounts(
        data: JsonObject,
        version: Int,
        previous: Unit,
        accountUuidMap: MutableMap<String, String>,
    ): Map<String, CaldavAccount> {
        val importedAccounts = mutableMapOf<String, CaldavAccount>()
        for (imported in decodeSection<CaldavAccount>(data["caldavAccounts"]) ?: emptyList()) {
            val account =
                if (version < BackupVersions.UPGRADE_15_13) imported.canonicalizedBackupUrl()
                else imported
            importedAccounts[account.uuid!!] = account
            if (database.caldavDao().getAccountByUuid(account.uuid!!) != null) continue
            val existing = findExistingAccount(account)
            if (existing != null) {
                accountUuidMap[account.uuid!!] = existing.uuid!!
            } else {
                database.caldavDao().insert(account)
            }
        }
        return importedAccounts
    }

    private suspend fun importCaldavCalendars(
        data: JsonObject,
        version: Int,
        importedAccounts: Map<String, CaldavAccount>,
        accountUuidMap: Map<String, String>,
        calendarUuidMap: MutableMap<String, String>,
    ) {
        for (imported in decodeSection<CaldavCalendar>(data["caldavCalendars"]) ?: emptyList()) {
            if (database.caldavDao().getCalendarByUuid(imported.uuid!!) != null) continue
            val calendar = if (version < BackupVersions.UPGRADE_15_13)
                imported.canonicalizedBackupUrl(importedAccounts[imported.account])
            else imported
            val remappedAccount = accountUuidMap[calendar.account] ?: calendar.account
            val existingByUrl = calendar.url?.takeIf { it.isNotBlank() }
                ?.let { url -> database.caldavDao().getCalendarByUrl(remappedAccount!!, url) }
            if (existingByUrl != null) {
                calendarUuidMap[calendar.uuid!!] = existingByUrl.uuid!!
            } else {
                database.caldavDao().insert(
                    calendar.copy(
                        account = remappedAccount,
                        color = themeToColor(version, calendar.color),
                        icon = calendar.icon.migrateLegacyIcon(),
                    )
                )
            }
        }
    }

    private suspend fun importListMetadata(data: JsonObject) {
        for (tlm in decodeSection<TaskListMetadata>(data["taskListMetadata"]) ?: emptyList()) {
            val id = tlm.filter.takeIf { it?.isNotBlank() == true } ?: tlm.tagUuid!!
            if (database.taskListMetadataDao().fetchByTagOrFilter(id) == null) {
                database.taskListMetadataDao().insert(tlm)
            }
        }
    }

    private suspend fun importAttachments(data: JsonObject) {
        for (attachment in decodeSection<TaskAttachment>(data["taskAttachments"]) ?: emptyList()) {
            if (database.taskAttachmentDao().getAttachment(attachment.remoteId) == null) {
                database.taskAttachmentDao().insert(attachment)
            }
        }
    }

    private suspend fun importGoogleTasks(data: JsonObject, version: Int) {
        for (googleTaskAccount in decodeSection<org.tasks.data.GoogleTaskAccount>(
            data["googleTaskAccounts"]
        ) ?: emptyList()) {
            if (database.caldavDao().getAccount(TYPE_GOOGLE_TASKS, googleTaskAccount.account!!) == null) {
                database.caldavDao().insert(
                    CaldavAccount(
                        accountType = TYPE_GOOGLE_TASKS,
                        uuid = googleTaskAccount.account,
                        name = googleTaskAccount.account,
                        username = googleTaskAccount.account,
                    )
                )
            }
        }
        for (googleTaskList in decodeSection<org.tasks.data.GoogleTaskList>(
            data["googleTaskLists"]
        ) ?: emptyList()) {
            if (database.caldavDao().getCalendar(googleTaskList.remoteId!!) == null) {
                database.caldavDao().insert(
                    CaldavCalendar(
                        account = googleTaskList.account,
                        uuid = googleTaskList.remoteId,
                        color = themeToColor(version, googleTaskList.color ?: 0),
                        icon = googleTaskList.icon?.toString().migrateLegacyIcon(),
                    )
                )
            }
        }
    }

    private inline fun <reified T> decodeSection(
        element: JsonElement?,
    ): List<T>? = element?.let {
        lenientJson.decodeFromString(it.toString())
    }
}
