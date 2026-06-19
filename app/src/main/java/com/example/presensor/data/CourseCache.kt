package com.example.presensor.data

import android.view.View
import com.example.presensor.CourseUtilities
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.AttendanceRecord
import java.time.ZoneId

class CourseCache {
    // Complete data payload collections retrieved from the database
    var courseId: Long? = null
    var activeStudents: List<Student> = emptyList()
    var activeStudentEmails: Set<String> = emptySet()
    var allSessions: List<Session> = emptyList()
    var allAttendance: List<AttendanceRecord> = emptyList()
    var sessionIds: Set<Long> = emptySet()

    var allStudents: List<Student> = emptyList()

    /**
     * Filters the cached student roster list based on the user's search query string match constraints.
     * Runs instantly in memory with zero database transaction overhead.
     */
    fun getFilteredStudents(query: String): List<Student> {
        return if (query.isBlank()) {
            activeStudents
        } else {
            activeStudents.filter { it.name.contains(query, ignoreCase = true) }
        }
    }

    fun computeFromMinimalData(
        courseId: Long?,
        sessions: List<Session>,
        attendances: List<AttendanceRecord>,
        students: List<Student>
    ) {
        this.courseId = courseId
        allSessions = sessions
        allAttendance = attendances
        activeStudentEmails = allAttendance.map { it.studentEmail }.toSet()
        activeStudents = students.filter { it.email in activeStudentEmails }
        sessionIds = allSessions.map { it.id }.toSet()
    }

    fun deleteSession(session: Session) {
        computeFromMinimalData(
            courseId,
            allSessions.filter { it.id != session.id },
            allAttendance.filter { it.sessionId != session.id },
            activeStudents
        )
    }

    fun addAttendance(student: Student, session: Session, attendanceRecord: AttendanceRecord) {
        if (!activeStudentEmails.contains(student.email)) {
            activeStudents += student
            activeStudentEmails += student.email
        }
        allAttendance += attendanceRecord
    }

    fun updateSessionLock(sessionId: Long, newStatus: Boolean) {
        allSessions.find { it.id == sessionId }?.isLocked = newStatus
    }

    fun updateSession(session: Session) {
        allSessions.find { it.id == session.id }?.name = session.name
        allSessions.find { it.id == session.id }?.date = session.date
    }


    /**
     * Resets the entire state when navigating away or switching contexts.
     */
    fun clear() {
        courseId = null
        activeStudents = emptyList()
        activeStudentEmails = emptySet()
        allSessions = emptyList()
        allAttendance = emptyList()
        sessionIds = emptySet()
    }
}