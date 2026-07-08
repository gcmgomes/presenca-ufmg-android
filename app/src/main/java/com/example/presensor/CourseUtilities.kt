package com.example.presensor

import android.content.ContentResolver
import android.content.Context
import android.content.res.TypedArray
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale


import com.example.presensor.data.DataTransceiver
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.AttendanceRecord
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset

object CourseUtilities {

    fun parseSessionsFromTable(
        table: InternalDataTable,
        courseId: Long
    ): List<Session> {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val sessionsToInsert = mutableListOf<Session>()

        for (i in 0 until table.rowCount) {
            val tokens = table.rows[i]
            if (tokens.size >= 2) {
                var sessionName = ""
                var localDate: LocalDate? = null

                val firstTokenDate = tryParseDate(tokens[0], formatter)
                val secondTokenDate = tryParseDate(tokens[1], formatter)

                when {
                    firstTokenDate != null -> {
                        localDate = firstTokenDate
                        sessionName = tokens[1]
                    }

                    secondTokenDate != null -> {
                        localDate = secondTokenDate
                        sessionName = tokens[0]
                    }
                }

                if (localDate != null && sessionName.isNotEmpty()) {
                    val timestamp = localDate.atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()

                    sessionsToInsert.add(
                        Session(
                            courseId = courseId,
                            name = sessionName,
                            date = timestamp
                        )
                    )
                }
            }
        }
        return sessionsToInsert
    }

    fun parseStudentsFromTable(table: InternalDataTable): List<Student> {
        val students = mutableListOf<Student>()
        
        // Handle "email" column indexing if headers exist
        val emailIdx = table.headers.indexOfFirst { it.contains("email", ignoreCase = true) }.let { if (it == -1) 1 else it }
        val nameIdx = if (emailIdx == 0) 1 else 0

        for (i in 0 until table.rowCount) {
            val row = table.rows[i]
            if (row.size >= 2) {
                val name = row.getOrNull(nameIdx) ?: ""
                val email = row.getOrNull(emailIdx) ?: ""
                if (name.isNotEmpty() && email.isNotEmpty()) {
                    students.add(Student(email = email, name = name))
                }
            }
        }
        return students
    }

    fun generateCsvString(
        context: Context,
        course: Course,
        allSessions: List<Session>,
        allAttendance: List<AttendanceRecord>,
        allStudents: List<Student>
    ): String {
        val csvBuilder = StringBuilder()

        val activeEmails = allAttendance.map { it.studentEmail }.toSet()
        val activeStudents = allStudents
            .filter { it.email in activeEmails }
            .sortedBy { it.name }

        // Extracted standard headers to R.string to keep export layouts decoupled from static literals
        csvBuilder.append(context.getString(R.string.csv_header_student_identity))
        allSessions.forEach { session ->
            csvBuilder.append(",${session.name}")
        }
        csvBuilder.append("\n")

        val fallbackNa = context.getString(R.string.label_not_applicable)

        activeStudents.forEach { student ->
            csvBuilder.append("${student.name},${student.email},${student.rfid ?: fallbackNa}")

            allSessions.forEach { session ->
                val wasPresent = allAttendance.any {
                    it.studentEmail == student.email && it.sessionName == session.name
                }
                csvBuilder.append(if (wasPresent) ",1" else ",0")
            }
            csvBuilder.append("\n")
        }

        return csvBuilder.toString()
    }

    fun tryParseDate(text: String, formatter: DateTimeFormatter): LocalDate? {
        return try {
            LocalDate.parse(text, formatter)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    fun isDateInCurrentWeek(targetDate: LocalDate): Boolean {
        val today = LocalDate.now()
        val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        return !targetDate.isBefore(startOfWeek) && !targetDate.isAfter(endOfWeek)
    }

    fun fromMillisToLocalDate(utcMillis: Long): LocalDate {
        return Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    }

    fun fromMillisToLocalDateTime(millis: Long): LocalDateTime {
        return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(millis),
            ZoneId.systemDefault()
        )
    }

    fun makeSessionTimeFormatter(context: Context): DateTimeFormatter {
        // Now resolves the localized format dynamically via layout configurations
        val pattern = context.getString(R.string.session_date_display_format)
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    }

    fun updateLockIconUI(isLocked: Boolean, lockIcon: ImageView) {
        if (isLocked) {
            lockIcon.setImageResource(R.drawable.status_lock)
            lockIcon.alpha = 1.0f
        } else {
            lockIcon.setImageResource(R.drawable.status_unlock)
            lockIcon.alpha = 0.5f
        }
    }


    fun updateEditIconUI(isLocked: Boolean, editIcon: ImageView) {
        if (isLocked) {
            editIcon.setImageResource(R.drawable.ic_edit)
            editIcon.alpha = 0.4f
        } else {
            editIcon.setImageResource(R.drawable.ic_edit)
            editIcon.alpha = 1.0f
        }
    }


    fun getColorForAccent(courseName: String, colorArray: TypedArray): Int {
        val colors = IntArray(colorArray.length())
        for (i in 0 until colorArray.length()) {
            colors[i] = colorArray.getColor(i, 0)
        }
        colorArray.recycle()
        return colors[Math.abs(courseName.hashCode()) % colors.size]
    }


    fun fillCourseDetailedCardStatistics(
        activity: AppCompatActivity,
        card: View,
        course: Course,
        sessionIds: Set<Long>,
        studentEmails: Set<String>,
        courseAttendances: List<AttendanceRecord>
    ) {
        card.findViewById<TextView>(R.id.txtDetailCourseName).text = course.name

        // Dynamic localized layout ordinal mapping integration ("1st Semester" vs "1º Semestre")
        val semesterOrdinal = if (course.semester == 1) {
            activity.getString(R.string.semester_ordinal_1st)
        } else {
            activity.getString(R.string.semester_ordinal_2nd)
        }
        card.findViewById<TextView>(R.id.txtDetailCourseSemester).text =
            activity.getString(R.string.semester_display_format, course.year, semesterOrdinal)

        card.findViewById<View>(R.id.viewCourseDetailAccent)
            .setBackgroundColor(
                getColorForAccent(
                    course.name,
                    activity.resources.obtainTypedArray(R.array.chalk_colors_list)
                )
            )

        val studentCount = studentEmails.size
        val sessionCount = sessionIds.size

        val avgAttendance = if (studentCount > 0 && sessionCount > 0) {
            val totalPossible = studentCount * sessionCount
            val actualLogs =
                courseAttendances.map { it.sessionId to it.studentEmail }.distinct().size
            (actualLogs.toFloat() / totalPossible.toFloat() * 100).toInt()
        } else {
            0
        }

        card.findViewById<TextView>(R.id.txtStatStudentCount).text = studentCount.toString()
        card.findViewById<TextView>(R.id.txtStatSessionCount).text = sessionCount.toString()
        card.findViewById<TextView>(R.id.txtStatAvgAttendance).text = "$avgAttendance%"
    }
}