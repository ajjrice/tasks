package org.tasks.timer

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Append-only timer session log, synced as the X-TASKS-TIMER VTODO property.
 *
 * One line per session:
 *
 * ```
 * started this task: 2026-10-26T14:32:05+01:00
 * started this task: 2026-10-26T14:32:05+01:00 && stopped doing this task: 2026-10-26T15:32:09+01:00 == 3604s
 * ```
 *
 * A line without the `== <seconds>s` suffix is an open (running) session;
 * the timestamp on it is its start. Lifetime elapsed time is the sum of the
 * `==` values over all closed lines. Malformed lines are preserved verbatim
 * for round-trip but contribute nothing to the parsed result.
 *
 * The timerStart/elapsedSeconds columns on Task are the derived display cache
 * for this log; the log is the source of truth for the wire.
 */
object TimerLog {
    const val START_MARKER = "started this task: "
    const val STOP_MARKER = " && stopped doing this task: "

    data class Parsed(
        val timerStart: Long = 0L,
        val elapsedSeconds: Int = 0,
        val malformed: List<String> = emptyList(),
    ) {
        val hasOpenSession: Boolean get() = timerStart > 0L
    }

    private val durationPattern = Regex(""" == (\d+)s$""")
    private val timestampPattern = Regex(
        """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:Z|[+-]\d{2}:\d{2})""",
    )

    private const val SECONDS_PER_HOUR = 3600
    private const val SECONDS_PER_MINUTE = 60

    fun formatTimestamp(instant: Instant): String =
        formatTimestamp(instant, TimeZone.currentSystemDefault().offsetAt(instant))

    internal fun formatTimestamp(instant: Instant, offset: UtcOffset): String = buildString {
        val local = instant.toLocalDateTime(offset)
        val total = offset.totalSeconds
        val abs = kotlin.math.abs(total)
        append(local.year.toString().padStart(4, '0'))
        append('-')
        append(local.monthNumber.toString().padStart(2, '0'))
        append('-')
        append(local.dayOfMonth.toString().padStart(2, '0'))
        append('T')
        append(local.hour.toString().padStart(2, '0'))
        append(':')
        append(local.minute.toString().padStart(2, '0'))
        append(':')
        append(local.second.toString().padStart(2, '0'))
        append(if (total >= 0) '+' else '-')
        append((abs / SECONDS_PER_HOUR).toString().padStart(2, '0'))
        append(':')
        append(((abs % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toString().padStart(2, '0'))
    }

    fun parseTimestamp(text: String): Instant? =
        text.takeIf { timestampPattern.matches(it) }?.toInstant()

    fun formatStart(instant: Instant): String =
        START_MARKER + formatTimestamp(instant)

    fun appendStart(log: String, now: Instant): String {
        val line = formatStart(now)
        return if (log.isBlank()) line else "$log\n$line"
    }

    /**
     * Closes the most recent open line with the stop timestamp and the
     * session duration. Returns the log unchanged when there is no open line.
     */
    fun closeOpenLine(log: String, stoppedAt: Instant, sessionSeconds: Int): String {
        val lines = log.lineSequence().filter { it.isNotBlank() }.toMutableList()
        val openIndex = lines.indexOfLast { isOpenLine(it) }
        if (openIndex < 0) {
            return log
        }
        val closed = lines[openIndex] + STOP_MARKER + formatTimestamp(stoppedAt) + " == ${sessionSeconds}s"
        lines[openIndex] = closed
        return lines.joinToString("\n")
    }

    fun isOpenLine(line: String): Boolean =
        line.startsWith(START_MARKER) && !line.contains("==")

    fun parse(text: String): Parsed {
        var timerStart: Long = 0L
        var elapsed = 0
        val malformed = mutableListOf<String>()
        for (line in text.lineSequence().filter { it.isNotBlank() }) {
            val match = durationPattern.find(line)
            when {
                match != null -> elapsed += match.groupValues[1].toInt()
                isOpenLine(line) -> {
                    val ts = line.removePrefix(START_MARKER).trim()
                    parseTimestamp(ts)?.toEpochMilliseconds()?.let { timerStart = it }
                        ?: malformed.add(line)
                }
                else -> malformed.add(line)
            }
        }
        return Parsed(timerStart = timerStart, elapsedSeconds = elapsed, malformed = malformed)
    }

    /**
     * Synthesizes a log for legacy rows that have the timer columns but no
     * log (pre-migration data). The elapsed amount becomes a closed line
     * without timestamps (unknown); a running timer becomes an open line
     * with the recorded start.
     */
    fun synthesizeLegacy(elapsedSeconds: Int, timerStart: Long): String {
        val parts = mutableListOf<String>()
        if (elapsedSeconds > 0) {
            parts.add(
                START_MARKER + STOP_MARKER + " == ${elapsedSeconds}s",
            )
        }
        if (timerStart > 0) {
            parts.add(formatStart(Instant.fromEpochMilliseconds(timerStart)))
        }
        return parts.joinToString("\n")
    }
}
