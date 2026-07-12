package com.example.presensor.tools

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.ValueRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class DataProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun ingestFromCsv_Success() {
        val csvContent = "\uFEFFHeader1,Header2\nRow1Val1,Row1Val2"
        val inputStream: InputStream = ByteArrayInputStream(csvContent.toByteArray())
        
        // Testing ingestFromInputStream directly to avoid final method mocking issues with ContentResolver
        val result = DataProcessor.ingestFromInputStream(inputStream)
        
        assertEquals(2, result.headers.size)
        assertEquals("Header1", result.headers[0])
        assertEquals(1, result.rowCount)
        assertEquals("Row1Val1", result.getCellValue(0, 0))
    }

    @Test
    fun ingestFromGoogleSheets_Success() {
        val mockSheets = mock<Sheets>()
        val mockSpreadsheets = mock<Sheets.Spreadsheets>()
        val mockValues = mock<Sheets.Spreadsheets.Values>()
        val mockGet = mock<Sheets.Spreadsheets.Values.Get>()
        
        val valueRange = ValueRange().setValues(listOf(
            listOf("H1", "H2"),
            listOf("V1", "V2")
        ))

        whenever(mockSheets.spreadsheets()).thenReturn(mockSpreadsheets)
        whenever(mockSpreadsheets.values()).thenReturn(mockValues)
        whenever(mockValues.get(any(), any())).thenReturn(mockGet)
        whenever(mockGet.execute()).thenReturn(valueRange)

        val result = DataProcessor.ingestFromGoogleSheets(context, mockSheets, "id", "range")

        assertEquals(2, result.headers.size)
        assertEquals(1, result.rowCount)
        assertEquals("V1", result.getCellValue(0, 0))
    }

    @Test
    fun ingestFromGoogleSheets_Empty_ReturnsEmptyTable() {
        val mockSheets = mock<Sheets>()
        val mockSpreadsheets = mock<Sheets.Spreadsheets>()
        val mockValues = mock<Sheets.Spreadsheets.Values>()
        val mockGet = mock<Sheets.Spreadsheets.Values.Get>()
        
        whenever(mockSheets.spreadsheets()).thenReturn(mockSpreadsheets)
        whenever(mockSpreadsheets.values()).thenReturn(mockValues)
        whenever(mockValues.get(any(), any())).thenReturn(mockGet)
        whenever(mockGet.execute()).thenReturn(ValueRange().setValues(emptyList()))

        val result = DataProcessor.ingestFromGoogleSheets(context, mockSheets, "id", "range")

        assertTrue(result.headers.isEmpty())
        assertEquals(0, result.rowCount)
    }

    @Test
    fun parseSessionsFromTable_Success() {
        val headers = listOf("Name", "Date")
        val rows = listOf(listOf("S1", "25/12/2023"), listOf("S2", "26/12/2023"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseSessionsFromTable(context, table, 1L)
        
        assertEquals(2, result.items.size)
        assertEquals("S1", result.items[0].name)
        assertEquals("S2", result.items[1].name)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun parseSessionsFromTable_InvalidDate_ReturnsError() {
        val headers = listOf("Name", "Date")
        val rows = listOf(listOf("S1", "invalid-date"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseSessionsFromTable(context, table, 1L)
        
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.errors.size)
    }

    @Test
    fun parseSessionsFromTable_AutoDetectDate_FirstColumn() {
        val headers = listOf("Col 1", "Col 2")
        val rows = listOf(listOf("25/12/2023", "Session A"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseSessionsFromTable(context, table, 1L)
        
        assertEquals(1, result.items.size)
        assertEquals("Session A", result.items[0].name)
    }

    @Test
    fun parseSessionsFromTable_AutoDetectDate_SecondColumn() {
        val headers = listOf("Col 1", "Col 2")
        val rows = listOf(listOf("Session B", "26/12/2023"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseSessionsFromTable(context, table, 1L)
        
        assertEquals(1, result.items.size)
        assertEquals("Session B", result.items[0].name)
    }

    @Test
    fun parseStudentsFromTable_Success() {
        val headers = listOf("Name", "Email")
        val rows = listOf(listOf("John", "john@example.com"), listOf("Jane", "jane@example.com"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseStudentsFromTable(context, table)
        
        assertEquals(2, result.items.size)
        assertEquals("john@example.com", result.items[0].email)
        assertEquals("jane@example.com", result.items[1].email)
    }

    @Test
    fun parseStudentsFromTable_MissingData_ReturnsError() {
        val headers = listOf("Name", "Email")
        val rows = listOf(listOf("John", ""))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseStudentsFromTable(context, table)
        
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.errors.size)
    }

    @Test
    fun parseStudentsFromTable_NameMissing_ReturnsError() {
        val headers = listOf("Name", "Email")
        val rows = listOf(listOf("", "test@test.com"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseStudentsFromTable(context, table)
        
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.errors.size)
    }

    @Test
    fun parseStudentsFromTable_AutoDetectEmailColumn() {
        val headers = listOf("Full Name", "Student Email")
        val rows = listOf(listOf("John Doe", "john@test.com"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseStudentsFromTable(context, table)
        
        assertEquals(1, result.items.size)
        assertEquals("john@test.com", result.items[0].email)
        assertEquals("John Doe", result.items[0].name)
    }

    @Test
    fun parseStudentsFromTable_ManualMapping() {
        val headers = listOf("A", "B", "C")
        val rows = listOf(listOf("John", "john@test.com", "random"))
        val table = InternalDataTable(headers, rows)
        val mapping = mapOf("name" to "A", "email" to "B")
        
        val result = DataProcessor.parseStudentsFromTable(context, table, mapping)
        
        assertEquals(1, result.items.size)
        assertEquals("john@test.com", result.items[0].email)
    }

    @Test
    fun ingestFromInputStream_EmptyStream() {
        val inputStream = ByteArrayInputStream("".toByteArray())
        val result = DataProcessor.ingestFromInputStream(inputStream)
        assertTrue(result.headers.isEmpty())
        assertEquals(0, result.rowCount)
    }

    @Test
    fun ingestFromInputStream_WithBlankLines() {
        val csv = "H1,H2\n\nV1,V2\n   \nV3,V4"
        val inputStream = ByteArrayInputStream(csv.toByteArray())
        val result = DataProcessor.ingestFromInputStream(inputStream)
        assertEquals(2, result.rowCount) // blank lines should be skipped
    }

    @Test
    fun ingestFromGoogleSheets_MissingHeaders_UsesPlaceholders() {
        val mockSheets = mock<Sheets>()
        val mockSpreadsheets = mock<Sheets.Spreadsheets>()
        val mockValues = mock<Sheets.Spreadsheets.Values>()
        val mockGet = mock<Sheets.Spreadsheets.Values.Get>()
        
        // Row 1: First header missing, Second valid. Row 2: Longer than Row 1.
        val valueRange = ValueRange().setValues(listOf(
            listOf("", "Header 2"),
            listOf("V1", "V2", "V3")
        ))

        whenever(mockSheets.spreadsheets()).thenReturn(mockSpreadsheets)
        whenever(mockSpreadsheets.values()).thenReturn(mockValues)
        whenever(mockValues.get(any(), any())).thenReturn(mockGet)
        whenever(mockGet.execute()).thenReturn(valueRange)

        val result = DataProcessor.ingestFromGoogleSheets(context, mockSheets, "id", "range")

        assertEquals(3, result.headers.size)
        // Placeholder for index 0 and 3
        assertTrue(result.headers[0].contains("1"))
        assertEquals("Header 2", result.headers[1])
        assertTrue(result.headers[2].contains("3"))
        assertEquals(1, result.rowCount)
        assertEquals("V3", result.getCellValue(0, 2))
    }

    @Test
    fun parseSessionsFromTable_SessionNameMissing_ReturnsError() {
        val headers = listOf("Date", "Name")
        val rows = listOf(listOf("25/12/2023", ""))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseSessionsFromTable(context, table, 1L)
        
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.errors.size)
        // Check if error is not empty instead of hardcoded substring which might fail on localization
        assertTrue(result.errors[0].isNotEmpty())
    }

    @Test
    fun parseSessionsFromTable_NoDateFound_ReturnsError() {
        val headers = listOf("Col1", "Col2")
        val rows = listOf(listOf("NotADate", "NotADateEither"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseSessionsFromTable(context, table, 1L)
        
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.errors.size)
    }

    @Test
    fun parseStudentsFromTable_AutoDetect_NoEmailMatch() {
        // Headers don't contain "email"
        val headers = listOf("Full Name", "Other")
        val rows = listOf(listOf("John", "john@test.com"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseStudentsFromTable(context, table)
        
        // It defaults to index 1 for email if none found
        assertEquals(1, result.items.size)
        assertEquals("john@test.com", result.items[0].email)
    }

    @Test
    fun parseStudentsFromTable_EmptyRow_Ignored() {
        val headers = listOf("Name", "Email")
        val rows = listOf(listOf("", "")) // Truly empty strings
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseStudentsFromTable(context, table)
        
        assertTrue(result.items.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun parseStudentsFromTable_PartiallyFilledRow_ReturnsError() {
        val headers = listOf("Name", "Email", "Other")
        val rows = listOf(listOf("", "", "Just some text"))
        val table = InternalDataTable(headers, rows)
        
        val result = DataProcessor.parseStudentsFromTable(context, table)
        
        assertTrue(result.items.isEmpty())
        assertEquals(1, result.errors.size) // Both missing
    }

    @Test
    fun generateCsvString_NullRfid_UsesFallback() {
        val course = Course(name = "C1")
        val s1 = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        val student = Student("e1@test.com", "John", null)
        val attendance = listOf(AttendanceRecord(100L, "John", null, "e1@test.com", "S1", 1))
        
        val result = DataProcessor.generateCsvString(context, course, listOf(s1), attendance, listOf(student))
        
        val fallback = context.getString(com.example.presensor.R.string.label_not_applicable)
        assertTrue(result.contains(fallback))
    }
}
