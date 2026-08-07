package com.example.presensor.tools

import android.content.Context
import com.example.presensor.R
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
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class DataProcessorTest {

    private val context: Context = mock {
        on { getString(R.string.label_mapping_none) } doReturn "None (Skip)"
        on { getString(R.string.csv_header_student_identity) } doReturn "Name,Email,Tag"
        on { getString(R.string.label_not_applicable) } doReturn "N/A"
        on { getString(eq(R.string.label_column_placeholder), any()) } doAnswer { invocation -> "Column ${invocation.arguments[1]}" }
        on { getString(eq(R.string.error_import_row_session_name_missing), any()) } doAnswer { invocation -> "Row ${invocation.arguments[1]}: Name missing" }
        on { getString(eq(R.string.error_import_row_invalid_date), any(), any()) } doAnswer { invocation -> "Row ${invocation.arguments[1]}: Invalid date ${invocation.arguments[2]}" }
        on { getString(eq(R.string.error_import_row_student_name_email_missing), any()) } doAnswer { invocation -> "Row ${invocation.arguments[1]}: Name and Email missing" }
        on { getString(eq(R.string.error_import_row_student_name_missing), any()) } doAnswer { invocation -> "Row ${invocation.arguments[1]}: Name missing" }
        on { getString(eq(R.string.error_import_row_student_email_missing), any()) } doAnswer { invocation -> "Row ${invocation.arguments[1]}: Email missing" }
    }

    @Test
    fun `parseCsvLine handles various delimiters and complex quotes`() {
        assertEquals(listOf("a", "b", "c"), DataProcessor.parseCsvLine("a,b,c"))
        assertEquals(listOf("a", "b", "c"), DataProcessor.parseCsvLine("a;b;c"))
        assertEquals(listOf("a,b", "c"), DataProcessor.parseCsvLine("\"a,b\",c"))
        assertEquals(listOf("a\"b", "c"), DataProcessor.parseCsvLine("\"a\"\"b\",c"))
        assertEquals(listOf("a", "b", "c"), DataProcessor.parseCsvLine(" a , b , c "))
        
        // Complex quoted fields
        assertEquals(listOf("field", "contains \"quote\"", "end"), DataProcessor.parseCsvLine("field,\"contains \"\"quote\"\"\",end"))
        // Mixed delimiters logic: it chooses ; if it exists
        assertEquals(listOf("a,b", "c"), DataProcessor.parseCsvLine("a,b;c"))
    }

    @Test
    fun `ingestFromInputStream correctly parses CSV stream and handles BOM`() {
        // No BOM
        val csv = "Name,Email\nJohn Doe,john@example.com\nJane Doe,jane@example.com"
        val inputStream = ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8))
        val table = DataProcessor.ingestFromInputStream(inputStream)
        assertEquals(listOf("Name", "Email"), table.headers)
        assertEquals(2, table.rows.size)
        
        // With UTF-8 BOM
        val csvWithBom = "\uFEFFName,Email\nJohn Doe,john@example.com"
        val inputStreamBom = ByteArrayInputStream(csvWithBom.toByteArray(StandardCharsets.UTF_8))
        val tableBom = DataProcessor.ingestFromInputStream(inputStreamBom)
        assertEquals(listOf("Name", "Email"), tableBom.headers)
        assertEquals(1, tableBom.rows.size)
    }

    @Test
    fun `ingestFromGoogleSheets handles empty sheet`() {
        val sheetsService: Sheets = mock()
        val spreadsheets: Sheets.Spreadsheets = mock()
        val values: Sheets.Spreadsheets.Values = mock()
        val get: Sheets.Spreadsheets.Values.Get = mock()
        
        whenever(sheetsService.spreadsheets()).thenReturn(spreadsheets)
        whenever(spreadsheets.values()).thenReturn(values)
        whenever(values.get(any(), any())).thenReturn(get)
        whenever(get.execute()).thenReturn(ValueRange().setValues(null))
        
        val table = DataProcessor.ingestFromGoogleSheets(context, sheetsService, "id", "range")
        
        assertTrue(table.headers.isEmpty())
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun `ingestFromGoogleSheets handles inconsistent row lengths and placeholders`() {
        val sheetsService: Sheets = mock()
        val spreadsheets: Sheets.Spreadsheets = mock()
        val values: Sheets.Spreadsheets.Values = mock()
        val get: Sheets.Spreadsheets.Values.Get = mock()
        
        whenever(sheetsService.spreadsheets()).thenReturn(spreadsheets)
        whenever(spreadsheets.values()).thenReturn(values)
        whenever(values.get(any(), any())).thenReturn(get)
        
        // Max cols is 3
        val sheetData = listOf(
            listOf("Header1", ""), // Empty string header
            listOf("V1", "V2", "V3") // Row longer than header
        )
        whenever(get.execute()).thenReturn(ValueRange().setValues(sheetData))
        
        val table = DataProcessor.ingestFromGoogleSheets(context, sheetsService, "id", "range")
        
        assertEquals(3, table.headers.size)
        assertEquals("Header1", table.headers[0])
        assertEquals("Column 2", table.headers[1]) // Placeholder for empty
        assertEquals("Column 3", table.headers[2]) // Placeholder for extra column
        
        assertEquals(1, table.rows.size)
        assertEquals(listOf("V1", "V2", "V3"), table.rows[0])
    }

    @Test
    fun `parseSessionsFromTable handles fallbacks and error collection`() {
        val course = Course(id = 1, name = "Test", startTime = 480, endTime = 600)
        val table = InternalDataTable(
            headers = listOf("Name", "Date"),
            rows = listOf(
                listOf("Session 1", "25/12/2023"),
                listOf("", "26/12/2023"), // Name missing
                listOf("Session 3", "invalid") // Invalid date
            )
        )
        val mapping = mapOf("name" to "Name", "date" to "Date")

        val result = DataProcessor.parseSessionsFromTable(context, table, course, mapping)
        
        assertEquals(1, result.items.size)
        assertEquals("Session 1", result.items[0].name)
        assertEquals(480L, result.items[0].startTime)
        
        assertEquals(2, result.errors.size)
        assertTrue(result.errors[0].contains("Name missing"))
        assertTrue(result.errors[1].contains("Invalid date"))
    }

    @Test
    fun `parseSessionsFromTable respects None (Skip) mapping and auto-detection`() {
        val course = Course(id = 1, name = "C1")
        
        // Test None (Skip)
        val tableSkip = InternalDataTable(
            headers = listOf("Name", "Date"),
            rows = listOf(listOf("S1", "25/12/2023"))
        )
        val mapping = mapOf("name" to "Name", "date" to "None (Skip)")
        val resultSkip = DataProcessor.parseSessionsFromTable(context, tableSkip, course, mapping)
        // It falls back to auto-detection because mapping["date"] == "None (Skip)" which is noneLabel
        assertEquals(1, resultSkip.items.size)
        assertEquals("S1", resultSkip.items[0].name)

        // Test auto-detection logic (guessing date vs name)
        val tableAuto = InternalDataTable(
            headers = listOf("Col1", "Col2"),
            rows = listOf(listOf("25/12/2023", "Session X"))
        )
        val resultAuto = DataProcessor.parseSessionsFromTable(context, tableAuto, course)
        assertEquals(1, resultAuto.items.size)
        assertEquals("Session X", resultAuto.items[0].name)
    }

    @Test
    fun `parseStudentsFromTable handles mapping, auto-detection and errors`() {
        // Manual mapping
        val tableMap = InternalDataTable(
            headers = listOf("Full Name", "E-mail Address"),
            rows = listOf(listOf("John Doe", "john@example.com"))
        )
        val mapping = mapOf("name" to "Full Name", "email" to "E-mail Address")
        val resultId = DataProcessor.parseStudentsFromTable(context, tableMap, mapping)
        assertEquals("John Doe", resultId.items[0].name)
        assertEquals("john@example.com", resultId.items[0].email)

        // Auto-detection
        val tableAuto = InternalDataTable(
            headers = listOf("The Student Name", "Contact Email"),
            rows = listOf(listOf("Jane Doe", "jane@example.com"))
        )
        val resultAuto = DataProcessor.parseStudentsFromTable(context, tableAuto)
        assertEquals("Jane Doe", resultAuto.items[0].name)
        assertEquals("jane@example.com", resultAuto.items[0].email)

        // Error collection
        val tableErr = InternalDataTable(
            headers = listOf("name", "email", "other"),
            rows = listOf(
                listOf("", "only-email@test.com", "abc"), // Name missing
                listOf("Only Name", "", "abc"),         // Email missing
                listOf("", "", "something")            // Both missing (but row not empty)
            )
        )
        val resultErr = DataProcessor.parseStudentsFromTable(context, tableErr)
        assertEquals(0, resultErr.items.size)
        assertEquals(3, resultErr.errors.size)
        assertTrue(resultErr.errors[0].contains("Name missing"))
        assertTrue(resultErr.errors[1].contains("Email missing"))
        assertTrue(resultErr.errors[2].contains("Name and Email missing"))
    }

    @Test
    fun `generateCsvString creates valid attendance CSV with multiple students`() {
        val course = Course(name = "Math")
        val s1 = Session(id = 1, courseId = 1, name = "S1", date = 0L)
        val s2 = Session(id = 2, courseId = 1, name = "S2", date = 0L)
        val student1 = Student("s1@test.com", "Alice")
        val student2 = Student("s2@test.com", "Bob")
        
        val attendance = listOf(
            AttendanceRecord(0L, "Alice", null, "s1@test.com", "S1", 1L), // Alice present S1
            AttendanceRecord(0L, "Bob", null, "s2@test.com", "S2", 2L)    // Bob present S2
        )
        
        val result = DataProcessor.generateCsvString(context, course, listOf(s1, s2), attendance, listOf(student1, student2))
        
        val lines = result.trim().split("\n")
        assertEquals(3, lines.size) // Header + 2 students
        assertEquals("Name,Email,Tag,S1,S2", lines[0])
        assertTrue(lines.any { it.startsWith("Alice,s1@test.com,N/A,1,0") })
        assertTrue(lines.any { it.startsWith("Bob,s2@test.com,N/A,0,1") })
    }
}
