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


import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.AttendanceRecord
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset

object CourseUtilities {

    private fun parseSessionCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        var currentToken = StringBuilder()
        var inQuotes = false

        // Determine delimiter dynamically per line row context
        val delimiter = if (line.contains(";")) ';' else ','

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                // Check for escaped quotes ("") inside a quoted block
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    currentToken.append('"')
                    i++ // Skip next quote character
                } else {
                    inQuotes = !inQuotes // Toggle quote state machine flag
                }
            } else if ((c == delimiter) && !inQuotes) {
                tokens.add(currentToken.toString().trim())
                currentToken.setLength(0) // Reset builder buffer
            } else {
                currentToken.append(c)
            }
            i++
        }
        tokens.add(currentToken.toString().trim())
        return tokens
    }

    fun parseSessionsFromCsv(
        contentResolver: ContentResolver,
        uri: Uri,
        courseId: Long
    ): List<Session> {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val sessionsToInsert = mutableListOf<Session>()

        contentResolver.openInputStream(uri)?.use { inputStream ->
            // Force the InputStreamReader wrapper explicitly to eliminate internal system-default fallback variations
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8)
            )

            var isFirstLine = true

            reader.use { bufferedReader ->
                var line: String? = bufferedReader.readLine()

                while (line != null) {
                    // 1. Clean up Excel/Google Sheets BOM character immediately on the absolute first line
                    if (isFirstLine) {
                        if (line.startsWith("\uFEFF")) {
                            line = line.substring(1)
                        }
                        isFirstLine = false

                        // Skip processing the header row entirely
                        line = bufferedReader.readLine()
                        continue
                    }

                    // Split safely by both Comma and Semicolon formats
                    val tokens = parseSessionCsvLine(line)

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

                        // Clean string layout values directly before saving them down into Room data trees
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

                    // Advance the reader loop safely
                    line = bufferedReader.readLine()
                }
            }
        }
        return sessionsToInsert
    }

    fun parseStudentsFromCsv(contentResolver: ContentResolver, uri: Uri): List<Student> {
        val students = mutableListOf<Student>()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (line.isBlank() || (index == 0 && line.contains(
                            "email",
                            ignoreCase = true
                        ))
                    ) {
                        return@forEachIndexed
                    }

                    val tokens = line.split(Regex("[,;]")).map { it.trim() }
                    if (tokens.size >= 2) {
                        val name = tokens[0]
                        val email = tokens[1]
                        if (name.isNotEmpty() && email.isNotEmpty()) {
                            students.add(Student(email = email, name = name))
                        }
                    }
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