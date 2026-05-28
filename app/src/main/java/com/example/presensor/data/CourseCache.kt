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