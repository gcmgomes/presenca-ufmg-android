package com.example.presensor.tools.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.presensor.data.InternalDataTable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DataProcessorProviderTest {

    private val provider = AndroidDataProcessorProvider()

    @Test
    fun `parseSessionsFromTable delegates to DataProcessor`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val table = InternalDataTable(headers = listOf("header"), rows = listOf(listOf("value")))
        val courseId = 1L
        val mapping = mapOf("a" to "b")

        val result = provider.parseSessionsFromTable(context, table, courseId, mapping)
        
        // We expect some errors because the table is invalid for session parsing
        assertEquals(0, result.items.size)
        assertEquals(true, result.errors.isNotEmpty())
    }

    @Test
    fun `parseStudentsFromTable delegates to DataProcessor`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val table = InternalDataTable(headers = listOf("header"), rows = listOf(listOf("value")))
        val mapping = mapOf("a" to "b")

        val result = provider.parseStudentsFromTable(context, table, mapping)

        assertEquals(0, result.items.size)
        assertEquals(true, result.errors.isNotEmpty())
    }
}
