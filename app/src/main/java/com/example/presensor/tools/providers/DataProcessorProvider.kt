package com.example.presensor.tools.providers

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.DataProcessor
import com.google.api.services.sheets.v4.Sheets

interface DataProcessorProvider {
    suspend fun ingestFromGoogleSheets(
        context: Context,
        sheetsService: Sheets,
        spreadsheetId: String,
        range: String,
        caller: String
    ): InternalDataTable

    suspend fun ingestFromCsv(
        contentResolver: ContentResolver,
        uri: Uri,
        caller: String
    ): InternalDataTable

    fun parseSessionsFromTable(
        context: Context,
        table: InternalDataTable,
        courseId: Long,
        mapping: Map<String, String>?
    ): DataProcessor.ImportResult<Session>

    fun parseStudentsFromTable(
        context: Context,
        table: InternalDataTable,
        mapping: Map<String, String>?
    ): DataProcessor.ImportResult<Student>
}

class AndroidDataProcessorProvider : DataProcessorProvider {
    override suspend fun ingestFromGoogleSheets(
        context: Context,
        sheetsService: Sheets,
        spreadsheetId: String,
        range: String,
        caller: String
    ): InternalDataTable {
        return DataProcessor.ingestFromGoogleSheets(context, sheetsService, spreadsheetId, range, caller)
    }

    override suspend fun ingestFromCsv(
        contentResolver: ContentResolver,
        uri: Uri,
        caller: String
    ): InternalDataTable {
        return DataProcessor.ingestFromCsv(contentResolver, uri, caller)
    }

    override fun parseSessionsFromTable(
        context: Context,
        table: InternalDataTable,
        courseId: Long,
        mapping: Map<String, String>?
    ): DataProcessor.ImportResult<Session> {
        return DataProcessor.parseSessionsFromTable(context, table, courseId, mapping)
    }

    override fun parseStudentsFromTable(
        context: Context,
        table: InternalDataTable,
        mapping: Map<String, String>?
    ): DataProcessor.ImportResult<Student> {
        return DataProcessor.parseStudentsFromTable(context, table, mapping)
    }
}
