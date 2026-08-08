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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ToolsCoverageTest {

    private val context: Context = mock()

    @Before
    fun setup() {
        whenever(context.getString(eq(R.string.label_column_placeholder), anyVararg())).thenAnswer {
            "Col ${it.arguments[1]}"
        }
        whenever(context.getString(R.string.label_mapping_none)).thenReturn("None")
        whenever(context.getString(any(), anyVararg())).thenReturn("Mock String")
    }

    @Test
    fun `DataProcessor parseCsvLine handles various formats`() {
        // Comma delimiter
        val tokens1 = DataProcessor.parseCsvLine("a,b,c")
        assertEquals(listOf("a", "b", "c"), tokens1)

        // Semicolon delimiter
        val tokens2 = DataProcessor.parseCsvLine("a;b;c")
        assertEquals(listOf("a", "b", "c"), tokens2)

        // Quotes and escaped quotes
        val tokens3 = DataProcessor.parseCsvLine("\"a,b\",\"c\"\"d\",e")
        assertEquals(listOf("a,b", "c\"d", "e"), tokens3)

        // Empty tokens
        val tokens4 = DataProcessor.parseCsvLine("a,,c")
        assertEquals(listOf("a", "", "c"), tokens4)
    }

    @Test
    fun `DataProcessor ingestFromInputStream handles BOM and empty lines`() {
        val csv = "\uFEFFheader1,header2\nval1,val2\n\nval3,val4"
        val table = DataProcessor.ingestFromInputStream(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(2, table.headers.size)
        assertEquals("header1", table.headers[0])
        assertEquals(2, table.rowCount)
        assertEquals("val3", table.rows[1][0])
    }

    @Test
    fun `DataProcessor ingestFromGoogleSheets handles empty and placeholders`() {
        val sheets: Sheets = mock(Answers = Answers.RETURNS_DEEP_STUBS)
        val response = ValueRange()
        
        // Empty response
        whenever(sheets.spreadsheets().values().get(any(), any()).execute()).thenReturn(response)
        val table1 = DataProcessor.ingestFromGoogleSheets(context, sheets, "id", "range")
        assertEquals(0, table1.headers.size)

        // Data with empty headers
        response.setValues(listOf(
            listOf("H1", "", "H3"),
            listOf("V1", "V2")
        ))
        val table2 = DataProcessor.ingestFromGoogleSheets(context, sheets, "id", "range")
        assertEquals(3, table2.headers.size)
        assertEquals("H1", table2.headers[0])
        assertEquals("Col 2", table2.headers[1])
        assertEquals("V1", table2.rows[0][0])
        assertEquals("", table2.rows[0][2]) // Padding for shorter row
    }

    @Test
    fun `DataProcessor parseSessionsFromTable with mapping`() {
        val headers = listOf("Name", "Date", "Start", "End")
        val rows = listOf(
            listOf("Session 1", "01/01/2024", "08:00", "10:00"),
            listOf("Invalid Date", "invalid", "", "")
        )
        val table = InternalDataTable(headers, rows)
        val course = Course(id = 1, name = "C1", startTime = 480, endTime = 600)
        val mapping = mapOf("name" to "Name", "date" to "Date", "start_time" to "Start", "end_time" to "End")

        val result = DataProcessor.parseSessionsFromTable(context, table, course, mapping)
        
        assertEquals(1, result.items.size)
        assertEquals("Session 1", result.items[0].name)
        assertEquals(480L, result.items[0].startTime)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("Invalid Date"))
    }

    @Test
    fun `DataProcessor parseSessionsFromTable heuristic fallback`() {
        val headers = listOf("Col1", "Col2")
        val rows = listOf(
            listOf("01/01/2024", "Session A"),
            listOf("Session B", "02/01/2024"),
            listOf("NoDate", "NoDate")
        )
        val table = InternalDataTable(headers, rows)
        val course = Course(id = 1)

        val result = DataProcessor.parseSessionsFromTable(context, table, course, null)
        
        assertEquals(2, result.items.size)
        assertEquals("Session A", result.items[0].name)
        assertEquals("Session B", result.items[1].name)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `DataProcessor parseStudentsFromTable`() {
        val headers = listOf("Full Name", "Email Address")
        val rows = listOf(
            listOf("John", "john@test.com"),
            listOf("", "only@email.com"),
            listOf("Only Name", ""),
            listOf("", "")
        )
        val table = InternalDataTable(headers, rows)
        val mapping = mapOf("name" to "Full Name", "email" to "Email Address")

        val result = DataProcessor.parseStudentsFromTable(context, table, mapping)
        
        assertEquals(1, result.items.size)
        assertEquals(2, result.errors.size) // Only Name, Only Email. Empty row is skipped if all blank.
    }

    @Test
    fun `DataProcessor generateCsvString`() {
        val course = Course(id = 1, name = "C1")
        val session = Session(id = 10, name = "S1", courseId = 1, date = 0L)
        val student = Student(email = "s@t.com", name = "Stud", rfid = "R1")
        val record = AttendanceRecord(id = 100, studentEmail = "s@t.com", sessionName = "S1", timestamp = 0L)
        
        whenever(context.getString(R.string.csv_header_student_identity)).thenReturn("Name,Email,RFID")
        whenever(context.getString(R.string.label_not_applicable)).thenReturn("N/A")

        val csv = DataProcessor.generateCsvString(context, course, listOf(session), listOf(record), listOf(student))
        
        assertTrue(csv.contains("Stud,s@t.com,R1,1"))
    }

    @Test
    fun `TimeUtils various helpers`() {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        
        // tryParseDate
        assertNotNull(TimeUtils.tryParseDate("01/01/2024", formatter))
        assertNull(TimeUtils.tryParseDate("invalid", formatter))

        // formatMinutesToTime
        assertEquals("08:30", TimeUtils.formatMinutesToTime(510L))
        assertEquals("---", TimeUtils.formatMinutesToTime(null))

        // parseTimeToMinutes
        assertEquals(510L, TimeUtils.parseTimeToMinutes("08:30"))
        assertNull(TimeUtils.parseTimeToMinutes("8:30")) // requires %02d
        assertNull(TimeUtils.parseTimeToMinutes("25:00"))
        assertNull(TimeUtils.parseTimeToMinutes("08:61"))
        assertNull(TimeUtils.parseTimeToMinutes("abc"))

        // Millis conversions
        val now = System.currentTimeMillis()
        assertNotNull(TimeUtils.fromMillisToLocalDate(now))
        assertNotNull(TimeUtils.fromMillisToLocalDateTime(now))
        
        // Current week (Robolectric context would be better but simple unit test works for logic)
        assertTrue(TimeUtils.isDateInCurrentWeek(LocalDate.now()))
    }
}
