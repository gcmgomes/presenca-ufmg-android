package com.example.presensor.data

import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `initial state is empty`() {
        assertNull(cache.courseId)
        assertTrue(cache.activeStudents.isEmpty())
        assertTrue(cache.activeStudentEmails.isEmpty())
        assertTrue(cache.allSessions.isEmpty())
        assertTrue(cache.allAttendance.isEmpty())
        assertTrue(cache.sessionIds.isEmpty())
        assertTrue(cache.allStudents.isEmpty())
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
    fun `computeFromMinimalData with null courseId`() {
        cache.computeFromMinimalData(null, emptyList(), emptyList(), emptyList())
        assertNull(cache.courseId)
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
    fun `getFilteredStudents with blank query returns all active students`() {
        cache.activeStudents = listOf(Student("s1", "N1"), Student("s2", "N2"))
        assertEquals(2, cache.getFilteredStudents("   ").size)
    }

    @Test
    fun `deleteSession updates cache and recalculates active students`() {
        val student1 = Student("s1@test.com", "S1")
        val student2 = Student("s2@test.com", "S2")
        val session1 = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        val session2 = Session(id = 2, courseId = 1, name = "S2", date = 2000L)
        val att1 = AttendanceRecord(100L, "S1", null, "s1@test.com", "S1", 1)
        val att2 = AttendanceRecord(200L, "S2", null, "s2@test.com", "S2", 2)
        
        cache.computeFromMinimalData(
            courseId = 1L,
            sessions = listOf(session1, session2),
            attendances = listOf(att1, att2),
            students = listOf(student1, student2)
        )
        
        assertEquals(2, cache.activeStudents.size)

        // Delete session 1, student 1 should be removed from activeStudents
        cache.deleteSession(session1)

        assertEquals(1, cache.allSessions.size)
        assertEquals(2L, cache.allSessions[0].id)
        assertEquals(1, cache.allAttendance.size)
        assertEquals(2L, cache.allAttendance[0].sessionId)
        assertEquals(1, cache.activeStudents.size)
        assertEquals("s2@test.com", cache.activeStudents[0].email)
    }

    @Test
    fun `updateSessionLock updates existing session status`() {
        val session = Session(id = 1, courseId = 1, name = "S1", date = 1000L, isLocked = false)
        cache.allSessions = listOf(session)
        
        cache.updateSessionLock(1, true)
        
        assertTrue(cache.allSessions[0].isLocked)
    }

    @Test
    fun `updateSessionLock does nothing if session not found`() {
        val session = Session(id = 1, courseId = 1, name = "S1", date = 1000L, isLocked = false)
        cache.allSessions = listOf(session)
        
        cache.updateSessionLock(99, true)
        
        assertFalse(cache.allSessions[0].isLocked)
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
    fun `updateSession does nothing if session not found`() {
        val session = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        cache.allSessions = listOf(session)
        
        val otherSession = Session(id = 99, courseId = 1, name = "Other", date = 2000L)
        cache.updateSession(otherSession)
        
        assertEquals("S1", cache.allSessions[0].name)
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
    fun `addAttendance for already active student only adds record`() {
        val student = Student("s1@test.com", "S1")
        val session = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        val record1 = AttendanceRecord(100L, "S1", null, "s1@test.com", "S1", 1)
        
        cache.addAttendance(student, session, record1)
        assertEquals(1, cache.activeStudents.size)
        assertEquals(1, cache.allAttendance.size)
        
        val record2 = AttendanceRecord(200L, "S1", null, "s1@test.com", "S1", 1)
        cache.addAttendance(student, session, record2)
        
        assertEquals(1, cache.activeStudents.size)
        assertEquals(2, cache.allAttendance.size)
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
