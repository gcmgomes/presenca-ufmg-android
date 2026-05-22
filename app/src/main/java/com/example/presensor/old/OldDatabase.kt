package com.example.presensor.old

import androidx.room.*
import java.util.*

// --- ENTITIES ---

@Entity(primaryKeys = ["email"]) // Email is a better primary key if RFID is initially missing
data class Student(
    val email: String,
    val name: String,
    val rfid: String? = null // Nullable until bound
)
@Entity(tableName = "Course")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val year: Int = Calendar.getInstance().get(Calendar.YEAR),
    val semester: Int = if (Calendar.getInstance().get(Calendar.MONTH) < 6) 1 else 2
)
@Entity(
    indices = [Index(value = ["courseId"])]
) data class Session(@PrimaryKey(autoGenerate = true) val id: Long = 0, val courseId: Long, val name: String, val date: Long, val isLocked: Boolean = false)
@Entity(
    indices = [Index(value = ["sessionId"]), Index(value = ["rfid"])]
) data class Attendance(@PrimaryKey(autoGenerate = true) val id: Long = 0, val rfid: String, val studentEmail: String, val sessionId: Long, val timestamp: Long)
data class AttendanceRecord(
    val timestamp: Long,
    val studentName: String,
    val studentRfid: String?,
    val studentEmail: String,
    val sessionName: String,
    val sessionId: Long
)

@Dao interface PresensorDao {
    @Insert suspend fun insertCourse(c: Course): Long

    @Delete
    suspend fun deleteCourse(course: Course)

    @Query("SELECT * FROM Course") suspend fun getAllCourses(): List<Course>
    @Insert suspend fun insertSession(s: Session): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessions(sessions: List<Session>)

    @Delete
    suspend fun deleteSession(session: Session)
    @Query("DELETE FROM Session WHERE courseId = :courseId")
    suspend fun deleteSessionsByCourseId(courseId: Long)

    @Query("UPDATE Session SET isLocked = :locked WHERE id = :sid")
    suspend fun updateSessionLock(sid: Long, locked: Boolean)
    @Query("SELECT * FROM Session WHERE courseId = :courseId")
    suspend fun getSessionsByCourse(courseId: Long): List<Session>
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

    @Insert suspend fun recordAttendance(a: Attendance)
    @Query("""
    SELECT Attendance.timestamp AS timestamp, Student.email AS studentEmail,
            Student.rfid AS studentRfid, Student.name AS studentName, Session.name AS sessionName,
            Session.id AS sessionId
    FROM Attendance 
    INNER JOIN Student ON Attendance.studentEmail = Student.email 
    INNER JOIN Session ON Attendance.sessionId = Session.id
    WHERE Attendance.sessionId = :sid 
    ORDER BY timestamp DESC
""")
    suspend fun getAttendanceRecordsForSession(sid: Long): List<AttendanceRecord>
    @Query("""
    SELECT Attendance.timestamp AS timestamp, Student.name AS studentName, 
           Student.rfid AS studentRfid, Student.email AS studentEmail, 
           Session.name AS sessionName, Session.id AS sessionId
    FROM Attendance
    INNER JOIN Student ON Attendance.studentEmail = Student.email
    INNER JOIN Session ON Attendance.sessionId = Session.id
    WHERE Session.courseId = :courseId
""")
    suspend fun getAllAttendanceForCourse(courseId: Long): List<AttendanceRecord>

    @Query("DELETE FROM Attendance WHERE sessionId = :sessionId")
    suspend fun deleteAttendancesBySessionId(sessionId: Long)
    @Query("SELECT * FROM Student") suspend fun getAllStudents(): List<Student>
    @Query("SELECT EXISTS(SELECT 1 FROM Attendance WHERE rfid = :r AND sessionId = :s)") suspend fun isPresent(r: String, s: Long): Boolean
}

@Database(entities = [Student::class, Course::class, Session::class, Attendance::class], version = 1)
abstract class OldDatabase : RoomDatabase() { abstract fun dao(): PresensorDao }