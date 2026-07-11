package com.example.presensor.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.presensor.data.InternalDataTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

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
    fun generateCsvString_Success() {
        val course = com.example.presensor.data.entities.Course(name = "C1")
        val s1 = com.example.presensor.data.entities.Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        val students = listOf(com.example.presensor.data.entities.Student("e1@test.com", "John"))
        val attendance = listOf(com.example.presensor.data.entities.AttendanceRecord(100L, "John", null, "e1@test.com", "S1", 1))
        
        val result = DataProcessor.generateCsvString(context, course, listOf(s1), attendance, students)
        
        assertTrue(result.contains("John"))
        assertTrue(result.contains("e1@test.com"))
        assertTrue(result.contains(",1")) // present in S1
    }
}
