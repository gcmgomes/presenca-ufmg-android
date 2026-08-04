package com.example.presensor.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TimeUtilsTest {

    @Test
    fun `tryParseDate returns LocalDate for valid date`() {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val result = TimeUtils.tryParseDate("25/12/2023", formatter)
        assertEquals(LocalDate.of(2023, 12, 25), result)
    }

    @Test
    fun `tryParseDate returns null for invalid date`() {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val result = TimeUtils.tryParseDate("32/12/2023", formatter)
        assertNull(result)
    }

    @Test
    fun `fromMillisToLocalDate converts correctly`() {
        // 1703462400000L is 2023-12-25 00:00:00 UTC
        val result = TimeUtils.fromMillisToLocalDate(1703462400000L)
        assertEquals(LocalDate.of(2023, 12, 25), result)
    }

    @Test
    fun `fromMillisToLocalDateTime converts correctly`() {
        val now = System.currentTimeMillis()
        val result = TimeUtils.fromMillisToLocalDateTime(now)
        val expected = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(now),
            java.time.ZoneId.systemDefault()
        )
        assertEquals(expected.year, result.year)
        assertEquals(expected.month, result.month)
        assertEquals(expected.dayOfMonth, result.dayOfMonth)
        assertEquals(expected.hour, result.hour)
    }

    @Test
    fun `isDateInCurrentWeek logic check`() {
        // This is tricky to test without mocking now(), but we can at least check if today is in current week
        val today = LocalDate.now()
        assertTrue(TimeUtils.isDateInCurrentWeek(today))
        
        // Far in the past/future should be false
        assertFalse(TimeUtils.isDateInCurrentWeek(today.minusMonths(1)))
        assertFalse(TimeUtils.isDateInCurrentWeek(today.plusMonths(1)))
    }

    @Test
    fun `formatMinutesToTime converts correctly`() {
        assertEquals("08:00", TimeUtils.formatMinutesToTime(480L))
        assertEquals("14:30", TimeUtils.formatMinutesToTime(870L))
        assertEquals("00:05", TimeUtils.formatMinutesToTime(5L))
        assertEquals("---", TimeUtils.formatMinutesToTime(null))
    }

    @Test
    fun `parseTimeToMinutes parses correctly`() {
        assertEquals(480L, TimeUtils.parseTimeToMinutes("08:00"))
        assertEquals(870L, TimeUtils.parseTimeToMinutes("14:30"))
        assertEquals(5L, TimeUtils.parseTimeToMinutes("00:05"))
        assertEquals(0L, TimeUtils.parseTimeToMinutes("00:00"))
        assertEquals(1439L, TimeUtils.parseTimeToMinutes("23:59"))

        // Invalid formats
        assertNull(TimeUtils.parseTimeToMinutes("invalid"))
        assertNull(TimeUtils.parseTimeToMinutes("8:0"))
        assertNull(TimeUtils.parseTimeToMinutes("08:0"))
        assertEquals(480L, TimeUtils.parseTimeToMinutes("8:00"))
        
        // Out of range
        assertNull(TimeUtils.parseTimeToMinutes("24:00"))
        assertNull(TimeUtils.parseTimeToMinutes("00:60"))
        assertNull(TimeUtils.parseTimeToMinutes("-01:00"))
    }
}
