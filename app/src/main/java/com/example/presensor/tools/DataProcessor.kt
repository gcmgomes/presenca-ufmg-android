package com.example.presensor.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
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

    fun ingestFromCsv(contentResolver: ContentResolver, uri: Uri, caller: String = "Unknown"): InternalDataTable {
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
                
                Log.d("DataProcessor", "Ingestion pipeline started by: $caller")
                Log.d("DataProcessor", "Headers found in CSV: ${headers.joinToString(", ")}")

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
        context: Context,
        sheetsService: Sheets,
        spreadsheetId: String,
        range: String,
        caller: String = "Unknown"
    ): InternalDataTable {
        val response = sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
        val values = response.getValues() ?: emptyList<List<Any>>()

        if (values.isEmpty()) {
            Log.d("DataProcessor", "Ingestion pipeline started by: $caller - Sheet is empty.")
            return InternalDataTable(emptyList(), emptyList())
        }

        // Determine the maximum number of columns across all rows to ensure no data is truncated
        val maxCols = values.maxOf { it.size }
        val rawHeaders = values[0]
        
        val headers = (0 until maxCols).map { i ->
            rawHeaders.getOrNull(i)?.toString()?.trim()?.ifEmpty { context.getString(R.string.label_column_placeholder, i + 1) } ?: context.getString(R.string.label_column_placeholder, i + 1)
        }
        
        Log.d("DataProcessor", "Ingestion pipeline started by: $caller")
        Log.d("DataProcessor", "Headers found in Google Sheets: ${headers.joinToString(", ")}")

        val rows = values.drop(1).map { row -> 
            (0 until maxCols).map { i -> row.getOrNull(i)?.toString() ?: "" }
        }

        return InternalDataTable(headers, rows)
    }

    data class ImportResult<T>(
        val items: List<T>,
        val errors: List<String>
    )

    fun parseSessionsFromTable(
        context: Context,
        table: InternalDataTable,
        courseId: Long,
        mapping: Map<String, String>? = null
    ): ImportResult<Session> {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val sessionsToInsert = mutableListOf<Session>()
        val errors = mutableListOf<String>()

        val nameCol = mapping?.get("name")
        val dateCol = mapping?.get("date")

        for (i in 0 until table.rowCount) {
            val tokens = table.rows[i]
            val rowNum = i + 2 // +1 for 0-indexing, +1 for header
            
            var sessionName = ""
            var localDate: LocalDate? = null
            var dateStrForError = ""

            if (nameCol != null && dateCol != null) {
                sessionName = table.getCellValue(i, nameCol)
                val dateStr = table.getCellValue(i, dateCol)
                dateStrForError = dateStr
                localDate = TimeUtils.tryParseDate(dateStr, formatter)
            } else if (tokens.size >= 2) {
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
                    else -> {
                        dateStrForError = "${tokens[0]} or ${tokens[1]}"
                    }
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
            } else {
                if (sessionName.isEmpty()) {
                    errors.add(context.getString(R.string.error_import_row_session_name_missing, rowNum))
                } else if (localDate == null) {
                    errors.add(context.getString(R.string.error_import_row_invalid_date, rowNum, dateStrForError))
                }
            }
        }
        return ImportResult(sessionsToInsert, errors)
    }

    fun parseStudentsFromTable(
        context: Context,
        table: InternalDataTable,
        mapping: Map<String, String>? = null
    ): ImportResult<Student> {
        val students = mutableListOf<Student>()
        val errors = mutableListOf<String>()
        
        val nameCol = mapping?.get("name")
        val emailCol = mapping?.get("email")

        val emailIdx = if (emailCol != null) {
            table.headers.indexOf(emailCol)
        } else {
            table.headers.indexOfFirst { it.contains("email", ignoreCase = true) }.let { if (it == -1) 1 else it }
        }
        
        val nameIdx = if (nameCol != null) {
            table.headers.indexOf(nameCol)
        } else {
            if (emailIdx == 0) 1 else 0
        }

        for (i in 0 until table.rowCount) {
            val row = table.rows[i]
            val rowNum = i + 2
            val name = row.getOrNull(nameIdx) ?: ""
            val email = row.getOrNull(emailIdx) ?: ""
            
            if (name.isNotEmpty() && email.isNotEmpty()) {
                students.add(Student(email = email, name = name))
            } else {
                if (name.isEmpty() && email.isEmpty()) {
                    // Skip completely empty rows without error? 
                    // Usually better to report if it looks like data was intended.
                    if (row.any { it.isNotBlank() }) {
                        errors.add(context.getString(R.string.error_import_row_student_name_email_missing, rowNum))
                    }
                } else if (name.isEmpty()) {
                    errors.add(context.getString(R.string.error_import_row_student_name_missing, rowNum))
                } else {
                    errors.add(context.getString(R.string.error_import_row_student_email_missing, rowNum))
                }
            }
        }
        return ImportResult(students, errors)
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
