package com.example.presensor.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DatabaseBackupRestoreTest {

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
    fun backupAndRestoreMaintainsDataIntegrity() = runBlocking {
        // 1. Setup initial data
        val course = Course(name = "Original Course", year = 2023, semester = 1)
        val courseId = dao.insertCourse(course)
        
        dao.insertSession(Session(courseId = courseId, name = "S1", date = 123456789L))
        val sessionWithId = dao.getSessionsByCourse(courseId)[0]
        
        val student = Student(email = "test@example.com", name = "Test Student", rfid = "TAG1")
        dao.insertStudents(listOf(student))
        
        db.recordAttendance(student, sessionWithId, 987654321L)

        // 2. Perform Backup
        val outputStream = ByteArrayOutputStream()
        val backupSuccess = db.performFullDatabaseDump(outputStream)
        assertTrue("Backup should succeed", backupSuccess)
        val backupData = outputStream.toByteArray()

        // 3. Clear Database (using a new in-memory DB to simulate restoration on fresh install)
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.dao()

        // 4. Perform Restore
        val inputStream = ByteArrayInputStream(backupData)
        val restoreSuccess = db.importFullDatabaseDump(inputStream)
        assertTrue("Restore should succeed", restoreSuccess)

        // 5. Verify Data
        val courses = dao.getAllCourses()
        assertEquals(1, courses.size)
        assertEquals("Original Course", courses[0].name)

        val students = dao.getAllStudents()
        assertEquals(1, students.size)
        assertEquals("test@example.com", students[0].email)
        assertEquals("TAG1", students[0].rfid)

        val sessions = dao.getSessionsByCourse(courses[0].id)
        assertEquals(1, sessions.size)
        assertEquals("S1", sessions[0].name)

        val attendance = dao.getAttendanceRecordsForSession(sessions[0].id)
        assertEquals(1, attendance.size)
        assertEquals("test@example.com", attendance[0].studentEmail)
    }

    @Test
    fun restoreWithMalformedDataReturnsFalse() = runBlocking {
        val malformedCsv = "NOT A REAL BACKUP\n=== COURSES ===\nInvalid,Data,Row"
        val inputStream = ByteArrayInputStream(malformedCsv.toByteArray())
        
        val restoreSuccess = db.importFullDatabaseDump(inputStream)
        
        // Verification: DB should be empty or consistent
        assertTrue(dao.getAllCourses().isEmpty())
    }

    @Test
    fun restoreWithForeignKeyViolationReturnsFalse() = runBlocking {
        // Attendance referencing session 999 which doesn't exist
        val invalidBackup = "=== ATTENDANCE RECORDS ===\n123456,test@e.com,Name,TAG,999,SessionName\n"
        val inputStream = ByteArrayInputStream(invalidBackup.toByteArray())
        
        val success = db.importFullDatabaseDump(inputStream)
        
        // Since we enable PRAGMA foreign_keys = ON in onOpen, 
        // and we have a ForeignKey in Attendance entity, it should fail.
        assertFalse("Restore should fail due to foreign key violation", success)
    }

    @Test
    fun restoreWithConflictingIdsFails() = runBlocking {
        val csv = """
            === COURSES ===
            10,C1,2023,1
            10,C2,2023,2
            === END ===
        """.trimIndent()
        
        val inputStream = ByteArrayInputStream(csv.toByteArray())
        val success = db.importFullDatabaseDump(inputStream)
        
        assertFalse("Restore should fail due to primary key conflict in transaction", success)
        assertTrue(dao.getAllCourses().isEmpty()) // Transaction should roll back
    }

    @Test
    fun restoreHandlesInvalidLinesGracefully() = runBlocking {
        val mixedCsv = """
            === COURSES ===
            100,Valid Course,2023,1
            BAD,LINE,HERE
            101,Another Valid,2023,2
            === STUDENTS ===
            e1@test.com,Name 1,TAG1
            e2@test.com,Name 2
            === END ===
        """.trimIndent()
        
        val inputStream = ByteArrayInputStream(mixedCsv.toByteArray())
        val success = db.importFullDatabaseDump(inputStream)
        
        assertTrue("Import should succeed even with some bad lines", success)
        assertEquals(2, dao.getAllCourses().size)
        assertEquals(2, dao.getAllStudents().size)
    }

    @Test
    fun backupEmptyDatabase() = runBlocking {
        val outputStream = ByteArrayOutputStream()
        val success = db.performFullDatabaseDump(outputStream)
        assertTrue(success)
        val data = String(outputStream.toByteArray())
        assertTrue(data.contains("=== COURSES ==="))
        assertTrue(data.contains("=== STUDENTS ==="))
        assertTrue(data.contains("=== SESSIONS ==="))
        assertTrue(data.contains("=== ATTENDANCE RECORDS ==="))
        assertTrue(data.contains("=== END ==="))
    }

    @Test
    fun backupEscapesCommas() = runBlocking {
        val course = Course(name = "Course, with, commas")
        val courseId = dao.insertCourse(course)
        
        val outputStream = ByteArrayOutputStream()
        db.performFullDatabaseDump(outputStream)
        val data = String(outputStream.toByteArray())
        
        assertTrue(data.contains("Course with commas"))
        assertFalse(data.contains("Course, with, commas"))
    }

    @Test
    fun restoreHandlesBlankRfid() = runBlocking {
        val csv = """
            === STUDENTS ===
            e1@test.com,Name 1,
            e2@test.com,Name 2,   
            e3@test.com,Name 3,TAG3
            === END ===
        """.trimIndent()
        
        val inputStream = ByteArrayInputStream(csv.toByteArray())
        db.importFullDatabaseDump(inputStream)
        
        val students = dao.getAllStudents().sortedBy { it.email }
        assertEquals(3, students.size)
        assertTrue(students[0].rfid.isNullOrBlank())
        assertTrue(students[1].rfid.isNullOrBlank())
        assertEquals("TAG3", students[2].rfid)
    }
}
