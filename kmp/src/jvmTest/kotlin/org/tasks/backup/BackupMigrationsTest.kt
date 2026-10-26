package org.tasks.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.Task
import org.tasks.themes.TasksIcons

/** Unit tests for the pure version-gated migration helpers. */
class BackupMigrationsTest {
    @Test
    fun legacyColorMapsKnownIndices() {
        assertEquals(BLUE_GREY_500.toInt(), legacyColor(0))
        assertEquals(WHITE_100.toInt(), legacyColor(WHITE_INDEX))
    }

    @Test
    fun legacyColorFallsBackForUnknownIndices() {
        assertEquals(0, legacyColor(UNKNOWN_INDEX))
        assertEquals(FALLBACK_COLOR, legacyColor(UNKNOWN_INDEX, FALLBACK_COLOR))
    }

    @Test
    fun migrateLegacyIconConvertsNumericIndices() {
        assertEquals(TasksIcons.LABEL, "1".migrateLegacyIcon())
        assertEquals(TasksIcons.PLACE, LEGACY_PLACE_INDEX.migrateLegacyIcon())
        assertNull(LEGACY_NONE_INDEX.migrateLegacyIcon())
    }

    @Test
    fun migrateLegacyIconPassesThroughNames() {
        assertEquals(LEGACY_FLAG_NAME, LEGACY_FLAG_NAME.migrateLegacyIcon())
        assertNull(null.migrateLegacyIcon())
    }

    @Test
    fun repeatFromDetectsCompletionAnchor() {
        assertEquals(Task.RepeatFrom.COMPLETION_DATE, "FROM=COMPLETION;R=DAILY".repeatFrom())
        assertEquals(Task.RepeatFrom.DUE_DATE, "R=DAILY".repeatFrom())
        assertEquals(Task.RepeatFrom.DUE_DATE, "".repeatFrom())
        assertEquals(Task.RepeatFrom.DUE_DATE, null.repeatFrom())
    }

    @Test
    fun withoutFromStripsTheFromClause() {
        // Same regex as the app: an unanchored FROM=COMPLETION at the start
        // leaves the following semicolon behind.
        assertEquals(";R=DAILY", "FROM=COMPLETION;R=DAILY".withoutFrom())
        assertEquals("R=DAILY", "R=DAILY;FROM=COMPLETION".withoutFrom())
        assertEquals("", "FROM=COMPLETION".withoutFrom())
        assertNull(null.withoutFrom())
    }

    @Test
    fun reconstructDirtyVersionCoversAllBranches() {
        assertEquals(1L to 0L, reconstructDirtyVersion(null, LATE_TIMESTAMP))
        assertEquals(1L to 0L, reconstructDirtyVersion(0L, LATE_TIMESTAMP))
        assertEquals(2L to 1L, reconstructDirtyVersion(EARLY_TIMESTAMP, LATE_TIMESTAMP))
        assertEquals(1L to 1L, reconstructDirtyVersion(LATE_TIMESTAMP, LATE_TIMESTAMP))
    }

    @Test
    fun legacyAlarmsFromFlagsBuildsOneAlarmPerFlag() {
        assertEquals(0, legacyAlarmsFromFlags(0, task = FLAGGED_TASK_ID).size)
        val flags = Task.NOTIFY_AT_START or Task.NOTIFY_AT_DEADLINE
        assertEquals(2, legacyAlarmsFromFlags(flags, task = FLAGGED_TASK_ID).size)
    }

    @Test
    fun convertLegacyPictureUriHandlesJsonWrappers() {
        assertEquals(
            CONTENT_URI,
            convertLegacyPictureUri("{\"uri\": \"$CONTENT_URI\"}"),
        )
        assertEquals(
            "file:///tmp/p.jpg",
            convertLegacyPictureUri("{\"path\": \"/tmp/p.jpg\"}"),
        )
        assertNull(convertLegacyPictureUri(CONTENT_URI))
        assertNull(convertLegacyPictureUri(null))
        assertNull(convertLegacyPictureUri("{broken"))
    }

    @Test
    fun canonicalizedBackupUrlOnlyTouchesCaldavAndTasksAccounts() {
        val account = CaldavAccount(
            uuid = "acct",
            name = "n",
            url = CALDAV_URL,
            username = "u",
            accountType = CaldavAccount.TYPE_TASKS,
        )
        assertEquals(CALDAV_URL, account.canonicalizedBackupUrl().url)

        val google = account.copy(accountType = CaldavAccount.TYPE_GOOGLE_TASKS)
        assertEquals(google.url, google.canonicalizedBackupUrl().url)

        val calendar = CaldavCalendar(
            uuid = "cal",
            account = "acct",
            url = CALDAV_CAL_URL,
        )
        assertEquals(CALDAV_CAL_URL, calendar.canonicalizedBackupUrl(account).url)
        assertEquals(calendar.url, calendar.canonicalizedBackupUrl(google).url)
    }

    private companion object {
        const val BLUE_GREY_500 = 0xFF607D8B
        const val WHITE_100 = 0xFFFFFFFF
        const val WHITE_INDEX = 20
        const val UNKNOWN_INDEX = 99
        const val FALLBACK_COLOR = 0x11223344
        const val LEGACY_PLACE_INDEX = "1050"
        const val LEGACY_NONE_INDEX = "-1"
        const val LEGACY_FLAG_NAME = "flag"
        const val LATE_TIMESTAMP = 100L
        const val EARLY_TIMESTAMP = 50L
        const val FLAGGED_TASK_ID = 42L
        const val CONTENT_URI = "content://media/x"
        const val CALDAV_URL = "https://dav.example.com/dav"
        const val CALDAV_CAL_URL = "https://dav.example.com/cal"
    }
}
