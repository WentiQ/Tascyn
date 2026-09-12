package com.example.tascyn

import com.example.tascyn.data.*
import com.example.tascyn.domain.NotionFormulas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotionFormulasTest {

    @Test
    fun testParseMinimumTimeToMinutes() {
        assertEquals(3150L, NotionFormulas.parseMinimumTimeToMinutes("2d 4h 30m"))
        assertEquals(90L, NotionFormulas.parseMinimumTimeToMinutes("1h 30m"))
        assertEquals(45L, NotionFormulas.parseMinimumTimeToMinutes("45m"))
        assertEquals(1440L, NotionFormulas.parseMinimumTimeToMinutes("1d"))
        assertEquals(120L, NotionFormulas.parseMinimumTimeToMinutes("2h"))
        assertEquals(0L, NotionFormulas.parseMinimumTimeToMinutes(""))
    }

    @Test
    fun testFormatMinutesToDuration() {
        assertEquals("2d 4h 30m", NotionFormulas.formatMinutesToDuration(3150L))
        assertEquals("0d 1h 30m", NotionFormulas.formatMinutesToDuration(90L))
        assertEquals("0d 0h 45m", NotionFormulas.formatMinutesToDuration(45L))
        assertEquals("1d 0h 0m", NotionFormulas.formatMinutesToDuration(1440L))
        assertEquals("0d 2h 0m", NotionFormulas.formatMinutesToDuration(120L))
        assertEquals("0d 0h 0m", NotionFormulas.formatMinutesToDuration(0L))
    }

    @Test
    fun testFormula41TimesheetDuration() {
        val now = System.currentTimeMillis()

        // Active session
        val activeSession = TimesheetSession(
            id = "s1",
            title = "Test Task",
            status = TimesheetStatus.IN_PROGRESS,
            startTime = now - 120_000,
            endTime = null,
            taskId = "t1"
        )
        val activeResult = NotionFormulas.calculateTimesheetDuration(activeSession, now)
        assertTrue(activeResult.contains("(In Progress)"))

        // Less than 60s
        val shortSession = TimesheetSession(
            id = "s2",
            title = "Test Task",
            status = TimesheetStatus.DONE,
            startTime = now - 30_000,
            endTime = now,
            taskId = "t1"
        )
        val shortResult = NotionFormulas.calculateTimesheetDuration(shortSession, now)
        assertEquals("Duration: Hit Start Work", shortResult)

        // Normal session (1h 15m = 4500s)
        val normalSession = TimesheetSession(
            id = "s3",
            title = "Test Task",
            status = TimesheetStatus.DONE,
            startTime = now - 75 * 60 * 1000,
            endTime = now,
            taskId = "t1"
        )
        val normalResult = NotionFormulas.calculateTimesheetDuration(normalSession, now)
        assertEquals("Duration: 1h 15m 0s", normalResult)
    }

    @Test
    fun testFormula21TotalDurationRecursive() {
        val now = System.currentTimeMillis()
        val sessions = listOf(
            TimesheetSession("s1", "Parent", TimesheetStatus.DONE, now - 60 * 60 * 1000, now, "t_parent"),
            TimesheetSession("s2", "Subtask 1", TimesheetStatus.DONE, now - 30 * 60 * 1000, now, "t_sub1"),
            TimesheetSession("s3", "Subtask 2", TimesheetStatus.DONE, now - 45 * 60 * 1000, now, "t_sub2")
        )

        val parent = Task(id = "t_parent", title = "Parent")
        val sub1 = Task(id = "t_sub1", title = "Subtask 1", parentTaskId = "t_parent")
        val sub2 = Task(id = "t_sub2", title = "Subtask 2", parentTaskId = "t_parent")

        val allTasks = listOf(parent, sub1, sub2)

        val totalParent = NotionFormulas.calculateTotalDuration(parent, allTasks, sessions)
        assertEquals("0d 2h 15m", totalParent)

        val totalSub1 = NotionFormulas.calculateTotalDuration(sub1, allTasks, sessions)
        assertEquals("0d 0h 30m", totalSub1)
    }

    @Test
    fun testFormula22TimeLeftUrgencyBuckets() {
        val now = System.currentTimeMillis()
        val minTimeStr = "0d 2h 0m" // 120 minutes

        // 1. Overdue
        val taskOverdue = Task(id = "t1", title = "T1", dueDate = now - 3600 * 1000, minimumTimeRequired = minTimeStr)
        val resOverdue = NotionFormulas.calculateTimeLeft(taskOverdue, now)
        assertEquals(UrgencyLevel.OVERDUE, resOverdue.urgencyLevel)
        assertEquals("Overdue", resOverdue.rawText)

        // 2. Urgent (<= 1x minTime)
        val taskUrgent = Task(id = "t2", title = "T2", dueDate = now + 60 * 60 * 1000, minimumTimeRequired = minTimeStr)
        val resUrgent = NotionFormulas.calculateTimeLeft(taskUrgent, now)
        assertEquals(UrgencyLevel.URGENT, resUrgent.urgencyLevel)
        assertTrue(resUrgent.rawText.startsWith("Urgent"))

        // 3. Attention Needed (<= 2x minTime)
        val taskAttention = Task(id = "t3", title = "T3", dueDate = now + 180 * 60 * 1000, minimumTimeRequired = minTimeStr)
        val resAttention = NotionFormulas.calculateTimeLeft(taskAttention, now)
        assertEquals(UrgencyLevel.ATTENTION_NEEDED, resAttention.urgencyLevel)
        assertTrue(resAttention.rawText.startsWith("Attention Needed"))

        // 4. On Track (<= 4x minTime)
        val taskOnTrack = Task(id = "t4", title = "T4", dueDate = now + 400 * 60 * 1000, minimumTimeRequired = minTimeStr)
        val resOnTrack = NotionFormulas.calculateTimeLeft(taskOnTrack, now)
        assertEquals(UrgencyLevel.ON_TRACK, resOnTrack.urgencyLevel)
        assertTrue(resOnTrack.rawText.startsWith("On Track"))

        // 5. Normal (> 4x minTime)
        val taskNormal = Task(id = "t5", title = "T5", dueDate = now + 600 * 60 * 1000, minimumTimeRequired = minTimeStr)
        val resNormal = NotionFormulas.calculateTimeLeft(taskNormal, now)
        assertEquals(UrgencyLevel.NORMAL_TIME_LEFT, resNormal.urgencyLevel)
    }

    @Test
    fun testFormula23QuadrantEngineCalculations() {
        val now = System.currentTimeMillis()
        val minTimeStr = "0d 2h 0m"

        // Overdue + High + Not started -> Q1 (u=1, p=1, s=1 -> (1-1)*15 + (1-1)*5 + 1 = 1)
        val t1 = Task(id = "t1", title = "T1", dueDate = now - 3600 * 1000, minimumTimeRequired = minTimeStr, priority = TaskPriority.HIGH, status = TaskStatus.NOT_STARTED)
        val q1 = NotionFormulas.calculateQuadrant(t1, now)
        assertEquals(1, q1.qNumber)
        assertEquals("Recover Immediately", q1.action)

        // Urgent + High + Not started -> Q16 (u=2, p=1, s=1 -> (2-1)*15 + (1-1)*5 + 1 = 16)
        val t16 = Task(id = "t16", title = "T16", dueDate = now + 60 * 60 * 1000, minimumTimeRequired = minTimeStr, priority = TaskPriority.HIGH, status = TaskStatus.NOT_STARTED)
        val q16 = NotionFormulas.calculateQuadrant(t16, now)
        assertEquals(16, q16.qNumber)
        assertEquals("Do Now", q16.action)

        // Normal + Low + Done -> Q75 (u=5, p=3, s=5 -> (5-1)*15 + (3-1)*5 + 5 = 75)
        val t75 = Task(id = "t75", title = "T75", dueDate = now + 1000 * 60 * 1000, minimumTimeRequired = minTimeStr, priority = TaskPriority.LOW, status = TaskStatus.DONE)
        val q75 = NotionFormulas.calculateQuadrant(t75, now)
        assertEquals(75, q75.qNumber)
        assertEquals("Done / Archive", q75.action)
    }
}
