package org.tasks.timer

import kotlinx.datetime.Instant
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimerLogTest {

    private val start = Instant.parse("2026-10-26T14:32:05.000Z")
    private val stop = Instant.parse("2026-10-26T15:32:09.000Z")
    private val stopAfterTenMinutes = plusTenMinutes(stop)

    private fun plusTenMinutes(instant: Instant): Instant =
        Instant.fromEpochMilliseconds(instant.toEpochMilliseconds() + TEN_MINUTES_MS)

    private companion object {
        const val TEN_MINUTES_MS = 600_000L
    }

    @Test
    fun `timestamp round-trips through format and parse in a positive offset zone`() {
        val offset = UtcOffset.orNull(1, 0)!!
        val formatted = TimerLog.formatTimestamp(start, offset)
        assertEquals(start, TimerLog.parseTimestamp(formatted))
        assertTrue(formatted.endsWith("+01:00"), formatted)
    }

    @Test
    fun `timestamp round-trips in a negative offset zone`() {
        val offset = UtcOffset.orNull(-5, 0)!!
        val formatted = TimerLog.formatTimestamp(start, offset)
        assertEquals(start, TimerLog.parseTimestamp(formatted))
        assertTrue(formatted.contains("-05:00"), formatted)
    }

    @Test
    fun `appendStart adds an open line to an empty log`() {
        val log = TimerLog.appendStart("", start)
        val parsed = TimerLog.parse(log)
        assertEquals(start.toEpochMilliseconds(), parsed.timerStart)
        assertEquals(0, parsed.elapsedSeconds)
        assertTrue(log.startsWith(TimerLog.START_MARKER))
    }

    @Test
    fun `appendStart appends to an existing log`() {
        val first = TimerLog.appendStart("", start)
        val second = TimerLog.appendStart(first, stop)
        assertEquals(1, second.count { it == '\n' })
        val parsed = TimerLog.parse(second)
        assertEquals(stop.toEpochMilliseconds(), parsed.timerStart)
        assertEquals(0, parsed.elapsedSeconds)
    }

    @Test
    fun `closeOpenLine closes the most recent open line`() {
        val open = TimerLog.appendStart("", start)
        val closed = TimerLog.closeOpenLine(open, stop, 3604)
        val parsed = TimerLog.parse(closed)
        assertEquals(0L, parsed.timerStart)
        assertEquals(3604, parsed.elapsedSeconds)
        assertTrue(parsed.malformed.isEmpty())
    }

    @Test
    fun `closeOpenLine on a log without an open line is unchanged`() {
        val log = "nothing to see"
        assertEquals(log, TimerLog.closeOpenLine(log, stop, 10))
    }

    @Test
    fun `parse sums closed sessions and keeps the latest open one`() {
        val log = buildString {
            appendLine(TimerLog.closeOpenLine(TimerLog.appendStart("", start), stop, 3604))
            appendLine(TimerLog.closeOpenLine(TimerLog.appendStart("", stop), stopAfterTenMinutes, 600))
            append(TimerLog.formatStart(stopAfterTenMinutes))
        }
        val parsed = TimerLog.parse(log)
        assertEquals(4204, parsed.elapsedSeconds)
        assertEquals(stopAfterTenMinutes.toEpochMilliseconds(), parsed.timerStart)
    }

    @Test
    fun `parse preserves malformed lines without counting them`() {
        val log = "garbage line\n" + TimerLog.closeOpenLine(TimerLog.appendStart("", start), stop, 300)
        val parsed = TimerLog.parse(log)
        assertEquals(300, parsed.elapsedSeconds)
        assertEquals(listOf("garbage line"), parsed.malformed)
    }

    @Test
    fun `open line with a bad timestamp is malformed`() {
        val log = "started this task: 14:32"
        val parsed = TimerLog.parse(log)
        assertEquals(0L, parsed.timerStart)
        assertEquals(listOf(log), parsed.malformed)
    }

    @Test
    fun `parse is empty for blank input`() {
        val parsed = TimerLog.parse("  \n  ")
        assertEquals(TimerLog.Parsed(), parsed)
    }

    @Test
    fun `timestamp round-trips through format and parse in the system zone`() {
        val formatted = TimerLog.formatTimestamp(start)
        assertEquals(start, TimerLog.parseTimestamp(formatted))
    }

    @Test
    fun `synthesizeLegacy produces a closed line for elapsed and open line for a running timer`() {
        val startMs = start.toEpochMilliseconds()
        val log = TimerLog.synthesizeLegacy(900, startMs)
        val parsed = TimerLog.parse(log)
        assertEquals(900, parsed.elapsedSeconds)
        assertEquals(startMs, parsed.timerStart)
    }

    @Test
    fun `synthesizeLegacy is blank for a never-used timer`() {
        assertEquals("", TimerLog.synthesizeLegacy(0, 0L))
    }
}
