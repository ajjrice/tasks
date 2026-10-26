package org.tasks.backup

import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopBackupFilesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun dir(): String = tempFolder.newFolder().absolutePath

    @Test
    fun defaultDirectoryIsDocumentsTasksOrgBackups() {
        val files = DesktopBackupFiles()
        val expected = File(System.getProperty("user.home"), "Documents/Tasks.org/backups")
        assertEquals(expected.absolutePath, files.defaultDirectory())
    }

    @Test
    fun listFilenamesOnMissingDirectoryIsEmpty() = runTest {
        val files = DesktopBackupFiles()
        val missing = File(dir(), "missing")
        assertTrue(files.listFilenames(missing.absolutePath).isEmpty())
    }

    @Test
    fun writeReadListDeleteRoundTrip() = runTest {
        val files = DesktopBackupFiles()
        val d = dir()
        files.ensureDirectory(d)
        assertTrue(File(d).isDirectory)

        files.write(d, "user.20261026T0405.json", "{}")
        files.write(d, "auto.20261026T0305.json", "{}")
        File(d, "not-a-backup.txt").writeText("ignore me")

        val names = files.listFilenames(d).sorted()
        assertEquals(listOf("auto.20261026T0305.json", "not-a-backup.txt", "user.20261026T0405.json"), names)
        assertEquals("{}", files.read(d, "user.20261026T0405.json"))

        files.delete(d, "auto.20261026T0305.json")
        assertFalse(File(d, "auto.20261026T0305.json").exists())
    }
}
