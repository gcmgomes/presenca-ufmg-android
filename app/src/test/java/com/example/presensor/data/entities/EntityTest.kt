package com.example.presensor.data.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class EntityTest {

    @Test
    fun `Course default values`() {
        val course = Course(name = "Test Course")
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        assertEquals("Test Course", course.name)
        assertEquals(currentYear, course.year)
        // Semester depends on current month, but it should be 1 or 2
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
        val expectedSemester = if (currentMonth < 6) 1 else 2
        assertEquals(expectedSemester, course.semester)
        assertEquals(0L, course.id)
    }

    @Test
    fun `Session default values`() {
        val session = Session(courseId = 1L, name = "S1", date = 1000L)
        assertEquals(1L, session.courseId)
        assertEquals("S1", session.name)
        assertEquals(1000L, session.date)
        assertFalse(session.isLocked)
        assertEquals(0L, session.id)
    }

    @Test
    fun `Student default values`() {
        val student = Student("e@test.com", "Name")
        assertEquals("e@test.com", student.email)
        assertEquals("Name", student.name)
        assertNull(student.rfid)
    }

    @Test
    fun `Attendance default values`() {
        val attendance = Attendance(rfid = "TAG", studentEmail = "e@t.com", sessionId = 10L, timestamp = 2000L)
        assertEquals("TAG", attendance.rfid)
        assertEquals("e@t.com", attendance.studentEmail)
        assertEquals(10L, attendance.sessionId)
        assertEquals(2000L, attendance.timestamp)
        assertEquals(0L, attendance.id)
    }

    @Test
    fun `AttendanceRecord data class`() {
        val record = AttendanceRecord(100L, "N", "T", "E", "S", 1L)
        assertEquals(100L, record.timestamp)
        assertEquals("N", record.studentName)
        assertEquals("T", record.studentRfid)
        assertEquals("E", record.studentEmail)
        assertEquals("S", record.sessionName)
        assertEquals(1L, record.sessionId)
    }
}
