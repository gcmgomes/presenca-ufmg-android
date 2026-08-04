package com.example.presensor.tools

import android.content.Context
import com.example.presensor.R
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.ValueRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream

class DataProcessorTest {

    private val context: Context = mock {
        on { getString(R.string.label_mapping_none) } doReturn "None (Skip)"
        on { getString(R.string.csv_header_student_identity) } doReturn "Name,Email,Tag"
        on { getString(R.string.label_not_applicable) } doReturn "N/A"
    }

    @Test
    fun `parseCsvLine handles various delimiters and quotes`() {
        assertEquals(listOf("a", "b", "c"), DataProcessor.parseCsvLine("a,b,c"))
        assertEquals(listOf("a", "b", "c"), DataProcessor.parseCsvLine("a;b;c"))
        assertEquals(listOf("a,b", "c"), DataProcessor.parseCsvLine("\"a,b\",c"))
        assertEquals(listOf("a\"b", "c"), DataProcessor.parseCsvLine("\"a\"\"b\",c"))
        assertEquals(listOf("a", "b", "c"), DataProcessor.parseCsvLine(" a , b , c "))
    }

    @Test
    fun `ingestFromInputStream correctly parses CSV stream`() {
        val csv = "Name,Email\nJohn Doe,john@example.com\nJane Doe,jane@example.com"
        val inputStream = ByteArrayInputStream(csv.toByteArray())
        
        val table = DataProcessor.ingestFromInputStream(inputStream)
        
        assertEquals(listOf("Name", "Email"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("John Doe", "john@example.com"), table.rows[0])
    }

    @Test
    fun `parseStudentsFromTable correctly maps columns`() {
        val table = InternalDataTable(
            headers = listOf("Full Name", "E-mail Address"),
            rows = listOf(listOf("John Doe", "john@example.com"))
        )
        val mapping = mapOf("name" to "Full Name", "email" to "E-mail Address")
        
        val result = DataProcessor.parseStudentsFromTable(context, table, mapping)
        
        assertEquals(1, result.items.size)
        assertEquals("John Doe", result.items[0].name)
        assertEquals("john@example.com", result.items[0].email)
    }

    @Test
    fun `parseSessionsFromTable handles fallbacks to course default times`() {
        val course = Course(id = 1, name = "Test", startTime = 480, endTime = 600) // 08:00 - 10:00
        val table = InternalDataTable(
            headers = listOf("Name", "Date"),
            rows = listOf(listOf("Session 1", "25/12/2023"))
        )
        val mapping = mapOf("name" to "Name", "date" to "Date")

        val result = DataProcessor.parseSessionsFromTable(context, table, course, mapping)
        
        assertEquals(1, result.items.size)
        val session = result.items[0]
        assertEquals(480L, session.startTime)
        assertEquals(600L, session.endTime)
    }

    @Test
    fun `parseSessionsFromTable overrides course defaults with mapped times`() {
        val course = Course(id = 1, name = "Test", startTime = 480, endTime = 600)
        val table = InternalDataTable(
            headers = listOf("Name", "Date", "Start", "End"),
            rows = listOf(listOf("Session 1", "25/12/2023", "14:00", "16:00"))
        )
        val mapping = mapOf("name" to "Name", "date" to "Date", "start_time" to "Start", "end_time" to "End")

        val result = DataProcessor.parseSessionsFromTable(context, table, course, mapping)
        
        assertEquals(1, result.items.size)
        val session = result.items[0]
        assertEquals(840L, session.startTime) // 14:00
        assertEquals(960L, session.endTime)   // 16:00
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
    fun `generateCsvString creates valid attendance CSV`() {
        val course = Course(name = "History")
        val student = Student("john@test.com", "John", "RFID1")
        val students = listOf(student)
        val sessions = listOf(Session(id = 1, courseId = 1, name = "L1", date = 0))
        val attendance = listOf(
            com.example.presensor.data.entities.AttendanceRecord(
                timestamp = 1000L,
                studentName = "John",
                studentRfid = "RFID1",
                studentEmail = "john@test.com",
                sessionName = "L1",
                sessionId = 1
            )
        )
        
        val result = DataProcessor.generateCsvString(context, course, sessions, attendance, students)
        
        assertTrue(result.contains("Name,Email,Tag"))
        assertTrue(result.contains("John"))
        assertTrue(result.contains("john@test.com"))
    }
}
