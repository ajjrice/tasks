package org.tasks.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tasks.time.DateTime

class BackupFileFormatTest {
    @Test
    fun exportAndBackupNamesFollowTheNativeGrammar() {
        val now = DateTime(2026, 10, 26, 9, 5)
        assertEquals("user.20261026T0905.json", BackupFileFormat.exportNameFor(now))
        assertEquals("auto.20261026T0905.json", BackupFileFormat.backupNameFor(now))
    }

    @Test
    fun timestampRoundTripsThroughTheFilename() {
        val now = DateTime(2026, 10, 26, 23, 59)
        assertEquals(
            now.millis,
            BackupFileFormat.getTimestampFromFilename(BackupFileFormat.backupNameFor(now)),
        )
        assertEquals(
            now.millis,
            BackupFileFormat.getTimestampFromFilename(BackupFileFormat.exportNameFor(now)),
        )
    }

    @Test
    fun twoDigitYearsMapTo20xx() {
        assertEquals(
            DateTime(2026, 1, 1, 0, 0).millis,
            BackupFileFormat.getTimestampFromFilename("auto.260101T0000.json"),
        )
        assertEquals(
            DateTime(2099, 12, 31, 23, 59).millis,
            BackupFileFormat.getTimestampFromFilename("user.991231T2359.json"),
        )
    }

    @Test
    fun theDashSeparatorIsAcceptedLikeNative() {
        assertEquals(
            DateTime(2024, 2, 29, 12, 0).millis,
            BackupFileFormat.getTimestampFromFilename("auto.20240229-1200.json"),
        )
    }

    @Test
    fun namesOutsideTheGrammarAreRejected() {
        assertTrue(BackupFileFormat.isBackupFile("auto.20261026T0905.json"))
        assertTrue(BackupFileFormat.isBackupFile("user.20261026T0905.json"))
        assertFalse(BackupFileFormat.isBackupFile("backup.json"))
        assertFalse(BackupFileFormat.isBackupFile("auto.20261026.json"))
        assertFalse(BackupFileFormat.isBackupFile("auto.20261026T0905.txt"))
        assertFalse(BackupFileFormat.isBackupFile(null))
        assertNull(BackupFileFormat.getTimestampFromFilename("not a backup"))
    }

    @Test
    fun cleanupMatcherOnlyMatchesAutoFiles() {
        assertTrue(BackupFileFormat.BACKUP_CLEANUP_MATCHER.matches("auto.20261026T0905.json"))
        assertTrue(BackupFileFormat.BACKUP_CLEANUP_MATCHER.matches("auto.261026T0905.json"))
        assertFalse(BackupFileFormat.BACKUP_CLEANUP_MATCHER.matches("user.20261026T0905.json"))
    }
}
