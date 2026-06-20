package com.example.presensor.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.presensor.CourseUtilities
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Attendance
import com.example.presensor.data.entities.AttendanceRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

    fun getCourseCache(): CourseCache {
        return courseCache
    }

    // ==========================================
    // COURSE ACTIONS
    // ==========================================

    suspend fun insertCourse(course: Course): Long = withContext(Dispatchers.IO) {
        dao().insertCourse(course)
    }

    suspend fun updateCourse(course: Course) = withContext(Dispatchers.IO) {
        dao().updateCourse(course)
    }

    suspend fun deleteCourse(course: Course) = withContext(Dispatchers.IO) {
        dao().deleteCourse(course)
        // Only invalidate cache if caching is enabled
        if (useCourseCache && courseCache.courseId == course.id) {
            courseCache.clear()
        }
    }

    suspend fun getAllCourses(): List<Course> = withContext(Dispatchers.IO) {
        dao().getAllCourses()
    }

    // ==========================================
    // SESSION ACTIONS
    // ==========================================

    suspend fun insertSession(courseId: Long, sessionName: String, date: Long) =
        withContext(Dispatchers.IO) {
            val session = Session(courseId = courseId, name = sessionName, date = date)
            val newSessionId = dao().insertSession(session)

            if (useCourseCache && courseCache.courseId == courseId) {
                courseCache.sessionIds += newSessionId
                courseCache.allSessions += session.copy(id = newSessionId)
            }
        }

    suspend fun insertSessions(sessions: List<Session>) = withContext(Dispatchers.IO) {
        val newIds = dao().insertSessions(sessions)
        sessions.zip(newIds).forEach { (session, id) ->
            if (useCourseCache && courseCache.courseId == session.courseId) {
                courseCache.sessionIds += id
                courseCache.allSessions += session.copy(id = id)
            }
        }
    }

    suspend fun deleteSession(session: Session) = withContext(Dispatchers.IO) {
        dao().deleteAttendancesBySessionId(session.id)
        dao().deleteSession(session)
        if (useCourseCache && courseCache.courseId == session.courseId) {
            courseCache.deleteSession(session)
        }
    }

    suspend fun deleteSessionsByCourseId(courseId: Long) = withContext(Dispatchers.IO) {
        dao().deleteSessionsByCourseId(courseId)
        if (useCourseCache && courseCache.courseId == courseId) {
            courseCache.allSessions = emptyList()
            courseCache.sessionIds = emptySet()
            courseCache.allAttendance = emptyList()
            courseCache.activeStudents = emptyList()
            courseCache.activeStudentEmails = emptySet()
        }
    }

    suspend fun updateSessionLock(sessionId: Long, locked: Boolean) = withContext(Dispatchers.IO) {
        dao().updateSessionLock(sessionId, locked)
        if (useCourseCache) {
            courseCache.updateSessionLock(sessionId, locked)
        }
    }

    suspend fun updateSession(session: Session) = withContext(Dispatchers.IO) {
        dao().updateSession(session)
        if (useCourseCache) {
            courseCache.updateSession(session)
        }
    }

    suspend fun getSessionsByCourse(courseId: Long): List<Session> = withContext(Dispatchers.IO) {
        if (useCourseCache && courseCache.courseId != null && courseId == courseCache.courseId) {
            courseCache.allSessions
        } else {
            dao().getSessionsByCourse(courseId)
        }
    }

    suspend fun loadSessionsForCourse(course: Course) = withContext(Dispatchers.IO) {
        if (useCourseCache && course.id == courseCache.courseId) {
            return@withContext
        }

        val sessionsDeferred = async { dao().getSessionsByCourse(course.id) }
        val attendanceDeferred = async { dao().getAllAttendanceForCourse(course.id) }
        val activeStudentsDeferred = async { dao().getAllStudents() }

        val sessions = sessionsDeferred.await().sortedByDescending { it.date }
        val allAttendance = attendanceDeferred.await()
        val attendeeEmails = allAttendance.map { it.studentEmail }.toSet()
        val activeStudents = activeStudentsDeferred.await().filter { it.email in attendeeEmails }

        courseCache.computeFromMinimalData(course.id, sessions, allAttendance, activeStudents)
    }

    // ==========================================
    // STUDENT ACTIONS
    // ==========================================

    suspend fun preloadStudents() = withContext(Dispatchers.IO) {
        if (useCourseCache) {
            courseCache.allStudents = dao().getAllStudents()
        }
    }

    suspend fun insertStudents(students: List<Student>) = withContext(Dispatchers.IO) {
        val knownEmails = if (useCourseCache) courseCache.allStudents.map { it.email }.toSet() else emptySet()
        dao().insertStudents(students)

        if (useCourseCache) {
            courseCache.allStudents += students.filter { it.email !in knownEmails }
        }
    }

    suspend fun getAllStudents(): List<Student> = withContext(Dispatchers.IO) {
        if(useCourseCache) {
            courseCache.allStudents
        }
        dao().getAllStudents()
    }

    suspend fun getUnboundStudents(): List<Student> = withContext(Dispatchers.IO) {
        if(useCourseCache) {
            courseCache.allStudents.filter { it.rfid == null }
        }
        dao().getUnboundStudents()
    }

    suspend fun getStudentByRfid(rfid: String): Student? = withContext(Dispatchers.IO) {
        if(useCourseCache) {
            val tempStudents = courseCache.allStudents.filter { it.rfid == rfid }
            if(tempStudents.isNotEmpty()) {
                tempStudents[0]
            } else {
                null
            }
        }
        dao().getStudentByRfid(rfid)
    }

    // ==========================================
    // RFID TAG BINDING MANAGEMENT
    // ==========================================

    suspend fun clearTagFromOthers(rfid: String) = withContext(Dispatchers.IO) {
        dao().clearTagFromOthers(rfid)

        if (useCourseCache) {
            courseCache.allStudents.find { it.rfid == rfid }?.rfid = null
            courseCache.activeStudents.find { it.rfid == rfid }?.rfid = null
        }
    }

    suspend fun bindTagToStudent(rfid: String?, email: String) = withContext(Dispatchers.IO) {
        dao().bindTagToStudent(rfid, email)

        if (useCourseCache) {
            courseCache.allStudents.find { it.email == email }?.rfid = rfid
            courseCache.activeStudents.find { it.email == email }?.rfid = rfid
        }
    }

    suspend fun clearAndBind(rfid: String, email: String) = withContext(Dispatchers.IO) {
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

    suspend fun recordAttendance(student: Student, session: Session, timestamp: Long) =
        withContext(Dispatchers.IO) {
            dao().recordAttendance(
                Attendance(
                    rfid = student.rfid,
                    sessionId = session.id,
                    studentEmail = student.email,
                    timestamp = timestamp
                )
            )
            if (useCourseCache) {
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



    suspend fun getAttendanceRecordsForSession(sid: Long): List<AttendanceRecord> =
        withContext(Dispatchers.IO) {
            if(useCourseCache) {
                courseCache.allAttendance.filter {it.sessionId == sid}
            }
            dao().getAttendanceRecordsForSession(sid)
        }

    suspend fun getAllAttendanceForCourse(courseId: Long): List<AttendanceRecord> =
        withContext(Dispatchers.IO) {
            if(useCourseCache && courseCache.courseId == courseId) {
                courseCache.allAttendance
            }
            dao().getAllAttendanceForCourse(courseId)
        }

    suspend fun deleteAttendancesBySessionId(sessionId: Long) = withContext(Dispatchers.IO) {
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
                    sb.append(
                        "${it.id},\"${
                            it.name.replace(
                                "\"",
                                "\"\""
                            )
                        }\",${it.year},${it.semester}\n"
                    )
                }
                sb.append("\n")

                // Append Sessions
                sb.append("=== SESSIONS ===\n")
                sb.append("Session ID,Course ID,Session Name,Timestamp/Date\n")
                allSessions.forEach {
                    sb.append(
                        "${it.id},${it.courseId},\"${
                            it.name.replace(
                                "\"",
                                "\"\""
                            )
                        }\",${it.date}\n"
                    )
                }
                sb.append("\n")

                // Append Students
                sb.append("=== STUDENTS ===\n")
                sb.append("Email,Name,RFID Tag\n")
                allStudents.forEach {
                    sb.append(
                        "\"${it.email}\",\"${
                            it.name.replace(
                                "\"",
                                "\"\""
                            )
                        }\",${it.rfid ?: ""}\n"
                    )
                }
                sb.append("\n")

                // Append Attendance Records
                sb.append("=== ATTENDANCE RECORDS ===\n")
                sb.append("Timestamp,Student Email,Student Name,RFID Tag,Session ID,Session Name\n")
                allAttendance.forEach {
                    sb.append(
                        "${it.timestamp},\"${it.studentEmail}\",\"${
                            it.studentName.replace(
                                "\"",
                                "\"\""
                            )
                        }\",${it.studentRfid ?: ""},${it.sessionId},\"${
                            it.sessionName.replace(
                                "\"",
                                "\"\""
                            )
                        }\"\n"
                    )
                }

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

                // Helper helper parsing routine handling simple split rules for escaped quotes
                fun parseCsvLine(line: String): List<String> {
                    val tokens = mutableListOf<String>()
                    var sb = StringBuilder()
                    var inQuotes = false
                    var i = 0
                    while (i < line.length) {
                        val c = line[i]
                        if (c == '"') {
                            if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                                sb.append('"') // Handle escaped quote ""
                                i++
                            } else {
                                inQuotes = !inQuotes
                            }
                        } else if (c == ',' && !inQuotes) {
                            tokens.add(sb.toString())
                            sb = StringBuilder()
                        } else {
                            sb.append(c)
                        }
                        i++
                    }
                    tokens.add(sb.toString())
                    return tokens
                }

                reader.useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty()) return@forEach

                        // Identify structural tables transitions markers
                        if (line.startsWith("===")) {
                            currentSection = line
                            return@forEach
                        }

                        // Skip individual table headers rows
                        if (line.startsWith("Course ID,") || line.startsWith("Session ID,") ||
                            line.startsWith("Email,") || line.startsWith("Timestamp,")
                        ) {
                            return@forEach
                        }

                        val tokens = parseCsvLine(line)
                        try {
                            when (currentSection) {
                                "=== COURSES ===" -> {
                                    if (tokens.size >= 4) {
                                        coursesToInsert.add(
                                            Course(
                                                id = tokens[0].toLong(),
                                                name = tokens[1],
                                                year = tokens[2].toInt(),
                                                semester = tokens[3].toInt()
                                            )
                                        )
                                    }
                                }

                                "=== SESSIONS ===" -> {
                                    if (tokens.size >= 4) {
                                        sessionsToInsert.add(
                                            Session(
                                                id = tokens[0].toLong(),
                                                courseId = tokens[1].toLong(),
                                                name = tokens[2],
                                                date = tokens[3].toLong()
                                            )
                                        )
                                    }
                                }

                                "=== STUDENTS ===" -> {
                                    if (tokens.size >= 2) {
                                        studentsToInsert.add(
                                            Student(
                                            email = tokens[0],
                                            name = tokens[1],
                                            rfid = tokens.getOrNull(2)?.takeIf { it.isNotBlank() }
                                        ))
                                    }
                                }

                                "=== ATTENDANCE RECORDS ===" -> {
                                    if (tokens.size >= 5) {
                                        attendanceToInsert.add(
                                            Attendance(
                                                timestamp = tokens[0].toLong(),
                                                studentEmail = tokens[1],
                                                rfid = tokens.getOrNull(3)
                                                    ?.takeIf { it.isNotBlank() },
                                                sessionId = tokens[4].toLong()
                                            )
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AppDatabase", "Error parsing CSV backup entry line: $line", e)
                        }
                    }
                }

                // Database transaction insertion block executed entirely on background thread
                try {
                    withTransaction {
                        // Bulk insert structures back into the underlying tables
                        coursesToInsert.forEach {
                            Log.d("importFullDatabase", "inserting course: $it")
                            insertCourse(it) }
                        insertSessions(sessionsToInsert)
                        insertStudents(studentsToInsert)
                        attendanceToInsert.forEach { attendance ->
                            val insertingStudent =
                                studentsToInsert.filter { it.email == attendance.studentEmail }[0]
                            val insertingSession =
                                sessionsToInsert.filter { it.id == attendance.sessionId }[0]
                            recordAttendance(
                                insertingStudent.copy(rfid = attendance.rfid),
                                insertingSession,
                                attendance.timestamp
                            )
                        }
                    }
                } catch (dbException: Exception) {
                    Log.e("AppDatabase", "Transaction failed during CSV insertion", dbException)
                    return@withContext false
                }


                // Wipe local in-memory cache models clean to force immediate reload
                courseCache.clear()
                preloadStudents()
                true
            } catch (e: Exception) {
                Log.e("AppDatabase", "Database restoration parse execution crash", e)
                false
            }
        }

    // ==========================================
    // LIFECYCLE COMPANION INSTANCE
    // ==========================================

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