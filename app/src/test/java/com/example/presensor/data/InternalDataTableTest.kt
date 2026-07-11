package com.example.presensor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InternalDataTableTest {

    @Test
    fun `getCellValue by name returns correct value case insensitive`() {
        val headers = listOf("Name", "Email", "Date")
        val rows = listOf(
            listOf("John Doe", "john@example.com", "2023-01-01"),
            listOf("Jane Smith", "jane@example.com", "2023-01-02")
        )
        val table = InternalDataTable(headers, rows)

        assertEquals("John Doe", table.getCellValue(0, "name"))
        assertEquals("jane@example.com", table.getCellValue(1, "EMAIL"))
        assertEquals("2023-01-02", table.getCellValue(1, "Date"))
    }

    @Test
    fun `getCellValue by name returns empty string for non-existent column`() {
        val table = InternalDataTable(listOf("A"), listOf(listOf("1")))
        assertEquals("", table.getCellValue(0, "B"))
    }

    @Test
    fun `getCellValue returns empty string for out of bounds index`() {
        val table = InternalDataTable(listOf("A"), listOf(listOf("1")))
        assertEquals("", table.getCellValue(5, "A"))
        assertEquals("", table.getCellValue(0, 5))
        assertEquals("", table.getCellValue(-1, 0))
    }

    @Test
    fun `getCellValue by index returns correct value`() {
        val table = InternalDataTable(listOf("A", "B"), listOf(listOf("1", "2")))
        assertEquals("1", table.getCellValue(0, 0))
        assertEquals("2", table.getCellValue(0, 1))
    }

    @Test
    fun `rowCount returns correct size`() {
        val table = InternalDataTable(listOf("A"), listOf(listOf("1"), listOf("2")))
        assertEquals(2, table.rowCount)
    }

    @Test
    fun `toFullGrid includes headers and rows`() {
        val headers = listOf("H1", "H2")
        val rows = listOf(listOf("R1C1", "R1C2"), listOf("R2C1", "R2C2"))
        val table = InternalDataTable(headers, rows)
        val fullGrid = table.toFullGrid()

        assertEquals(3, fullGrid.size)
        assertEquals(headers, fullGrid[0])
        assertEquals(rows[0], fullGrid[1])
        assertEquals(rows[1], fullGrid[2])
    }

    @Test
    fun `toFullGrid with empty rows returns only headers`() {
        val headers = listOf("H1", "H2")
        val table = InternalDataTable(headers, emptyList())
        val fullGrid = table.toFullGrid()
        assertEquals(1, fullGrid.size)
        assertEquals(headers, fullGrid[0])
    }
}
