package com.example.presensor

import android.content.ContentResolver
import android.net.Uri
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

object CourseUtilities {

    fun parseSessionsFromCsv(contentResolver: ContentResolver, uri: Uri, courseId: Long): List<Session> {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val sessionsToInsert = mutableListOf<Session>()

        contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val tokens = line.split(Regex("[,;]")).map { it.trim() }

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
            }
        }
        return sessionsToInsert
    }

    fun parseStudentsFromCsv(contentResolver: ContentResolver, uri: Uri): List<Student> {
        val students = mutableListOf<Student>()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (line.isBlank() || (index == 0 && line.contains("email", ignoreCase = true))) {
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

    fun generateCsvString(course: Course, allSessions: List<Session>, allAttendance: List<AttendanceRecord>, allStudents: List<Student>): String {
        val csvBuilder = StringBuilder()

        val activeEmails = allAttendance.map { it.studentEmail }.toSet()
        val activeStudents = allStudents
            .filter { it.email in activeEmails }
            .sortedBy { it.name }

        csvBuilder.append("Student Name,Email,RFID")
        allSessions.forEach { session ->
            csvBuilder.append(",${session.name}")
        }
        csvBuilder.append("\n")

        activeStudents.forEach { student ->
            csvBuilder.append("${student.name},${student.email},${student.rfid ?: "N/A"}")

            allSessions.forEach { session ->
                val wasPresent = allAttendance.any {
                    it.studentEmail == student.email && it.sessionName == session.name
                }
                csvBuilder.append(if (wasPresent) ",P" else ",")
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

    fun fromMillisToLocalDate(date: Long): LocalDate {
        return Instant.ofEpochMilli(date)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }

    fun fromMillisToLocalDateTime(millis: Long): LocalDateTime {
        return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(millis),
            ZoneId.systemDefault()
        )
    }

    fun makeSessionTimeFormatter(): DateTimeFormatter {
        return DateTimeFormatter.ofPattern("EEEE, dd 'de' MMMM", Locale.getDefault())
    }

    fun formatYearSemester(year: Int, semester: Int): String {
        return "$year/$semester"
    }
}