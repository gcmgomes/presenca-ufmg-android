package com.example.presensor.data

import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CourseCacheTest {

    private lateinit var cache: CourseCache

    @Before
    fun setup() {
        cache = CourseCache()
    }

    @Test
    fun `computeFromMinimalData populates cache correctly`() {
        val courseId = 1L
        val student1 = Student("s1@example.com", "Student 1")
        val student2 = Student("s2@example.com", "Student 2")
        val session1 = Session(id = 10, courseId = courseId, name = "Session 1", date = 1000L)
        val attendance1 = AttendanceRecord(
            timestamp = 100L,
            studentName = student1.name,
            studentRfid = null,
            studentEmail = student1.email,
            sessionName = session1.name,
            sessionId = session1.id
        )

        cache.computeFromMinimalData(
            courseId = courseId,
            sessions = listOf(session1),
            attendances = listOf(attendance1),
            students = listOf(student1, student2)
        )

        assertEquals(courseId, cache.courseId)
        assertEquals(1, cache.allSessions.size)
        assertEquals(1, cache.allAttendance.size)
        assertEquals(1, cache.activeStudents.size)
        assertEquals(student1.email, cache.activeStudents[0].email)
        assertTrue(cache.activeStudentEmails.contains(student1.email))
        assertTrue(cache.sessionIds.contains(session1.id))
    }

    @Test
    fun `getFilteredStudents returns correct results`() {
        val student1 = Student("s1@example.com", "John Doe")
        val student2 = Student("s2@example.com", "Jane Smith")
        cache.activeStudents = listOf(student1, student2)

        assertEquals(2, cache.getFilteredStudents("").size)
        assertEquals(1, cache.getFilteredStudents("John").size)
        assertEquals(student1, cache.getFilteredStudents("John")[0])
        assertEquals(1, cache.getFilteredStudents("jane").size)
        assertEquals(student2, cache.getFilteredStudents("jane")[0])
    }

    @Test
    fun `deleteSession updates cache`() {
        val session1 = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        val session2 = Session(id = 2, courseId = 1, name = "S2", date = 2000L)
        val attendance1 = AttendanceRecord(100L, "SN", null, "SE", "S1", 1)
        
        cache.computeFromMinimalData(
            courseId = 1L,
            sessions = listOf(session1, session2),
            attendances = listOf(attendance1),
            students = emptyList()
        )

        cache.deleteSession(session1)

        assertEquals(1, cache.allSessions.size)
        assertEquals(2L, cache.allSessions[0].id)
        assertTrue(cache.allAttendance.isEmpty())
    }

    @Test
    fun `updateSessionLock updates existing session status`() {
        val session = Session(id = 1, courseId = 1, name = "S1", date = 1000L, isLocked = false)
        cache.allSessions = listOf(session)
        
        cache.updateSessionLock(1, true)
        
        assertTrue(cache.allSessions[0].isLocked)
    }

    @Test
    fun `updateSession updates existing session details`() {
        val session = Session(id = 1, courseId = 1, name = "Old Name", date = 1000L)
        cache.allSessions = listOf(session)
        
        val updatedSession = Session(id = 1, courseId = 1, name = "New Name", date = 2000L)
        cache.updateSession(updatedSession)
        
        assertEquals("New Name", cache.allSessions[0].name)
        assertEquals(2000L, cache.allSessions[0].date)
    }

    @Test
    fun `addAttendance adds new student and record`() {
        val student = Student("new@example.com", "New Student")
        val session = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        val record = AttendanceRecord(100L, "New Student", null, "new@example.com", "S1", 1)
        
        cache.addAttendance(student, session, record)
        
        assertTrue(cache.activeStudentEmails.contains("new@example.com"))
        assertEquals(1, cache.activeStudents.size)
        assertEquals(1, cache.allAttendance.size)
    }

    @Test
    fun `clear resets cache`() {
        cache.courseId = 1L
        cache.activeStudents = listOf(Student("e", "n"))
        
        cache.clear()

        assertNull(cache.courseId)
        assertTrue(cache.activeStudents.isEmpty())
        assertTrue(cache.activeStudentEmails.isEmpty())
        assertTrue(cache.allSessions.isEmpty())
        assertTrue(cache.allAttendance.isEmpty())
        assertTrue(cache.sessionIds.isEmpty())
    }
}
