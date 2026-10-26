package org.tasks.backup

import org.tasks.time.DateTime

/**
 * Backup file naming + timestamp conventions.
 *
 * Ported from the Android `BackupConstants`. The filename grammar is the
 * cross-platform wire contract between the Android and desktop apps, so it
 * must stay in sync with the native app:
 * `auto.<yyyyMMdd'T'HHmm>.json` for automatic backups,
 * `user.<yyyyMMdd'T'HHmm>.json` for manual ones.
 */
object BackupFileFormat {
    const val EXPORT_PREFIX = "user"
    const val BACKUP_PREFIX = "auto"

    private const val FILENAME_FORMAT = "yyyyMMdd'T'HHmm"

    private val MATCHER =
        Regex("""(auto|user)\.(\d{2,4})(\d{2})(\d{2})[T-](\d{2})(\d{2})\.json""")

    val BACKUP_CLEANUP_MATCHER =
        Regex("""auto\.\d{6,8}[T-]\d{4}\.json""")

    fun isBackupFile(name: String?): Boolean =
        name?.let { MATCHER.matches(it) } ?: false

    /**
     * Extracts the timestamp encoded in a backup filename, or null when the
     * name doesn't match the grammar. Two-digit years are mapped to 20xx
     * (e.g. `261026` = 2026), mirroring the native matcher.
     */
    fun getTimestampFromFilename(name: String): Long? =
        MATCHER.matchEntire(name)?.let { match ->
            val groups = match.groupValues
            val year = groups[2].toInt().let { if (it > 2000) it else it + 2000 }
            DateTime(
                year = year,
                month = groups[3].toInt(),
                day = groups[4].toInt(),
                hour = groups[5].toInt(),
                minute = groups[6].toInt(),
            ).millis
        }

    fun exportNameFor(now: DateTime): String =
        "${EXPORT_PREFIX}.${now.toString(FILENAME_FORMAT)}.json"

    fun backupNameFor(now: DateTime): String =
        "${BACKUP_PREFIX}.${now.toString(FILENAME_FORMAT)}.json"
}
