package com.example.presensor.adapters

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.example.presensor.R
import com.example.presensor.data.entities.Session
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ImportPreviewAdapterTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_Presensor)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun getItemCount_returnsCorrectSize() {
        val sessions = listOf(
            Session(courseId = 1, name = "S1", date = 1000L),
            Session(courseId = 1, name = "S2", date = 2000L)
        )
        val adapter = ImportPreviewAdapter(sessions)
        assertEquals(2, adapter.itemCount)
    }

    @Test
    fun onBindViewHolder_bindsDataCorrectly() {
        val sessions = listOf(
            Session(courseId = 1, name = "Test Session", date = 1625097600000L) // 01/07/2021
        )
        val adapter = ImportPreviewAdapter(sessions)
        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)

        adapter.onBindViewHolder(viewHolder, 0)

        assertEquals("Test Session", viewHolder.nameText.text.toString())
        assertEquals("01/07/2021", viewHolder.dateText.text.toString())
    }
}
