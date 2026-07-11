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
    fun `isDateInCurrentWeek logic check`() {
        // This is tricky to test without mocking now(), but we can at least check if today is in current week
        val today = LocalDate.now()
        assertTrue(TimeUtils.isDateInCurrentWeek(today))
        
        // Far in the past/future should be false
        assertFalse(TimeUtils.isDateInCurrentWeek(today.minusMonths(1)))
        assertFalse(TimeUtils.isDateInCurrentWeek(today.plusMonths(1)))
    }
}
