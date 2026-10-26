package org.tasks.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tasks.backup.BackupFileFormat
import org.tasks.backup.BackupFiles
import org.tasks.backup.LocalBackupExporter
import org.tasks.backup.LocalBackupImporter
import org.tasks.date.DateTimeUtils
import org.tasks.preferences.TasksPreferences
import org.tasks.time.DateTime

/** Outcome of a backup or import operation, shown by the settings pane. */
sealed interface BackupOperationResult {
    val error: String?
        get() = null
}

data class BackupSucceeded(
    val taskCount: Int,
) : BackupOperationResult

data class ImportSucceeded(
    val taskCount: Int,
    val imported: Int,
    val skipped: Int,
) : BackupOperationResult

data class BackupOperationFailed(
    val message: String?,
) : BackupOperationResult {
    override val error: String?
        get() = message
}

/** A backup file present in the backup directory. */
data class BackupFileEntry(
    val filename: String,
    val timestamp: Long,
)

/**
 * State and operations for the desktop "Backups" settings pane.
 *
 * Manual backup and restore (Phase 1 of SPEC-backup-desktop.md). The
 * preference keys mirror the Android app so a directory configured on
 * one platform is understood by the other.
 */
open class BackupsViewModel(
    private val tasksPreferences: TasksPreferences,
    private val exporter: LocalBackupExporter,
    private val importer: LocalBackupImporter,
    private val backupFiles: BackupFiles,
    private val versionCode: () -> Int,
) : ViewModel() {
    var backupDir by mutableStateOf(runBlocking { loadBackupDir() })
        private set
    var lastBackup by mutableStateOf(
        runBlocking { tasksPreferences.get(TasksPreferences.lastLocalBackup, 0L) }
            .takeIf { it > 0L }
    )
        private set
    var ignoreWarnings by mutableStateOf(
        runBlocking { tasksPreferences.get(TasksPreferences.backupsIgnoreWarnings, false) }
    )
        private set
    var availableBackups by mutableStateOf(emptyList<BackupFileEntry>())
        private set
    var inFlight by mutableStateOf(false)
        private set
    var lastResult by mutableStateOf<BackupOperationResult?>(null)
        private set

    val showStaleBackupWarning: Boolean
        get() = !ignoreWarnings && lastBackup?.let { it < staleThresholdMillis } == true

    private val staleThresholdMillis: Long
        get() = DateTimeUtils.newDateTime().startOfDay().minusDays(2).millis

    fun updateBackupDir(dir: String) {
        backupDir = dir
        viewModelScope.launch {
            tasksPreferences.set(TasksPreferences.backupDir, dir)
        }
    }

    fun updateIgnoreWarnings(enabled: Boolean) {
        ignoreWarnings = enabled
        viewModelScope.launch {
            tasksPreferences.set(TasksPreferences.backupsIgnoreWarnings, enabled)
        }
    }

    fun loadAvailableBackups() {
        viewModelScope.launch {
            availableBackups = try {
                backupFiles.listFilenames(backupDir)
                    .filter { BackupFileFormat.isBackupFile(it) }
                    .mapNotNull { name ->
                        BackupFileFormat.getTimestampFromFilename(name)
                            ?.let { BackupFileEntry(name, it) }
                    }
                    .sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                Logger.w("BackupsViewModel") { "listing backups failed: ${e.message}" }
                availableBackups
            }
        }
    }

    fun backupNow(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        if (inFlight) return
        inFlight = true
        lastResult = null
        viewModelScope.launch {
            try {
                val now = DateTimeUtils.newDateTime()
                backupFiles.ensureDirectory(backupDir)
                val exported = exporter.export(
                    version = versionCode(),
                    timestamp = now.millis,
                    onProgress = onProgress,
                )
                backupFiles.write(backupDir, BackupFileFormat.exportNameFor(now), exported.content)
                pruneOldAutoBackups()
                lastBackup = now.millis
                tasksPreferences.set(TasksPreferences.lastLocalBackup, now.millis)
                lastResult = BackupSucceeded(taskCount = exported.taskCount)
            } catch (e: Exception) {
                Logger.e("BackupsViewModel") { "backup failed: $e" }
                lastResult = BackupOperationFailed(e.message)
            } finally {
                inFlight = false
            }
        }
    }

    fun importBackup(filename: String, onProgress: (Int) -> Unit = { _ -> }) {
        if (inFlight) return
        inFlight = true
        lastResult = null
        viewModelScope.launch {
            try {
                val text = backupFiles.read(backupDir, filename)
                val result = importer.import(text, onProgress)
                lastResult = ImportSucceeded(
                    taskCount = result.taskCount,
                    imported = result.importCount,
                    skipped = result.skipCount,
                )
            } catch (e: Exception) {
                Logger.e("BackupsViewModel") { "import failed: $e" }
                lastResult = BackupOperationFailed(e.message)
            } finally {
                inFlight = false
            }
        }
    }

    /** Keep only the newest [BACKUPS_TO_KEEP] automatic backup files. */
    private suspend fun pruneOldAutoBackups() {
        val obsolete = backupFiles.listFilenames(backupDir)
            .filter {
                BackupFileFormat.isBackupFile(it) &&
                    it.startsWith(BackupFileFormat.BACKUP_PREFIX + '.')
            }
            .sortedByDescending { BackupFileFormat.getTimestampFromFilename(it) ?: 0L }
            .drop(BACKUPS_TO_KEEP)
        obsolete.forEach { backupFiles.delete(backupDir, it) }
    }

    private suspend fun loadBackupDir(): String =
        tasksPreferences.get(TasksPreferences.backupDir, "")
            .takeIf { it.isNotBlank() }
            ?: backupFiles.defaultDirectory()

    private companion object {
        const val BACKUPS_TO_KEEP = 7
    }
}
