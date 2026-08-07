package com.example.presensor.data

import com.example.presensor.controllers.BaseControllerTest
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class AppDatabaseTest : BaseControllerTest() {

    @Test
    fun testPerformFullDatabaseDump() = runTest {
        // 1. Populate the database
        val courseId = db.insertCourse(Course(name = "Android Development", year = 2024, semester = 1))
        val student = Student(email = "test@example.com", name = "John Doe", rfid = "RFID123")
        db.insertStudents(listOf(student))
        
        val sessionDate = System.currentTimeMillis()
        db.insertSession(courseId, "Lecture 1", sessionDate)
        val session = db.getSessionsByCourse(courseId).first()
        
        db.recordAttendance(student, session, sessionDate + 1000)

        // 2. Capture output
        val outputStream = ByteArrayOutputStream()
        val result = db.performFullDatabaseDump(outputStream)

        // 3. Verify
        assertTrue(result)
        val csvOutput = outputStream.toString()
        
        assertTrue(csvOutput.contains("=== COURSES ==="))
        assertTrue(csvOutput.contains("=== STUDENTS ==="))
        assertTrue(csvOutput.contains("=== SESSIONS ==="))
        assertTrue(csvOutput.contains("=== ATTENDANCE RECORDS ==="))
        assertTrue(csvOutput.contains("=== END ==="))
        
        assertTrue(csvOutput.contains("Android Development"))
        assertTrue(csvOutput.contains("John Doe"))
        assertTrue(csvOutput.contains("test@example.com"))
        assertTrue(csvOutput.contains("Lecture 1"))
    }

    @Test
    fun testImportFullDatabaseDump() = runTest {
        // 1. Prepare CSV
        val csv = """
            === COURSES ===
            Course ID,Course Name,Year,Semester,Start Time,End Time
            10,Imported Course,2025,2,480,1020
            === STUDENTS ===
            Email,Name,RFID Tag
            imported@test.com,Imported Student,RFID999
            === SESSIONS ===
            Session ID,Course ID,Session Name,Timestamp/Date,Start Time,End Time
            100,10,Imported Session,1722880000000,500,600
            === ATTENDANCE RECORDS ===
            Timestamp,Student Email,Student Name,RFID Tag,Session ID,Session Name
            1722880001000,imported@test.com,Imported Student,RFID999,100,Imported Session
            === END ===
        """.trimIndent()

        val inputStream = ByteArrayInputStream(csv.toByteArray())

        // 2. Import into empty database
        val result = db.importFullDatabaseDump(inputStream)

        // 3. Verify
        assertTrue(result)
        
        val courses = db.getAllCourses()
        assertEquals(1, courses.size)
        assertEquals("Imported Course", courses[0].name)
        assertEquals(10L, courses[0].id)

        val students = db.getAllStudents()
        assertEquals(1, students.size)
        assertEquals("imported@test.com", students[0].email)
        assertEquals("RFID999", students[0].rfid)

        val sessions = db.getSessionsByCourse(10L)
        assertEquals(1, sessions.size)
        assertEquals("Imported Session", sessions[0].name)
        assertEquals(100L, sessions[0].id)

        val attendance = db.getAttendanceRecordsForSession(100L)
        assertEquals(1, attendance.size)
        assertEquals("imported@test.com", attendance[0].studentEmail)
    }

    @Test
    fun testPerformFullDatabaseDumpFailure() = runTest {
        val failingOutputStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("Simulated write failure")
            }
            override fun write(b: ByteArray) {
                throw IOException("Simulated write failure")
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("Simulated write failure")
            }
        }

        val result = db.performFullDatabaseDump(failingOutputStream)
        assertFalse(result)
    }

    @Test
    fun testImportFullDatabaseDumpMalformedCsv() = runTest {
        // Malformed CSV: Invalid data types in some fields
        // The implementation skips lines that don't match the expected format.
        val malformedCsv = """
            === COURSES ===
            Course ID,Course Name,Year,Semester,Start Time,End Time
            not_a_long,Bad Course,not_an_int,1,480,1020
            === STUDENTS ===
            Email,Name,RFID Tag
            missing_name_and_rfid
            === SESSIONS ===
            Session ID,Course ID,Session Name,Timestamp/Date,Start Time,End Time
            200,999,No Course Exists,date_is_string,500,600
            === END ===
        """.trimIndent()

        val inputStream = ByteArrayInputStream(malformedCsv.toByteArray())

        val result = db.importFullDatabaseDump(inputStream)
        
        // It returns true because it gracefully skips malformed lines and the (empty) transaction succeeds.
        assertTrue(result)
        assertTrue(db.getAllCourses().isEmpty())
    }

    @Test
    fun testImportFullDatabaseDumpFailsOnDbConstraint() = runTest {
        // Prepare CSV with a session referencing a non-existent course.
        // This should trigger a Foreign Key violation during insertion if FKs are enabled.
        // Note: Room's in-memory database needs FKs enabled via callback, which BaseControllerTest doesn't do.
        // However, we can test a Primary Key conflict for Courses.
        
        db.insertCourse(Course(id = 1L, name = "Existing Course"))
        
        val csv = """
            === COURSES ===
            Course ID,Course Name,Year,Semester,Start Time,End Time
            1,Duplicate Course,2024,1,0,0
            === END ===
        """.trimIndent()

        val inputStream = ByteArrayInputStream(csv.toByteArray())
        val result = db.importFullDatabaseDump(inputStream)
        
        // This should fail because course ID 1 already exists and dao().insertCourse uses default ABORT strategy.
        assertFalse(result)
    }
    
    @Test
    fun testImportFullDatabaseDumpEmptySection() = runTest {
        val emptyCsv = """
            === COURSES ===
            Course ID,Course Name,Year,Semester,Start Time,End Time
            === STUDENTS ===
            Email,Name,RFID Tag
            === END ===
        """.trimIndent()
        
        val inputStream = ByteArrayInputStream(emptyCsv.toByteArray())
        val result = db.importFullDatabaseDump(inputStream)
        
        assertTrue(result)
        assertTrue(db.getAllCourses().isEmpty())
        assertTrue(db.getAllStudents().isEmpty())
    }
}
