package com.example.presensor.data

import android.content.ContentResolver
import android.net.Uri
import com.google.api.services.sheets.v4.Sheets
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object DataTransceiver {

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
}
