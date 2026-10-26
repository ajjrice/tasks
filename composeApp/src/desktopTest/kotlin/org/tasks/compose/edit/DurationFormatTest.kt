package org.tasks.compose.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationFormatTest {
    @Test
    fun formatZero() {
        assertEquals("0m", DurationFormat.format(0))
        assertEquals("0m", DurationFormat.format(-5))
    }

    @Test
    fun formatSecondsOnly() {
        assertEquals("45s", DurationFormat.format(45))
        assertEquals("59s", DurationFormat.format(59))
    }

    @Test
    fun formatMinutes() {
        assertEquals("1m", DurationFormat.format(60))
        assertEquals("45m", DurationFormat.format(45 * 60))
        assertEquals("1m30s", DurationFormat.format(90))
    }

    @Test
    fun formatHours() {
        assertEquals("1h00m", DurationFormat.format(3600))
        assertEquals("1h30m", DurationFormat.format(5400))
        assertEquals("2h05m", DurationFormat.format(7500))
    }

    @Test
    fun formatWithSeconds() {
        assertEquals("1h30m05s", DurationFormat.format(5400 + 5))
    }

    @Test
    fun parseBareNumberIsMinutes() {
        assertEquals(5400, DurationFormat.parse("90"))
        assertEquals(45 * 60, DurationFormat.parse("45"))
    }

    @Test
    fun parseWithSuffixes() {
        assertEquals(45 * 60, DurationFormat.parse("45m"))
        assertEquals(45, DurationFormat.parse("45s"))
    }

    @Test
    fun parseHoursOnly() {
        assertEquals(7200, DurationFormat.parse("2h"))
    }

    @Test
    fun parseHoursAndMinutes() {
        assertEquals(5400, DurationFormat.parse("1h30"))
        assertEquals(5400, DurationFormat.parse("1h30m"))
        assertEquals(3630, DurationFormat.parse("1h30s"))
    }

    @Test
    fun parseColonHoursMinutes() {
        assertEquals(5400, DurationFormat.parse("1:30"))
    }

    @Test
    fun parseColonHoursMinutesSeconds() {
        assertEquals(5405, DurationFormat.parse("1:30:05"))
    }

    @Test
    fun parseIsCaseInsensitiveAndTrims() {
        assertEquals(5400, DurationFormat.parse("  1H30  "))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(DurationFormat.parse(""))
        assertNull(DurationFormat.parse("   "))
        assertNull(DurationFormat.parse("abc"))
        assertNull(DurationFormat.parse("1:"))
        assertNull(DurationFormat.parse("1:2:3:4"))
    }
}
