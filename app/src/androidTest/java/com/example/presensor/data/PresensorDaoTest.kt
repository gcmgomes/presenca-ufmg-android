package com.example.presensor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.presensor.data.entities.Attendance
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PresensorDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PresensorDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.dao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndGetCourse() = runBlocking {
        val course = Course(name = "Android Testing", year = 2024, semester = 1)
        val id = dao.insertCourse(course)
        val courses = dao.getAllCourses()
        assertEquals(1, courses.size)
        assertEquals("Android Testing", courses[0].name)
        assertEquals(id, courses[0].id)
    }

    @Test
    fun insertAndGetStudent() = runBlocking {
        val student = Student(email = "test@example.com", name = "Test Student")
        dao.insertStudents(listOf(student))
        val students = dao.getAllStudents()
        assertEquals(1, students.size)
        assertEquals("test@example.com", students[0].email)
    }

    @Test
    fun bindTagToStudent() = runBlocking {
        val email = "test@example.com"
        val student = Student(email = email, name = "Test Student")
        dao.insertStudents(listOf(student))
        
        dao.bindTagToStudent("TAG123", email)
        
        val updatedStudent = dao.getStudentByRfid("TAG123")
        assertNotNull(updatedStudent)
        assertEquals(email, updatedStudent?.email)
        assertEquals("TAG123", updatedStudent?.rfid)
    }

    @Test
    fun clearAndBindTag() = runBlocking {
        val s1 = Student(email = "s1@example.com", name = "S1", rfid = "TAG1")
        val s2 = Student(email = "s2@example.com", name = "S2")
        dao.insertStudents(listOf(s1, s2))
        
        // TAG1 is currently with s1. Now we want to bind it to s2.
        dao.clearAndBind("TAG1", "s2@example.com")
        
        val studentWithTag = dao.getStudentByRfid("TAG1")
        assertEquals("s2@example.com", studentWithTag?.email)
        
        val all = dao.getAllStudents()
        val updatedS1 = all.find { it.email == "s1@example.com" }
        assertNull(updatedS1?.rfid)
    }

    @Test
    fun attendanceWorkflow() = runBlocking {
        val courseId = dao.insertCourse(Course(name = "C1"))
        val sessionId = dao.insertSession(Session(courseId = courseId, name = "S1", date = 1000L))
        val email = "student@example.com"
        dao.insertStudents(listOf(Student(email = email, name = "Student 1")))

        assertFalse(dao.isPresent(email, sessionId))

        dao.recordAttendance(Attendance(studentEmail = email, sessionId = sessionId, timestamp = 2000L, rfid = null))

        assertTrue(dao.isPresent(email, sessionId))
        
        val records = dao.getAttendanceRecordsForSession(sessionId)
        assertEquals(1, records.size)
        assertEquals(email, records[0].studentEmail)
        assertEquals("S1", records[0].sessionName)
    }

    @Test
    fun deleteCourseCascades() = runBlocking {
        val cId = dao.insertCourse(Course(name = "C1"))
        val sId = dao.insertSession(Session(courseId = cId, name = "S1", date = 1000L))
        dao.recordAttendance(Attendance(studentEmail = "e", sessionId = sId, timestamp = 100L, rfid = null))
        
        dao.deleteCourse(Course(id = cId, name = "C1"))
        
        assertTrue(dao.getAllCourses().isEmpty())
        assertTrue(dao.getSessionsByCourse(cId).isEmpty())
        assertTrue(dao.getAttendanceRecordsForSession(sId).isEmpty())
    }

    @Test
    fun deleteSessionsByCourseIdCascades() = runBlocking {
        val cId = dao.insertCourse(Course(name = "C1"))
        val sId = dao.insertSession(Session(courseId = cId, name = "S1", date = 1000L))
        dao.recordAttendance(Attendance(studentEmail = "e", sessionId = sId, timestamp = 100L, rfid = null))
        
        dao.deleteSessionsByCourseId(cId)
        
        assertTrue(dao.getSessionsByCourse(cId).isEmpty())
        assertTrue(dao.getAttendanceRecordsForSession(sId).isEmpty())
    }

    @Test
    fun insertSessionsWithConflict() = runBlocking {
        val cId = dao.insertCourse(Course(name = "C1"))
        val s1 = Session(id = 20, courseId = cId, name = "S1", date = 1000L)
        dao.insertSessions(listOf(s1))
        
        // Try inserting same ID again
        val s2 = Session(id = 20, courseId = cId, name = "S1 Duplicate", date = 2000L)
        val ids = dao.insertSessions(listOf(s2))
        
        assertEquals(1, ids.size)
        
        val sessions = dao.getSessionsByCourse(cId)
        assertEquals(1, sessions.size)
        assertEquals("S1", sessions[0].name)
    }

    @Test
    fun getUnboundStudents() = runBlocking {
        val s1 = Student("e1", "Bound", "TAG")
        val s2 = Student("e2", "Unbound", null)
        dao.insertStudents(listOf(s1, s2))
        
        val unbound = dao.getUnboundStudents()
        assertEquals(1, unbound.size)
        assertEquals("e2", unbound[0].email)
    }

    @Test
    fun updateSessionLock() = runBlocking {
        val cId = dao.insertCourse(Course(name = "C1"))
        val sId = dao.insertSession(Session(courseId = cId, name = "S1", date = 1000L))
        
        dao.updateSessionLock(sId, true)
        
        val sessions = dao.getSessionsByCourse(cId)
        assertTrue(sessions[0].isLocked)
    }

    @Test
    fun updateSession() = runBlocking {
        val cId = dao.insertCourse(Course(name = "C1"))
        val sId = dao.insertSession(Session(courseId = cId, name = "S1", date = 1000L))
        
        val session = dao.getSessionsByCourse(cId)[0]
        val updated = session.copy(name = "New Name")
        dao.updateSession(updated)
        
        val sessions = dao.getSessionsByCourse(cId)
        assertEquals("New Name", sessions[0].name)
    }

    @Test
    fun deleteAttendancesBySessionId() = runBlocking {
        val cId = dao.insertCourse(Course(name = "C1"))
        val sId = dao.insertSession(Session(courseId = cId, name = "S1", date = 1000L))
        dao.recordAttendance(Attendance(studentEmail = "e", sessionId = sId, timestamp = 100L, rfid = null))
        
        dao.deleteAttendancesBySessionId(sId)
        
        assertTrue(dao.getAttendanceRecordsForSession(sId).isEmpty())
    }

    @Test
    fun getAllAttendanceForCourse() = runBlocking {
        val cId = dao.insertCourse(Course(name = "C1"))
        val s1 = dao.insertSession(Session(courseId = cId, name = "S1", date = 1000L))
        val s2 = dao.insertSession(Session(courseId = cId, name = "S2", date = 2000L))
        dao.insertStudents(listOf(Student("e1", "S1"), Student("e2", "S2")))
        
        dao.recordAttendance(Attendance(studentEmail = "e1", sessionId = s1, timestamp = 100L, rfid = null))
        dao.recordAttendance(Attendance(studentEmail = "e2", sessionId = s2, timestamp = 200L, rfid = null))
        
        val all = dao.getAllAttendanceForCourse(cId)
        assertEquals(2, all.size)
    }
}
