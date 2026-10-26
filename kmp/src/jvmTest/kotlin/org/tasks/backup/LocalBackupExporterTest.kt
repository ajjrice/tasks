package org.tasks.backup

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.tasks.DatabaseTest
import org.tasks.caldav.VtodoCache
import org.tasks.data.db.Database
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Filter
import org.tasks.data.entity.Geofence
import org.tasks.data.entity.Place
import org.tasks.data.entity.Tag
import org.tasks.data.entity.TagData
import org.tasks.data.entity.Task

/**
 * Exporter tests: the produced JSON must be valid native-format backup
 * content, and a second in-memory database must be able to import it
 * (round trip) — the core cross-platform interchange guarantee.
 */
class LocalBackupExporterTest : DatabaseTest() {
    private val caldavDao = db.caldavDao()

    private fun exporter() = LocalBackupExporter(
        database = db,
        vtodoCache = mock<VtodoCache>(),
    )

    private fun freshDatabase(): Database = Room.inMemoryDatabaseBuilder<Database>()
        .setDriver(BundledSQLiteDriver())
        .addCallback(Database.CALLBACK)
        .build()

    private fun importerFor(db2: Database) = LocalBackupImporter(
        database = db2,
        taskSaver = mock(),
        taskMover = mock(),
        vtodoCache = mock(),
        refreshBroadcaster = mock(),
    )

    @Test
    fun exportProducesNativeShapedJson() = runBlocking {
        seedDatabase()

        val exported = exporter().export(version = RECENT_VERSION, timestamp = EXPORT_TIMESTAMP)
        val root = Json.parseToJsonElement(exported.content).jsonObject

        assertEquals(RECENT_VERSION.toLong(), root["version"]!!.jsonPrimitive.long)
        assertEquals(EXPORT_TIMESTAMP, root["timestamp"]!!.jsonPrimitive.long)
        assertEquals(1, exported.taskCount)

        val data = root["data"]!!.jsonObject
        assertEquals(1, data["tasks"]!!.jsonArray.size)
        DATA_KEYS.forEach { key ->
            assertTrue("missing section: $key", data.containsKey(key))
        }
        assertTrue((data["intPrefs"] as JsonObject).isEmpty())
    }

    @Test
    fun secondDatabaseImportsAnExport() = runBlocking {
        val (task, tagData, place) = seedDatabase()

        val exported = exporter().export(version = RECENT_VERSION, timestamp = EXPORT_TIMESTAMP)
        val other = freshDatabase()
        try {
            val result = importerFor(other).import(exported.content)

            assertEquals(1, result.taskCount)
            assertEquals(1, result.importCount)
            assertEquals(0, result.skipCount)

            val otherTask = other.taskDao().fetch(task.uuid)
            assertNotNull(otherTask)
            assertEquals(task.title, otherTask?.title)
            assertNotNull(other.tagDataDao().getTagByName(tagData.name!!))
            assertEquals(1, other.tagDao().getByTagUid(tagData.remoteId!!).size)
            assertNotNull(other.locationDao().getByUid(place.uid!!))
            assertNotNull(other.filterDao().getByName(FILTER_TITLE))
            assertNotNull(other.caldavDao().getAccountByUuid(ACCOUNT_UUID))
            assertNotNull(other.caldavDao().getCalendarByUuid(CALENDAR_UUID))
            val otherCaldavTask = other.caldavDao().getTasks(otherTask!!.id).single()
            assertEquals(REMOTE_ID, otherCaldavTask.remoteId)
            assertNull(otherCaldavTask.remoteParent)
        } finally {
            other.close()
        }
    }

    private fun seedDatabase(): Triple<Task, TagData, Place> = runBlocking {
        val task = Task(title = "Round trip")
        db.taskDao().createNew(task)
        val tagData = TagData(name = "home")
        db.tagDataDao().insert(tagData)
        db.tagDao().insert(
            Tag(
                task = task.id,
                taskUid = task.uuid,
                tagUid = tagData.remoteId,
                name = tagData.name,
            )
        )
        val place = Place(name = "HQ", latitude = 1.5, longitude = 2.5)
        db.locationDao().insert(place)
        db.locationDao().insert(Geofence(task = task.id, place = place.uid, isArrival = true))
        db.filterDao().insert(Filter(title = FILTER_TITLE))
        val account = CaldavAccount(
            uuid = ACCOUNT_UUID,
            name = "N",
            url = CALDAV_URL,
            username = USERNAME,
        )
        caldavDao.insert(account)
        val calendar = CaldavCalendar(
            uuid = CALENDAR_UUID,
            account = ACCOUNT_UUID,
            name = "C",
            url = CALDAV_CAL_URL,
        )
        caldavDao.insert(calendar)
        caldavDao.insert(CaldavTask(task = task.id, calendar = CALENDAR_UUID, remoteId = REMOTE_ID))
        Triple(task, tagData, place)
    }

    private companion object {
        const val RECENT_VERSION = 151300
        const val EXPORT_TIMESTAMP = 1732540800000L
        const val FILTER_TITLE = "Work"
        const val ACCOUNT_UUID = "acct-1"
        const val CALENDAR_UUID = "cal-1"
        const val CALDAV_URL = "https://dav.example.com/dav"
        const val CALDAV_CAL_URL = "https://dav.example.com/cal"
        const val USERNAME = "alex@example.com"
        const val REMOTE_ID = "r1"
        val DATA_KEYS = listOf(
            "tasks",
            "places",
            "tags",
            "filters",
            "caldavAccounts",
            "caldavCalendars",
            "taskListMetadata",
            "taskAttachments",
            "intPrefs",
            "longPrefs",
            "stringPrefs",
            "boolPrefs",
            "setPrefs",
        )
    }
}
