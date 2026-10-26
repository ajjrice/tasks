package org.tasks.compose.edit

/**
 * Common duration formatting and text input parsing for the timer rows.
 * No Android DateUtils dependency: hours:minutes display, bare numbers mean
 * minutes, and `h`/`m` suffixes or colons are accepted on input.
 */
object DurationFormat {
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private const val SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR

    private val hoursOnlyPattern = Regex("^(\\d{1,4})h$")
    private val hoursThenUnitPattern = Regex("^(\\d{1,4})h(\\d{1,2})(m|s)?$")
    private val plainPattern = Regex("^(\\d{1,4})(m|s)?$")

    fun format(seconds: Int): String {
        if (seconds <= 0) {
            return "0m"
        }
        val hours = seconds / SECONDS_PER_HOUR
        val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val secs = seconds % SECONDS_PER_MINUTE
        val tail = if (secs > 0) {
            "${secs.toString().padStart(2, '0')}s"
        } else {
            ""
        }
        return when {
            hours > 0 -> "${hours}h${minutes.toString().padStart(2, '0')}m$tail"
            minutes > 0 -> "${minutes}m$tail"
            else -> "${secs}s"
        }
    }

    /**
     * Parses user input into seconds. Accepts: `90` (bare = minutes), `45m`,
     * `45s`, `2h`, `1h30`, `1h30m`, `1:30` (hours:minutes) and
     * `1:30:00` (hours:minutes:seconds). Returns null when the input is not
     * a valid duration.
     */
    fun parse(input: String): Int? {
        val text = input.trim().lowercase()
        if (text.isEmpty()) {
            return null
        }
        parseColonNotation(text)?.let { return it }
        hoursThenUnitPattern.matchEntire(text)?.let { m ->
            val (hours, middle, suffix) = m.destructured
            return hours.toInt() * SECONDS_PER_HOUR +
                    (if (suffix == "s") middle.toInt() else middle.toInt() * SECONDS_PER_MINUTE)
        }
        hoursOnlyPattern.matchEntire(text)?.let { m ->
            return m.groupValues[1].toInt() * SECONDS_PER_HOUR
        }
        plainPattern.matchEntire(text)?.let { m ->
            val (number, suffix) = m.destructured
            return if (suffix == "s") number.toInt() else number.toInt() * SECONDS_PER_MINUTE
        }
        return null
    }

    private fun parseColonNotation(text: String): Int? {
        if (!text.contains(":")) {
            return null
        }
        val parts = text.split(":")
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) {
            return null
        }
        val values = parts.map { it.toInt() }
        return when (values.size) {
            2 -> values[0] * SECONDS_PER_HOUR + values[1] * SECONDS_PER_MINUTE
            else -> values[0] * SECONDS_PER_HOUR + values[1] * SECONDS_PER_MINUTE + values[2]
        }
    }
}
