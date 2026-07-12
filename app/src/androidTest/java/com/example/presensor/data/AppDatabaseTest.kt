package com.example.presensor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.AttendanceRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertSessionUpdatesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        db.insertSession(courseId, "New Session", 1000L)
        
        val cache = db.getCourseCache()
        assertEquals(courseId, cache.courseId)
        assertEquals(1, cache.allSessions.size)
        assertEquals("New Session", cache.allSessions[0].name)
        assertTrue(cache.sessionIds.contains(cache.allSessions[0].id))
    }

    @Test
    fun insertSessionDoesNotUpdateCacheIfDifferentCourse() = runBlocking {
        val c1 = db.insertCourse(Course(name = "C1"))
        val c2 = db.insertCourse(Course(name = "C2"))
        db.loadSessionsForCourse(Course(id = c1, name = "C1"))
        
        db.insertSession(c2, "Session for C2", 1000L)
        
        val cache = db.getCourseCache()
        assertEquals(c1, cache.courseId)
        assertTrue(cache.allSessions.isEmpty())
    }

    @Test
    fun deleteSessionUpdatesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.insertSession(courseId, "S1", 1000L)
        val session = db.getSessionsByCourse(courseId)[0]
        
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        db.deleteSession(session)
        
        val cache = db.getCourseCache()
        assertTrue(cache.allSessions.isEmpty())
        assertTrue(cache.sessionIds.isEmpty())
    }

    @Test
    fun deleteSessionsByCourseIdUpdatesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.insertSession(courseId, "S1", 1000L)
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        db.deleteSessionsByCourseId(courseId)
        
        val cache = db.getCourseCache()
        assertTrue(cache.allSessions.isEmpty())
        assertTrue(cache.sessionIds.isEmpty())
    }

    @Test
    fun deleteSessionsByCourseIdDoesNotClearCacheIfDifferentCourse() = runBlocking {
        val c1 = db.insertCourse(Course(name = "C1"))
        val c2 = db.insertCourse(Course(name = "C2"))
        db.insertSession(c1, "S1", 1000L)
        db.loadSessionsForCourse(Course(id = c1, name = "C1"))
        
        db.deleteSessionsByCourseId(c2)
        
        val cache = db.getCourseCache()
        assertEquals(1, cache.allSessions.size)
    }

    @Test
    fun deleteCourseClearsCacheIfMatches() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        db.deleteCourse(Course(id = courseId, name = "C1"))
        
        val cache = db.getCourseCache()
        assertNull(cache.courseId)
        assertTrue(cache.allSessions.isEmpty())
    }

    @Test
    fun updateSessionLockUpdatesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.insertSession(courseId, "S1", 1000L)
        val session = db.getSessionsByCourse(courseId)[0]
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        db.updateSessionLock(session.id, true)
        
        val cache = db.getCourseCache()
        assertTrue(cache.allSessions.find { it.id == session.id }?.isLocked == true)
    }

    @Test
    fun updateSessionUpdatesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.insertSession(courseId, "S1", 1000L)
        val session = db.getSessionsByCourse(courseId)[0]
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        val updated = session.copy(name = "Updated")
        db.updateSession(updated)
        
        val cache = db.getCourseCache()
        assertEquals("Updated", cache.allSessions[0].name)
    }

    @Test
    fun recordAttendanceUpdatesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.insertSession(courseId, "S1", 1000L)
        val session = db.getSessionsByCourse(courseId)[0]
        val student = Student("test@email.com", "Student Name", "TAG1")
        db.insertStudents(listOf(student))
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        db.recordAttendance(student, session, 2000L)
        
        val cache = db.getCourseCache()
        assertEquals(1, cache.allAttendance.size)
        assertEquals("test@email.com", cache.allAttendance[0].studentEmail)
    }

    @Test
    fun bindTagUpdatesCache() = runBlocking {
        val student = Student("test@email.com", "Student Name", null)
        db.insertStudents(listOf(student))
        db.preloadStudents()
        
        db.bindTagToStudent("NEW_TAG", "test@email.com")
        
        val cache = db.getCourseCache()
        val cachedStudent = cache.allStudents.find { it.email == "test@email.com" }
        assertEquals("NEW_TAG", cachedStudent?.rfid)
    }

    @Test
    fun clearTagUpdatesCache() = runBlocking {
        val student = Student("test@email.com", "Student Name", "OLD_TAG")
        db.insertStudents(listOf(student))
        db.preloadStudents()
        
        db.clearTagFromOthers("OLD_TAG")
        
        val cache = db.getCourseCache()
        val cachedStudent = cache.allStudents.find { it.email == "test@email.com" }
        assertNull(cachedStudent?.rfid)
    }

    @Test
    fun clearAndBindUpdatesCache() = runBlocking {
        val s1 = Student("s1@email.com", "S1", "TAG1")
        val s2 = Student("s2@email.com", "S2", null)
        db.insertStudents(listOf(s1, s2))
        db.preloadStudents()
        
        db.clearAndBind("TAG1", "s2@email.com")
        
        val cache = db.getCourseCache()
        assertNull(cache.allStudents.find { it.email == "s1@email.com" }?.rfid)
        assertEquals("TAG1", cache.allStudents.find { it.email == "s2@email.com" }?.rfid)
    }

    @Test
    fun getStudentsForCourseUsesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        val student = Student("test@email.com", "Student")
        db.insertStudents(listOf(student))
        val session = Session(courseId = courseId, name = "S1", date = 1000L)
        db.insertSessions(listOf(session))
        val sessionWithId = db.getSessionsByCourse(courseId)[0]
        db.recordAttendance(student, sessionWithId, 2000L)
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        db.getCourseCache().activeStudents = listOf(Student("cached@email.com", "Cached"))
        
        val students = db.getStudentsForCourse(courseId)
        assertEquals(1, students.size)
        assertEquals("cached@email.com", students[0].email)
    }

    @Test
    fun getAllStudentsUsesCache() = runBlocking {
        val student = Student("test@email.com", "Student")
        db.insertStudents(listOf(student))
        db.preloadStudents()
        
        db.getCourseCache().allStudents = listOf(Student("cached@email.com", "Cached"))
        
        val students = db.getAllStudents()
        assertEquals(1, students.size)
        assertEquals("cached@email.com", students[0].email)
    }

    @Test
    fun getAllStudentsFallsBackToDaoIfCacheEmpty() = runBlocking {
        val student = Student("test@email.com", "Student")
        db.insertStudents(listOf(student))
        
        val students = db.getAllStudents()
        assertEquals(1, students.size)
        assertEquals("test@email.com", students[0].email)
    }

    @Test
    fun getAttendanceRecordsForSessionUsesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.insertSession(courseId, "S1", 1000L)
        val session = db.getSessionsByCourse(courseId)[0]
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        db.getCourseCache().allAttendance = listOf(
            AttendanceRecord(2000L, "N", null, "E", "S1", session.id)
        )
        
        val records = db.getAttendanceRecordsForSession(session.id)
        assertEquals(1, records.size)
        assertEquals(2000L, records[0].timestamp)
    }

    @Test
    fun getStudentByRfidFallsBackToDao() = runBlocking {
        val student = Student("test@email.com", "Student", "TAG1")
        db.insertStudents(listOf(student))
        
        val s = db.getStudentByRfid("TAG1")
        assertEquals("test@email.com", s?.email)
    }

    @Test
    fun getUnboundStudentsUsesCache() = runBlocking {
        val student = Student("test@email.com", "Student", null)
        db.insertStudents(listOf(student))
        db.preloadStudents()
        
        db.getCourseCache().allStudents = listOf(Student("cached@email.com", "Cached", null))
        
        val unbound = db.getUnboundStudents()
        assertEquals(1, unbound.size)
        assertEquals("cached@email.com", unbound[0].email)
    }

    @Test
    fun deleteCourseDoesNotClearCacheIfDifferentCourse() = runBlocking {
        val c1Id = db.insertCourse(Course(name = "C1"))
        val c2Id = db.insertCourse(Course(name = "C2"))
        
        db.loadSessionsForCourse(Course(id = c1Id, name = "C1"))
        
        db.deleteCourse(Course(id = c2Id, name = "C2"))
        
        val cache = db.getCourseCache()
        assertEquals(c1Id, cache.courseId)
    }

    @Test
    fun insertSessionsUpdatesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        val sessions = listOf(
            Session(courseId = courseId, name = "S1", date = 1000L),
            Session(courseId = courseId, name = "S2", date = 2000L)
        )
        db.insertSessions(sessions)
        
        val cache = db.getCourseCache()
        assertEquals(2, cache.allSessions.size)
        assertTrue(cache.sessionIds.size >= 2)
    }

    @Test
    fun getSessionsByCourseUsesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.insertSession(courseId, "S1", 1000L)
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        
        db.getCourseCache().allSessions = listOf(Session(id = 99, courseId = courseId, name = "Cached", date = 2000L))
        
        val sessions = db.getSessionsByCourse(courseId)
        assertEquals(1, sessions.size)
        assertEquals("Cached", sessions[0].name)
    }

    @Test
    fun getStudentByRfidUsesCache() = runBlocking {
        val student = Student("test@email.com", "Student", "TAG1")
        db.insertStudents(listOf(student))
        db.preloadStudents()
        
        db.getCourseCache().allStudents = listOf(Student("cached@email.com", "Cached", "TAG1"))
        
        val s = db.getStudentByRfid("TAG1")
        assertEquals("cached@email.com", s?.email)
    }

    @Test
    fun insertStudentsFiltersDuplicatesForCache() = runBlocking {
        db.getCourseCache().allStudents = listOf(Student("s1@email.com", "S1"))
        
        val newStudents = listOf(
            Student("s1@email.com", "S1 New Name"), // duplicate email
            Student("s2@email.com", "S2")
        )
        db.insertStudents(newStudents)
        
        val cache = db.getCourseCache()
        assertEquals(2, cache.allStudents.size)
        assertTrue(cache.allStudents.any { it.email == "s2@email.com" })
    }

    @Test
    fun loadSessionsForCourseEarlyExitIfSameCourse() = runBlocking {
        val course = Course(id = 1, name = "C1")
        db.getCourseCache().courseId = 1
        db.getCourseCache().allSessions = listOf(Session(id = 10, courseId = 1, name = "S1", date = 1000L))
        
        db.loadSessionsForCourse(course)
        
        assertEquals(1, db.getCourseCache().allSessions.size)
        assertEquals("S1", db.getCourseCache().allSessions[0].name)
    }

    @Test
    fun deleteAttendancesBySessionIdUpdatesCache() = runBlocking {
        val courseId = db.insertCourse(Course(name = "C1"))
        db.insertSession(courseId, "S1", 1000L)
        val session = db.getSessionsByCourse(courseId)[0]
        val student = Student("e", "n")
        db.insertStudents(listOf(student))
        db.recordAttendance(student, session, 2000L)
        
        db.loadSessionsForCourse(Course(id = courseId, name = "C1"))
        assertEquals(1, db.getCourseCache().allAttendance.size)
        
        db.deleteAttendancesBySessionId(session.id)
        
        assertTrue(db.getCourseCache().allAttendance.isEmpty())
    }

    @Test
    fun updateCourseWorks() = runBlocking {
        val courseId = db.insertCourse(Course(name = "Old Name"))
        val updatedCourse = Course(id = courseId, name = "New Name")
        db.updateCourse(updatedCourse)
        
        val courses = db.getAllCourses()
        assertEquals("New Name", courses[0].name)
    }

    @Test
    fun updateCourseUpdatesCacheAware() = runBlocking {
        val courseId = db.insertCourse(Course(name = "Original"))
        db.loadSessionsForCourse(Course(id = courseId, name = "Original"))
        
        // This will hit the branch where courseId matches
        db.updateCourse(Course(id = courseId, name = "Updated"))
        
        val courses = db.getAllCourses()
        assertEquals("Updated", courses[0].name)
    }

    @Test
    fun preloadStudentsDirectly() = runBlocking {
        val students = listOf(Student("e1", "N1"), Student("e2", "N2"))
        db.insertStudents(students)
        
        db.preloadStudents()
        
        assertEquals(2, db.getCourseCache().allStudents.size)
    }

    @Test
    fun testOperationsWithoutCache() = runBlocking {
        db.setUseCourseCache(false)
        
        val courseId = db.insertCourse(Course(name = "NoCache"))
        db.updateCourse(Course(id = courseId, name = "UpdatedName"))
        
        db.insertSession(courseId, "S1", 1000L)
        db.insertSessions(listOf(Session(courseId = courseId, name = "S2", date = 2000L)))
        
        val session = db.getSessionsByCourse(courseId)[0]
        db.updateSessionLock(session.id, true)
        db.updateSession(session.copy(name = "Updated"))
        
        val student = Student("e@test.com", "N", null)
        db.insertStudents(listOf(student))
        db.bindTagToStudent("TAG", "e@test.com")
        db.clearTagFromOthers("TAG")
        db.clearAndBind("TAG2", "e@test.com")
        
        db.recordAttendance(student, session, 3000L)
        db.preloadStudents()
        db.getAttendanceRecordsForSession(session.id)
        db.getStudentsForCourse(courseId)
        db.getAllStudents()
        db.getUnboundStudents()
        db.getStudentByRfid("TAG2")
        db.getAllAttendanceForCourse(courseId)
        db.loadSessionsForCourse(Course(id = courseId, name = "UpdatedName"))
        db.deleteAttendancesBySessionId(session.id)
        db.deleteSession(session)
        db.deleteSessionsByCourseId(courseId)
        db.deleteCourse(Course(id = courseId, name = "UpdatedName"))
        
        // Cache should be empty since it was disabled
        assertTrue(db.getCourseCache().allSessions.isEmpty())
        assertTrue(db.getCourseCache().allStudents.isEmpty())
    }
}
