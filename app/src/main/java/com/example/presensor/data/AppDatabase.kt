package com.example.presensor.data

import android.content.Context
import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.presensor.CourseUtilities
import com.example.presensor.R
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Attendance
import com.example.presensor.data.CourseCache
import com.example.presensor.data.entities.AttendanceRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.collections.plus

@Database(
    entities = [Course::class, Session::class, Student::class, Attendance::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PresensorDao

    private val courseCache = CourseCache()

    fun getCourseCache(): CourseCache {
        return courseCache
    }

    suspend fun insertCourse(course: Course) {
        dao().insertCourse(course)
    }

    suspend fun insertSession(courseId: Long, sessionName: String, date: Long) =
        withContext(Dispatchers.IO) {
            val newSessionId = dao().insertSession(
                Session(
                    courseId = courseId,
                    name = sessionName,
                    date = date
                )
            )

            getCourseCache().sessionIds += newSessionId
            getCourseCache().allSessions += Session(newSessionId, courseId, sessionName, date)
        }

    suspend fun insertSessions(sessions: List<Session>) {
        val newIds = dao().insertSessions(sessions)
        sessions.zip(newIds).forEach { (session, id) ->
            getCourseCache().sessionIds += id
            getCourseCache().allSessions += session.copy(id = id)
        }
    }

    suspend fun deleteSession(session: Session) {
        dao().deleteAttendancesBySessionId(session.id)
        dao().deleteSession(session)
        courseCache.deleteSession(session)
    }

    // Mark the function with the suspend keyword
    suspend fun loadSessionsForCourse(course: Course) = withContext(Dispatchers.IO) {
        if (course.id == courseCache.courseId) {
            return@withContext
        }

        // Run parallel database queries safely on the IO thread pool
        val sessionsDeferred = async { dao().getSessionsByCourse(course.id) }
        val attendanceDeferred = async { dao().getAllAttendanceForCourse(course.id) }
        val activeStudentsDeferred = async { dao().getAllStudents() }

        val sessions = sessionsDeferred.await().sortedByDescending { it.date }
        val allAttendance = attendanceDeferred.await()
        val attendeeEmails = allAttendance.map { it.studentEmail }.toSet()

        val activeStudents = activeStudentsDeferred.await().filter { it.email in attendeeEmails }

        courseCache.computeFromMinimalData(course.id, sessions, allAttendance, activeStudents)
    }

    suspend fun recordAttendance(student: Student, session: Session, timestamp: Long) {
        dao().recordAttendance(
            Attendance(
                rfid = student.rfid,
                sessionId = session.id,
                studentEmail = student.email,
                timestamp = timestamp
            )
        )
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

    suspend fun updateSessionLock(sessionId: Long, newStatus: Boolean) {
        dao().updateSessionLock(sessionId, newStatus)
        courseCache.updateSessionLock(sessionId, newStatus)
    }

    suspend fun updateSession(session: Session) {
        dao().updateSession(session)
        courseCache.updateSession(session)
    }


    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val dbCallback = object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Performance optimization: Keep Session & Attendance indexes in RAM
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