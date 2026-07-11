package com.example.presensor.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class DataProcessorLogicTest {

    @Test
    fun `parseCsvLine handles comma delimiter`() {
        val line = "a,b,c"
        val result = DataProcessor.parseCsvLine(line)
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `parseCsvLine handles semicolon delimiter`() {
        val line = "a;b;c"
        val result = DataProcessor.parseCsvLine(line)
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `parseCsvLine handles quoted values`() {
        val line = "\"a,b\",c"
        val result = DataProcessor.parseCsvLine(line)
        assertEquals(listOf("a,b", "c"), result)
    }

    @Test
    fun `parseCsvLine handles escaped quotes`() {
        val line = "\"a\"\"b\",c"
        val result = DataProcessor.parseCsvLine(line)
        assertEquals(listOf("a\"b", "c"), result)
    }

    @Test
    fun `parseCsvLine trims values`() {
        val line = " a , b , c "
        val result = DataProcessor.parseCsvLine(line)
        assertEquals(listOf("a", "b", "c"), result)
    }
}
