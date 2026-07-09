package com.example.presensor.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.presensor.R
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.google.api.services.sheets.v4.Sheets
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DataProcessor {

    fun parseCsvLine(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val currentToken = StringBuilder()
        var inQuotes = false

        // Determine delimiter dynamically per line row context
        val delimiter = if (line.contains(";")) ';' else ','

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    currentToken.append('"')
                    i++ 
                } else {
                    inQuotes = !inQuotes 
                }
            } else if ((c == delimiter) && !inQuotes) {
                tokens.add(currentToken.toString().trim())
                currentToken.setLength(0) 
            } else {
                currentToken.append(c)
            }
            i++
        }
        tokens.add(currentToken.toString().trim())
        return tokens
    }

    fun ingestFromCsv(contentResolver: ContentResolver, uri: Uri): InternalDataTable {
        val rows = mutableListOf<List<String>>()
        var headers = listOf<String>()

        contentResolver.openInputStream(uri)?.use { inputStream ->
            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val lines = reader.readLines()
            if (lines.isNotEmpty()) {
                var firstLine = lines[0]
                if (firstLine.startsWith("\uFEFF")) {
                    firstLine = firstLine.substring(1)
                }
                headers = parseCsvLine(firstLine)
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    if (line.isNotBlank()) {
                        rows.add(parseCsvLine(line))
                    }
                }
            }
        }
        return InternalDataTable(headers, rows)
    }

    fun ingestFromGoogleSheets(
        sheetsService: Sheets,
        spreadsheetId: String,
        range: String
    ): InternalDataTable {
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
        val values = response.getValues() ?: emptyList<List<Any>>()

        if (values.isEmpty()) return InternalDataTable(emptyList(), emptyList())

        val headers = values[0].map { it.toString() }
        val rows = values.drop(1).map { row -> 
            headers.indices.map { i -> row.getOrNull(i)?.toString() ?: "" }
        }

        return InternalDataTable(headers, rows)
    }

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

                val firstTokenDate = TimeUtils.tryParseDate(tokens[0], formatter)
                val secondTokenDate = TimeUtils.tryParseDate(tokens[1], formatter)

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
}
