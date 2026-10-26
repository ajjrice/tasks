package org.tasks.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.tasks.DatabaseTest
import org.tasks.data.TaskMover
import org.tasks.data.TaskSaver
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_CALDAV
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Filter
import org.tasks.data.entity.Place
import org.tasks.data.entity.Tag
import org.tasks.data.entity.TagData
import org.tasks.data.entity.Task

/**
 * Integration tests for [LocalBackupImporter] against a real in-memory
 * database. The backup files are produced by serializing the same entity
 * classes the exporter uses, so the round trip matches the native format
 * field-for-field (unknown fields are ignored by the importer).
 */
class LocalBackupImporterTest : DatabaseTest() {
    private fun importer() = LocalBackupImporter(
        database = db,
        taskSaver = mock<TaskSaver>(),
        taskMover = mock<TaskMover>(),
        vtodoCache = mock(),
        refreshBroadcaster = mock(),
    )

    @Test
    fun importsTasksTagsPlacesAndFilters() = runBlocking {
        val task = Task(title = "Import me")
        val tagData = TagData(name = TAG_NAME)
        val tag = Tag(
            task = 1,
            taskUid = task.uuid,
            tagUid = tagData.remoteId,
            name = TAG_NAME,
        )
        val place = Place(name = "Office", latitude = 1.5, longitude = 2.5)
        val filter = Filter(title = "My filter")
        val backup = FixtureBackup(
            version = RECENT_VERSION,
            timestamp = 1L,
            data = FixtureData(
                tasks = listOf(TaskBackup(task = task, tags = listOf(tag))),
                tags = listOf(tagData),
                places = listOf(place),
                filters = listOf(filter),
            ),
        )
        val result = importer().import(fixtureJson.encodeToString(backup))

        assertEquals(1, result.taskCount)
        assertEquals(1, result.importCount)
        assertEquals(0, result.skipCount)
        assertNotNull(db.taskDao().fetch(task.uuid))
        assertNotNull(db.tagDataDao().getTagByName(TAG_NAME))
        assertEquals(1, db.tagDao().getByTagUid(tagData.remoteId!!).size)
        assertNotNull(db.locationDao().getByUid(place.uid!!))
        assertNotNull(db.filterDao().getByName("My filter"))
    }

    @Test
    fun reimportSkipsExistingTasks() = runBlocking {
        val backupJson = fixtureJson.encodeToString(simpleTaskBackup(Task(title = "Synced")))
        val importer = importer()

        importer.import(backupJson)
        val second = importer.import(backupJson)

        assertEquals(1, second.taskCount)
        assertEquals(0, second.importCount)
        assertEquals(1, second.skipCount)
    }

    @Test
    fun remapsUnknownCaldavAccountAndCalendarOntoLocalOnes() = runBlocking {
        val localAccount = CaldavAccount(
            uuid = "local-account",
            name = "N",
            url = CALDAV_URL,
            username = "alex@example.com",
            accountType = TYPE_CALDAV,
        )
        val localCalendar = CaldavCalendar(
            uuid = "local-cal",
            account = "local-account",
            name = "Work",
            url = CALDAV_CAL_URL,
        )
        db.caldavDao().insert(localAccount)
        db.caldavDao().insert(localCalendar)

        val task = Task(title = "Remote")
        val caldavTask = CaldavTask(calendar = "remote-cal", remoteId = REMOTE_ID)
        val backup = FixtureBackup(
            version = RECENT_VERSION,
            timestamp = 1L,
            data = FixtureData(
                tasks = listOf(TaskBackup(task = task, caldavTasks = listOf(caldavTask))),
                caldavAccounts = listOf(localAccount.copy(uuid = "remote-account")),
                caldavCalendars = listOf(
                    localCalendar.copy(uuid = "remote-cal", account = "remote-account"),
                ),
            ),
        )
        val result = importer().import(fixtureJson.encodeToString(backup))

        assertEquals(1, result.importCount)
        assertEquals(1, db.caldavDao().getAccounts().size)
        assertEquals(1, db.caldavDao().getCalendars().size)
        val importedTask = db.taskDao().fetch(task.uuid)
        assertNotNull(importedTask)
        val importedCaldavTask = db.caldavDao().getTasks(importedTask!!.id).single()
        assertEquals("local-cal", importedCaldavTask.calendar)
    }

    @Test
    fun pre128BackupMigratesRecurrenceInMemoryLikeTheApp() = runBlocking {
        val recurrence = "FROM=COMPLETION;R=DAILY"
        val task = Task(title = "Recurring", recurrence = recurrence)
        val backup = FixtureBackup(
            version = PRE_12_8_VERSION,
            timestamp = 1L,
            data = FixtureData(tasks = listOf(TaskBackup(task = task))),
        )
        val result = importer().import(fixtureJson.encodeToString(backup))

        assertEquals(1, result.importCount)
        // Parity with the Android importer: the migrated values are applied
        // to the in-memory task after createNew and are not re-saved, so the
        // database keeps the original recurrence string.
        assertEquals(recurrence, db.taskDao().fetch(task.uuid)?.recurrence)
    }

    private companion object {
        const val TAG_NAME = "work"
        const val RECENT_VERSION = 151300
        const val PRE_12_8_VERSION = 120700
        const val CALDAV_URL = "https://dav.example.com/dav"
        const val CALDAV_CAL_URL = "https://dav.example.com/cal"
        const val REMOTE_ID = "r1"
    }
}

private val fixtureJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

private fun simpleTaskBackup(task: Task): FixtureBackup = FixtureBackup(
    version = 151300,
    timestamp = 1L,
    data = FixtureData(tasks = listOf(TaskBackup(task = task))),
)

/** Shape of the native backup file: `{version, timestamp, data: {...}}`. */
@Serializable
private data class FixtureBackup(
    val version: Int,
    val timestamp: Long,
    val data: FixtureData,
)

@Serializable
private data class FixtureData(
    val tasks: List<TaskBackup> = emptyList(),
    val tags: List<TagData> = emptyList(),
    val places: List<Place> = emptyList(),
    val filters: List<Filter> = emptyList(),
    val caldavAccounts: List<CaldavAccount> = emptyList(),
    val caldavCalendars: List<CaldavCalendar> = emptyList(),
)
