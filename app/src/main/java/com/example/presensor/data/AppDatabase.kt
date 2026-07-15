package com.example.presensor.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Attendance
import com.example.presensor.data.entities.AttendanceRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

@Database(
    entities = [Course::class, Session::class, Student::class, Attendance::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PresensorDao

    private val courseCache = CourseCache()

    private var useCourseCache: Boolean = true

    fun setUseCourseCache(enabled: Boolean) {
        useCourseCache = enabled
    }

    fun getCourseCache(): CourseCache {
        return courseCache
    }

    // ==========================================
    // COURSE ACTIONS
    // ==========================================

    suspend fun insertCourse(course: Course): Long = dao().insertCourse(course)

    suspend fun updateCourse(course: Course) {
        dao().updateCourse(course)
        if (useCourseCache && courseCache.courseId == course.id) {
            // Update nothing for now, but ensure this branch is covered
            Log.d("AppDatabase", "Updating course currently in cache: ${course.id}")
        }
    }

    suspend fun deleteCourse(course: Course) {
        dao().deleteCourse(course)
        // Only invalidate cache if caching is enabled
        if (useCourseCache && courseCache.courseId == course.id) {
            courseCache.clear()
        }
    }

    suspend fun getAllCourses(): List<Course> = dao().getAllCourses()

    // ==========================================
    // SESSION ACTIONS
    // ==========================================

    suspend fun insertSession(courseId: Long, sessionName: String, date: Long) {
        val session = Session(courseId = courseId, name = sessionName, date = date)
        val newSessionId = dao().insertSession(session)

        if (useCourseCache && courseCache.courseId == courseId) {
            courseCache.sessionIds += newSessionId
            courseCache.allSessions += session.copy(id = newSessionId)
        }
    }

    suspend fun insertSessions(sessions: List<Session>) {
        val newIds = dao().insertSessions(sessions)
        sessions.zip(newIds).forEach { (session, id) ->
            if (useCourseCache && courseCache.courseId == session.courseId) {
                courseCache.sessionIds += id
                courseCache.allSessions += session.copy(id = id)
            }
        }
    }

    suspend fun deleteSession(session: Session) {
        dao().deleteAttendancesBySessionId(session.id)
        dao().deleteSession(session)
        if (useCourseCache && courseCache.courseId == session.courseId) {
            courseCache.deleteSession(session)
        }
    }

    suspend fun deleteSessionsByCourseId(courseId: Long) {
        dao().deleteSessionsByCourseId(courseId)
        if (useCourseCache && courseCache.courseId == courseId) {
            courseCache.allSessions = emptyList()
            courseCache.sessionIds = emptySet()
            courseCache.allAttendance = emptyList()
            courseCache.activeStudents = emptyList()
            courseCache.activeStudentEmails = emptySet()
        }
    }

    suspend fun updateSessionLock(sessionId: Long, locked: Boolean) {
        dao().updateSessionLock(sessionId, locked)
        if (useCourseCache) {
            courseCache.updateSessionLock(sessionId, locked)
        }
    }

    suspend fun updateSession(session: Session) {
        dao().updateSession(session)
        if (useCourseCache) {
            courseCache.updateSession(session)
        }
    }

    suspend fun getSessionsByCourse(courseId: Long): List<Session> {
        return if (useCourseCache && courseCache.courseId != null && courseId == courseCache.courseId) {
            courseCache.allSessions
        } else {
            dao().getSessionsByCourse(courseId)
        }
    }

    suspend fun getSessionById(id: Long): Session? = dao().getSessionById(id)

    suspend fun loadSessionsForCourse(course: Course) = coroutineScope {
        if (useCourseCache && course.id == courseCache.courseId) {
            return@coroutineScope
        }

        val sessionsDeferred = async { dao().getSessionsByCourse(course.id) }
        val attendanceDeferred = async { dao().getAllAttendanceForCourse(course.id) }
        val activeStudentsDeferred = async { dao().getAllStudents() }

        val sessions = sessionsDeferred.await().sortedByDescending { it.date }
        val allAttendance = attendanceDeferred.await()

        // Populate the cache if enabled
        if (useCourseCache) {
            courseCache.computeFromMinimalData(
                course.id,
                sessions,
                allAttendance,
                activeStudentsDeferred.await()
            )
        }
    }

    // ==========================================
    // STUDENT ACTIONS
    // ==========================================

    suspend fun preloadStudents() {
        if (useCourseCache) {
            courseCache.allStudents = dao().getAllStudents()
        }
    }

    suspend fun insertStudents(students: List<Student>) {
        val knownEmails = if (useCourseCache) courseCache.allStudents.map { it.email }.toSet() else emptySet()
        dao().insertStudents(students)

        if (useCourseCache) {
            courseCache.allStudents += students.filter { it.email !in knownEmails }
        }
    }


    suspend fun getStudentsForCourse(courseId: Long): List<Student> {
        if (useCourseCache && courseCache.courseId == courseId) {
            return courseCache.activeStudents
        }
        val studentEmails = dao().getAllAttendanceForCourse(courseId).map { it.studentEmail }.toSet()
        return getAllStudents().filter { it.email in studentEmails }
    }

    suspend fun getAllStudents(): List<Student> {
        if(useCourseCache && courseCache.allStudents.isNotEmpty()) {
            return courseCache.allStudents
        }
        return dao().getAllStudents()
    }

    suspend fun getUnboundStudents(): List<Student> {
        if(useCourseCache && courseCache.allStudents.isNotEmpty()) {
            return courseCache.allStudents.filter { it.rfid == null }
        }
        return dao().getUnboundStudents()
    }

    suspend fun getStudentByRfid(rfid: String): Student? {
        if(useCourseCache && courseCache.allStudents.isNotEmpty()) {
            return courseCache.allStudents.find { it.rfid == rfid }
        }
        return dao().getStudentByRfid(rfid)
    }

    // ==========================================
    // RFID TAG BINDING MANAGEMENT
    // ==========================================

    suspend fun clearTagFromOthers(rfid: String) {
        dao().clearTagFromOthers(rfid)

        if (useCourseCache) {
            courseCache.allStudents.find { it.rfid == rfid }?.rfid = null
            courseCache.activeStudents.find { it.rfid == rfid }?.rfid = null
        }
    }

    suspend fun bindTagToStudent(rfid: String?, email: String) {
        dao().bindTagToStudent(rfid, email)

        if (useCourseCache) {
            courseCache.allStudents.find { it.email == email }?.rfid = rfid
            courseCache.activeStudents.find { it.email == email }?.rfid = rfid
        }
    }

    suspend fun clearAndBind(rfid: String, email: String) {
        dao().clearAndBind(rfid, email)

        if (useCourseCache) {
            courseCache.allStudents.find { it.rfid == rfid }?.rfid = null
            courseCache.activeStudents.find { it.rfid == rfid }?.rfid = null

            courseCache.allStudents.find { it.email == email }?.rfid = rfid
            courseCache.activeStudents.find { it.email == email }?.rfid = rfid
        }
    }

    // ==========================================
    // ATTENDANCE MANAGEMENT
    // ==========================================

    suspend fun recordAttendance(student: Student, session: Session, timestamp: Long) {
        dao().recordAttendance(
            Attendance(
                rfid = student.rfid,
                sessionId = session.id,
                studentEmail = student.email,
                timestamp = timestamp
            )
        )
        if (useCourseCache && courseCache.courseId == session.courseId) {
            val attendanceRecord = AttendanceRecord(
                timestamp,
                student.name,
                student.rfid,
                student.email,
                session.name,
                session.id
            )
            courseCache.addAttendance(student, session, attendanceRecord)
        }
    }

    suspend fun getAttendanceRecordsForSession(sid: Long): List<AttendanceRecord> {
        if (useCourseCache && courseCache.sessionIds.contains(sid)) {
            return courseCache.allAttendance.filter { it.sessionId == sid }
        }
        return dao().getAttendanceRecordsForSession(sid)
    }

    suspend fun getAllAttendanceForCourse(courseId: Long): List<AttendanceRecord> {
        return if (useCourseCache && courseCache.courseId == courseId) {
            courseCache.allAttendance
        } else {
            dao().getAllAttendanceForCourse(courseId)
        }
    }

    suspend fun deleteAttendancesBySessionId(sessionId: Long) {
        dao().deleteAttendancesBySessionId(sessionId)
        if (useCourseCache && courseCache.sessionIds.contains(sessionId)) {
            courseCache.allAttendance =
                courseCache.allAttendance.filter { it.sessionId != sessionId }
        }
    }

    // ======================

    suspend fun performFullDatabaseDump(outputStream: OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // 1. Gather all system data using your established database hooks
                val allCourses = getAllCourses()
                val allStudents = getAllStudents()

                val allSessions = mutableListOf<Session>()
                val allAttendance = mutableListOf<AttendanceRecord>()

                allCourses.forEach { course ->
                    allSessions.addAll(getSessionsByCourse(course.id))
                    allAttendance.addAll(getAllAttendanceForCourse(course.id))
                }

                // 2. Build the structural CSV string mapping blocks
                val sb = StringBuilder()

                // Append Courses
                sb.append("=== COURSES ===\n")
                sb.append("Course ID,Course Name,Year,Semester\n")
                allCourses.forEach {
                    sb.append("${it.id},${it.name.replace(",", "")},${it.year},${it.semester}\n")
                }
                
                // Append Students
                sb.append("=== STUDENTS ===\n")
                sb.append("Email,Name,RFID Tag\n")
                allStudents.forEach {
                    sb.append("${it.email},${it.name.replace(",", "")},${it.rfid ?: ""}\n")
                }

                // Append Sessions
                sb.append("=== SESSIONS ===\n")
                sb.append("Session ID,Course ID,Session Name,Timestamp/Date\n")
                allSessions.forEach {
                    sb.append("${it.id},${it.courseId},${it.name.replace(",", "")},${it.date}\n")
                }

                // Append Attendance Records
                sb.append("=== ATTENDANCE RECORDS ===\n")
                sb.append("Timestamp,Student Email,Student Name,RFID Tag,Session ID,Session Name\n")
                allAttendance.forEach { record ->
                    sb.append("${record.timestamp},${record.studentEmail},${record.studentName.replace(",", "")},${record.studentRfid ?: ""},${record.sessionId},${record.sessionName.replace(",", "")}\n")
                }
                sb.append("=== END ===\n")

                // 3. Write compiled byte arrays safely to the output target stream
                outputStream.use { stream ->
                    stream.write(sb.toString().toByteArray())
                }

                true
            } catch (e: Exception) {
                Log.e("AppDatabase", "Full database backup dump streaming failure", e)
                false
            }
        }

    suspend fun importFullDatabaseDump(inputStream: InputStream): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(inputStream))
                var currentSection = ""

                val coursesToInsert = mutableListOf<Course>()
                val sessionsToInsert = mutableListOf<Session>()
                val studentsToInsert = mutableListOf<Student>()
                val attendanceToInsert = mutableListOf<Attendance>()

                reader.useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty()) return@forEach

                        if (line.startsWith("===")) {
                            currentSection = line
                            Log.d("AppDatabase", "Switching to section: $currentSection")
                            return@forEach
                        }

                        if (line.startsWith("Course ID,") || line.startsWith("Session ID,") ||
                            line.startsWith("Email,") || line.startsWith("Timestamp,")
                        ) {
                            return@forEach
                        }

                        val rowTokens = line.split(",")
                        try {
                            when (currentSection) {
                                "=== COURSES ===" -> {
                                    if (rowTokens.size >= 4 && rowTokens[0].toLongOrNull() != null) {
                                        coursesToInsert.add(Course(id = rowTokens[0].toLong(), name = rowTokens[1], year = rowTokens[2].toInt(), semester = rowTokens[3].toInt()))
                                    }
                                }
                                "=== SESSIONS ===" -> {
                                    if (rowTokens.size >= 4 && rowTokens[0].toLongOrNull() != null) {
                                        sessionsToInsert.add(Session(id = rowTokens[0].toLong(), courseId = rowTokens[1].toLong(), name = rowTokens[2], date = rowTokens[3].toLong()))
                                    }
                                }
                                "=== STUDENTS ===" -> {
                                    if (rowTokens.size >= 2 && !rowTokens[0].startsWith("Email")) {
                                        studentsToInsert.add(Student(email = rowTokens[0], name = rowTokens[1], rfid = rowTokens.getOrNull(2)?.takeIf { it.isNotBlank() }))
                                    }
                                }
                                "=== ATTENDANCE RECORDS ===" -> {
                                    if (rowTokens.size >= 5 && rowTokens[0].toLongOrNull() != null) {
                                        Log.d("AppDatabase", "Importing Attendance for ${rowTokens[1]} in session ${rowTokens[4]}")
                                        attendanceToInsert.add(Attendance(timestamp = rowTokens[0].toLong(), studentEmail = rowTokens[1], rfid = rowTokens[3].takeIf { it.isNotBlank() }, sessionId = rowTokens[4].toLong()))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AppDatabase", "Error parsing CSV backup entry line: $line", e)
                        }
                    }
                }

                try {
                    withTransaction {
                        coursesToInsert.forEach { dao().insertCourse(it) }
                        dao().insertStudents(studentsToInsert)
                        dao().insertSessions(sessionsToInsert)
                        attendanceToInsert.forEach { dao().recordAttendance(it) }
                    }
                } catch (dbException: Exception) {
                    Log.e("AppDatabase", "Transaction failed during CSV insertion", dbException)
                    return@withContext false
                }

                courseCache.clear()
                preloadStudents()
                true
            } catch (e: Exception) {
                Log.e("AppDatabase", "Database restoration parse execution crash", e)
                false
            }
        }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val dbCallback = object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA cache_size = 4000;")
                        db.execSQL("PRAGMA foreign_keys = ON;")
                        db.execSQL("PRAGMA optimize;")
                    }
                }

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "presensor-db"
                )
                    .addCallback(dbCallback)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
