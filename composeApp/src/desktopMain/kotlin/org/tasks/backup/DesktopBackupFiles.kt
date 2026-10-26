package org.tasks.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * `java.nio.file` implementation of [BackupFiles] for the desktop app.
 *
 * Default location: `~/Documents/Tasks.org/backups` (Alex's decision —
 * visible in Finder/Explorer, stable across the three desktop OSes).
 */
internal class DesktopBackupFiles : BackupFiles {
    override fun defaultDirectory(): String =
        File(System.getProperty("user.home"), "Documents/Tasks.org/backups").absolutePath

    override suspend fun ensureDirectory(dir: String) {
        withContext(Dispatchers.IO) { Files.createDirectories(Path.of(dir)) }
    }

    override suspend fun listFilenames(dir: String): List<String> = withContext(Dispatchers.IO) {
        val path = Path.of(dir)
        if (!Files.isDirectory(path)) {
            emptyList()
        } else {
            Files.list(path).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { name -> Files.isRegularFile(path.resolve(name)) }
                    .toList()
            }
        }
    }

    override suspend fun read(dir: String, filename: String): String = withContext(Dispatchers.IO) {
        Files.readString(Path.of(dir, filename))
    }

    override suspend fun write(dir: String, filename: String, content: String) {
        withContext(Dispatchers.IO) { Files.writeString(Path.of(dir, filename), content) }
    }

    override suspend fun delete(dir: String, filename: String) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(Path.of(dir, filename)) }
    }
}
