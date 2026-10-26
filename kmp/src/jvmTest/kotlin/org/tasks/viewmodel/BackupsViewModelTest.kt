package org.tasks.viewmodel

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.tasks.DatabaseTest
import org.tasks.InMemoryDataStore
import org.tasks.backup.BackupFileFormat
import org.tasks.backup.BackupFiles
import org.tasks.backup.LocalBackupExporter
import org.tasks.backup.LocalBackupImporter
import org.tasks.data.db.Database
import org.tasks.data.entity.Task
import org.tasks.preferences.TasksPreferences
import org.tasks.time.DateTime

@OptIn(ExperimentalCoroutinesApi::class)
class BackupsViewModelTest : DatabaseTest() {
    private val preferences = TasksPreferences(InMemoryDataStore())
    private val fakeFiles = FakeBackupFiles()

    private fun exporter() = LocalBackupExporter(
        database = db,
        vtodoCache = mock(),
    )

    private fun importer() = LocalBackupImporter(
        database = db,
        taskSaver = mock(),
        taskMover = mock(),
        vtodoCache = mock(),
        refreshBroadcaster = mock(),
    )

    private fun viewModel(): BackupsViewModel = BackupsViewModel(
        tasksPreferences = preferences,
        exporter = exporter(),
        importer = importer(),
        backupFiles = fakeFiles,
        versionCode = { VERSION_CODE },
    )

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The VM's viewModelScope runs on Main (Unconfined), but the Room DAOs
     * suspend on Room's real-time executor, so completion is not tied to
     * virtual time: poll until the expected state is visible.
     */
    private suspend fun awaitVmState(condition: suspend () -> Boolean) {
        withTimeout(POLL_TIMEOUT_MS) {
            while (!condition()) delay(POLL_DELAY_MS)
        }
    }

    private suspend fun awaitOperationDone(vm: BackupsViewModel) {
        awaitVmState { !vm.inFlight && vm.lastResult != null }
    }

    @Test
    fun backupNowWritesUserFileAndUpdatesState() = runBlocking {
        db.taskDao().createNew(Task(title = "Back me up"))
        val vm = viewModel()

        vm.backupNow()
        awaitOperationDone(vm)

        assertEquals(DEFAULT_DIR, vm.backupDir)
        val names = fakeFiles.listFilenames(DEFAULT_DIR)
        assertEquals(1, names.size)
        assertTrue(BackupFileFormat.isBackupFile(names.single()))
        assertTrue(names.single().startsWith(BackupFileFormat.EXPORT_PREFIX + '.'))
        assertNotNull(vm.lastBackup)
        assertTrue(vm.lastBackup!! > 0L)
        assertTrue(preferences.get(TasksPreferences.lastLocalBackup, 0L) > 0L)
        val result = vm.lastResult
        assertTrue(result is BackupSucceeded)
        assertEquals(1, (result as BackupSucceeded).taskCount)
        assertFalse(vm.inFlight)
        assertFalse(vm.showStaleBackupWarning)
    }

    @Test
    fun importBackupImportsTasksAndReportsResult() = runBlocking {
        db.taskDao().createNew(Task(title = "Seed task"))
        val seedExport = exporter().export(version = VERSION_CODE, timestamp = 1L).content
        val filename = "user.260101T1200.json"
        fakeFiles.write(DEFAULT_DIR, filename, seedExport)
        val vm = viewModel()

        vm.loadAvailableBackups()
        awaitVmState { vm.availableBackups.size == 1 }
        assertEquals(listOf(filename), vm.availableBackups.map { it.filename })

        vm.importBackup(filename)
        awaitOperationDone(vm)

        val result = vm.lastResult
        assertTrue(result is ImportSucceeded)
        assertEquals(1, (result as ImportSucceeded).taskCount)
        assertFalse(vm.inFlight)
    }

    @Test
    fun importBackupOfMissingFileReportsFailure() = runBlocking {
        val vm = viewModel()

        vm.importBackup("user.260101T1200.json")
        awaitOperationDone(vm)

        val result = vm.lastResult
        assertTrue(result is BackupOperationFailed)
        assertNotNull((result as? BackupOperationFailed)?.error)
    }

    @Test
    fun staleWarningFollowsLastBackupAndIgnoreWarnings() = runBlocking {
        val oldTimestamp = DateTime(2020, 1, 1).millis
        preferences.set(TasksPreferences.lastLocalBackup, oldTimestamp)
        val vm = viewModel()

        assertTrue(vm.showStaleBackupWarning)

        vm.updateIgnoreWarnings(true)
        awaitVmState { preferences.get(TasksPreferences.backupsIgnoreWarnings, false) }
        assertFalse(vm.showStaleBackupWarning)
        assertTrue(preferences.get(TasksPreferences.backupsIgnoreWarnings, false))
    }

    @Test
    fun backupNowKeepsOnlyTheNewestAutoBackups() = runBlocking {
        for (day in 1..KEEP_COUNT + 2) {
            val name = BackupFileFormat.backupNameFor(DateTime(2026, 1, day, 3, 0))
            fakeFiles.write(DEFAULT_DIR, name, "{}")
        }
        val vm = viewModel()

        vm.backupNow()
        awaitOperationDone(vm)

        val autoNames = fakeFiles.listFilenames(DEFAULT_DIR)
            .filter { it.startsWith(BackupFileFormat.BACKUP_PREFIX + '.') }
        assertEquals(KEEP_COUNT, autoNames.size)
        val newest = BackupFileFormat.backupNameFor(DateTime(2026, 1, KEEP_COUNT + 2, 3, 0))
        assertTrue(autoNames.contains(newest))
        assertFalse(autoNames.contains(BackupFileFormat.backupNameFor(DateTime(2026, 1, 1, 3, 0))))
    }

    @Test
    fun updateBackupDirPersistsToPreferences() = runBlocking {
        val vm = viewModel()

        vm.updateBackupDir("/custom/backups")
        awaitVmState { preferences.get(TasksPreferences.backupDir, "") == "/custom/backups" }

        assertEquals("/custom/backups", vm.backupDir)
        assertEquals(
            "/custom/backups",
            preferences.get(TasksPreferences.backupDir, ""),
        )
    }

    /** In-memory [BackupFiles] for VM tests. */
    private class FakeBackupFiles : BackupFiles {
        private val namesByDir = mutableMapOf<String, MutableList<String>>()
        private val contents = mutableMapOf<Pair<String, String>, String>()

        override fun defaultDirectory(): String = DEFAULT_DIR

        override suspend fun ensureDirectory(dir: String) {
            namesByDir.getOrPut(dir) { mutableListOf() }
        }

        override suspend fun listFilenames(dir: String): List<String> =
            namesByDir[dir].orEmpty().toList()

        override suspend fun read(dir: String, filename: String): String =
            contents[dir to filename] ?: error("missing file: $filename")

        override suspend fun write(dir: String, filename: String, content: String) {
            namesByDir.getOrPut(dir) { mutableListOf() }.add(filename)
            contents[dir to filename] = content
        }

        override suspend fun delete(dir: String, filename: String) {
            namesByDir[dir]?.remove(filename)
            contents.remove(dir to filename)
        }
    }

    private companion object {
        const val DEFAULT_DIR = "/tmp/backups"
        const val VERSION_CODE = 151300
        const val KEEP_COUNT = 7
        const val POLL_TIMEOUT_MS = 10_000L
        const val POLL_DELAY_MS = 25L
    }
}
