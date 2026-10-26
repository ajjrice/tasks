package org.tasks.caldav

import kotlinx.datetime.Instant
import org.tasks.caldav.iCalendar.Companion.applyLocal
import org.tasks.caldav.iCalendar.Companion.estimateSeconds
import org.tasks.caldav.iCalendar.Companion.timerLog
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Task
import org.tasks.icalendar.parseVTodos
import org.tasks.icalendar.serialize
import org.tasks.icalendar.VTodo
import org.tasks.timer.TimerLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimerCalDavSyncTest {
    private val caldavTask = CaldavTask(task = 1, calendar = "cal-uuid", remoteId = "remote-1")
    private val start = Instant.parse("2026-10-26T14:32:05.000Z")

    private fun closedLog(): String {
        val open = TimerLog.appendStart("", start)
        return TimerLog.closeOpenLine(open, Instant.fromEpochMilliseconds(start.toEpochMilliseconds() + 3_604_000L), 3604)
    }

    private fun openLog(): String = TimerLog.appendStart("", start)

    private fun taskWithTimer(log: String, elapsed: Int = 0, timerStart: Long = 0, estimate: Int = 0) = Task(
        id = 1,
        title = "Timed task",
        timerLog = log,
        elapsedSeconds = elapsed,
        timerStart = timerStart,
        estimatedSeconds = estimate,
    )

    // region wire format (VTodo extension properties)

    @Test
    fun aMultilineTimerLogRoundTripsThroughTheWire() {
        val log = closedLog() + "\n" + openLog()
        val vtodo = VTodo(uid = "remote-1@example.com")
        vtodo.timerLog = log

        val parsed = parseVTodos(vtodo.serialize()).single()

        assertEquals(log, parsed.timerLog)
    }

    @Test
    fun theTimerPropertyIsEscapedAndFoldedOnTheWire() {
        val longLine = "started this task: 2026-10-26T14:32:05+00:00 and this line goes on well beyond the folding limit of seventy-five octets"
        val vtodo = VTodo(uid = "remote-1@example.com")
        vtodo.timerLog = longLine + "\n" + longLine

        val wire = vtodo.serialize()

        assertFalse(wire.contains("\nstarted"))
        assertTrue(wire.contains("X-TASKS-TIMER:started this task:"))
        assertTrue(wire.contains("\\n"))
        assertEquals(1, parseVTodos(wire).single().timerLog.orEmpty().count { it == '\n' })
    }

    @Test
    fun aBlankTimerLogWritesNoProperty() {
        val vtodo = VTodo(uid = "remote-1@example.com")
        vtodo.timerLog = ""

        val wire = vtodo.serialize()

        assertFalse(wire.contains("X-TASKS-TIMER"))
        val parsed = parseVTodos(wire).single()
        assertEquals(0, parsed.estimateSeconds)
    }

    @Test
    fun theEstimateRoundTripsAsSeconds() {
        val vtodo = VTodo(uid = "remote-1@example.com")
        vtodo.estimateSeconds = 5400

        val parsed = parseVTodos(vtodo.serialize()).single()

        assertTrue(vtodo.serialize().contains("X-TASKS-EST-DUR:5400s"))
        assertEquals(5400, parsed.estimateSeconds)
    }

    @Test
    fun aZeroEstimateWritesNoProperty() {
        val vtodo = VTodo(uid = "remote-1@example.com")
        vtodo.estimateSeconds = 0

        assertFalse(vtodo.serialize().contains("X-TASKS-EST-DUR"))
    }

    @Test
    fun aTimerVTodoSerializesStablyWhenUnchanged() {
        val vtodo = VTodo(uid = "remote-1@example.com", dtStamp = 1_700_000_000_000L)
        vtodo.timerLog = closedLog()
        vtodo.estimateSeconds = 5400

        assertEquals(vtodo.serialize(), vtodo.serialize())
    }

    // endregion

    // region local write (applyLocal)

    @Test
    fun applyLocalWritesTheTimerAndEstimateFromTheTask() {
        val vtodo = VTodo(uid = "remote-1@example.com")
        vtodo.applyLocal(caldavTask, taskWithTimer(closedLog(), elapsed = 3604, estimate = 5400))

        val parsed = parseVTodos(vtodo.serialize()).single()

        assertEquals(closedLog(), parsed.timerLog)
        assertEquals(5400, parsed.estimateSeconds)
    }

    @Test
    fun applyLocalClearsTheTimerPropertiesForAnUntimedTask() {
        val vtodo = VTodo(uid = "remote-1@example.com")
        vtodo.applyLocal(caldavTask, taskWithTimer(closedLog(), estimate = 5400))
        vtodo.applyLocal(caldavTask, taskWithTimer(""))

        val wire = vtodo.serialize()

        assertFalse(wire.contains("X-TASKS-TIMER"))
        assertFalse(wire.contains("X-TASKS-EST-DUR"))
    }

    // endregion

    // region remote apply (Task.applyRemote)

    @Test
    fun aRemoteClosedLogIsAdoptedWithItsDerivedCache() {
        val task = taskWithTimer("")
        val remote = VTodo(uid = "remote-1@example.com")
        remote.timerLog = closedLog()
        remote.estimateSeconds = 5400

        task.applyRemote(remote, local = null)

        assertEquals(closedLog(), task.timerLog)
        assertEquals(3604, task.elapsedSeconds)
        assertEquals(0L, task.timerStart)
        assertEquals(5400, task.estimatedSeconds)
    }

    @Test
    fun aRemoteOpenLineLeavesTheTimerRunningLocally() {
        val task = taskWithTimer("")
        val remote = VTodo(uid = "remote-1@example.com")
        remote.timerLog = openLog()

        task.applyRemote(remote, local = null)

        assertEquals(start.toEpochMilliseconds(), task.timerStart)
        assertTrue(TimerLog.parse(task.timerLog).hasOpenSession)
    }

    @Test
    fun aLocallyChangedLogIsNotOverwrittenByTheRemote() {
        val localLog = openLog()
        val task = taskWithTimer(localLog)
        val local = VTodo(uid = "remote-1@example.com")
        local.timerLog = ""
        val remote = VTodo(uid = "remote-1@example.com")
        remote.timerLog = closedLog()

        task.applyRemote(remote, local)

        assertEquals(localLog, task.timerLog)
        assertEquals(0, task.elapsedSeconds)
    }

    @Test
    fun anUnchangedLocalLogTakesTheRemoteOne() {
        val log = closedLog()
        val task = taskWithTimer(log, elapsed = 3604)
        val local = VTodo(uid = "remote-1@example.com")
        local.timerLog = log
        val remote = VTodo(uid = "remote-1@example.com")
        remote.timerLog = log + "\n" + openLog()

        task.applyRemote(remote, local)

        assertEquals(log + "\n" + openLog(), task.timerLog)
        assertEquals(start.toEpochMilliseconds(), task.timerStart)
        assertEquals(3604, task.elapsedSeconds)
    }

    @Test
    fun aRemoteEstimateIsAdoptedUnlessChangedLocally() {
        val remote = VTodo(uid = "remote-1@example.com")
        remote.estimateSeconds = 900

        val fresh = taskWithTimer("")
        fresh.applyRemote(remote, local = null)
        assertEquals(900, fresh.estimatedSeconds)

        // Changed after the last push (pushed value was 0): local wins.
        val changed = taskWithTimer("", estimate = 1200)
        val local = VTodo(uid = "remote-1@example.com")
        changed.applyRemote(remote, local)
        assertEquals(1200, changed.estimatedSeconds)

        // No local change since the push: the newer remote value wins.
        val synced = taskWithTimer("", estimate = 1200)
        local.estimateSeconds = 1200
        synced.applyRemote(remote, local)
        assertEquals(900, synced.estimatedSeconds)
    }

    // endregion
}
