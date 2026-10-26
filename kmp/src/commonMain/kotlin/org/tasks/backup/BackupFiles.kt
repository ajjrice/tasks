package org.tasks.backup

/**
 * File access for the backup directory.
 *
 * Kept behind an interface so [org.tasks.viewmodel.BackupsViewModel] and
 * the backup engine stay free of platform file APIs (commonMain); the
 * desktop implementation uses `java.nio.file`, tests use an in-memory
 * fake. All methods take a plain path string plus a file name; the
 * implementation is responsible for resolving and validating them.
 */
interface BackupFiles {
    /**
     * The default backup location on this platform
     * (e.g. `~/Documents/Tasks.org/backups`).
     */
    fun defaultDirectory(): String

    /** Creates the backup directory (and parents) when missing. */
    suspend fun ensureDirectory(dir: String)

    /** File names present in the directory, not recursive. */
    suspend fun listFilenames(dir: String): List<String>

    suspend fun read(dir: String, filename: String): String

    suspend fun write(dir: String, filename: String, content: String)

    suspend fun delete(dir: String, filename: String)
}
