package com.example.presensor.data

import androidx.room.*
import com.example.presensor.data.entities.Attendance
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student

@Dao
interface PresensorDao {
    @Insert
    suspend fun insertCourse(c: Course): Long

    @Update
    suspend fun updateCourse(course: Course)

    @Delete
    suspend fun deleteCourse(course: Course)

    @Query("SELECT * FROM Course")
    suspend fun getAllCourses(): List<Course>

    @Insert
    suspend fun insertSession(s: Session): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessions(sessions: List<Session>): List<Long>

    @Delete
    suspend fun deleteSession(session: Session)

    @Query("DELETE FROM Session WHERE courseId = :courseId")
    suspend fun deleteSessionsByCourseId(courseId: Long)

    @Query("UPDATE Session SET isLocked = :locked WHERE id = :sid")
    suspend fun updateSessionLock(sid: Long, locked: Boolean)

    @Update
    suspend fun updateSession(session: Session)

    @Query("SELECT * FROM Session WHERE courseId = :courseId")
    suspend fun getSessionsByCourse(courseId: Long): List<Session>

    @Query("SELECT * FROM Session WHERE id = :id")
    suspend fun getSessionById(id: Long): Session?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStudents(students: List<Student>)

    @Query("SELECT * FROM Student WHERE rfid IS NULL")
    suspend fun getUnboundStudents(): List<Student>

    @Query("UPDATE Student SET rfid = NULL WHERE rfid = :rfid")
    suspend fun clearTagFromOthers(rfid: String)

    @Query("UPDATE Student SET rfid = :rfid WHERE email = :email")
    suspend fun bindTagToStudent(rfid: String?, email: String)

    @Transaction
    suspend fun clearAndBind(rfid: String, email: String) {
        clearTagFromOthers(rfid)
        bindTagToStudent(rfid, email)
    }

    @Query("SELECT * FROM Student WHERE rfid = :rfid")
    suspend fun getStudentByRfid(rfid: String): Student?

    @Insert
    suspend fun recordAttendance(a: Attendance)

    @Query(
        """
    SELECT Attendance.timestamp AS timestamp, Student.email AS studentEmail,
            Student.rfid AS studentRfid, Student.name AS studentName, Session.name AS sessionName,
            Session.id AS sessionId
    FROM Attendance 
    INNER JOIN Student ON Attendance.studentEmail = Student.email 
    INNER JOIN Session ON Attendance.sessionId = Session.id
    WHERE Attendance.sessionId = :sid 
    ORDER BY timestamp DESC
"""
    )
    suspend fun getAttendanceRecordsForSession(sid: Long): List<AttendanceRecord>

    @Query(
        """
    SELECT Attendance.timestamp AS timestamp, Student.name AS studentName, 
           Student.rfid AS studentRfid, Student.email AS studentEmail, 
           Session.name AS sessionName, Session.id AS sessionId
    FROM Attendance
    INNER JOIN Student ON Attendance.studentEmail = Student.email
    INNER JOIN Session ON Attendance.sessionId = Session.id
    WHERE Session.courseId = :courseId
"""
    )
    suspend fun getAllAttendanceForCourse(courseId: Long): List<AttendanceRecord>

    @Query("DELETE FROM Attendance WHERE sessionId = :sessionId")
    suspend fun deleteAttendancesBySessionId(sessionId: Long)

    @Query("SELECT * FROM Student")
    suspend fun getAllStudents(): List<Student>

    @Query("SELECT EXISTS(SELECT 1 FROM Attendance WHERE studentEmail = :email AND sessionId = :s)")
    suspend fun isPresent(email: String, s: Long): Boolean
}