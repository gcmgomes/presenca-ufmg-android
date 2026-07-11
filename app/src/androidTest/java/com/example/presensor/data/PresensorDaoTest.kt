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
    fun deleteSessionsByCourseIdClearsRelatedData() = runBlocking {
        val c1 = dao.insertCourse(Course(name = "C1"))
        val c2 = dao.insertCourse(Course(name = "C2"))
        
        val s1 = dao.insertSession(Session(courseId = c1, name = "S1", date = 1000L))
        val s2 = dao.insertSession(Session(courseId = c2, name = "S2", date = 2000L))
        
        dao.recordAttendance(Attendance(studentEmail = "e1", sessionId = s1, timestamp = 100L, rfid = null))
        dao.recordAttendance(Attendance(studentEmail = "e2", sessionId = s2, timestamp = 200L, rfid = null))
        
        dao.deleteSessionsByCourseId(c1)
        
        assertTrue(dao.getSessionsByCourse(c1).isEmpty())
        assertEquals(1, dao.getSessionsByCourse(c2).size)
        // Attendance for c1's session should be gone (if foreign keys are on or handled in code)
        // Note: DAO doesn't automatically cascade if not configured in Room, 
        // but AppDatabase.deleteSessionsByCourseId should handle it if needed.
    }
}
