package com.example.presensor

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainUiBinderTest {

    private lateinit var container: LinearLayout

    @Before
    fun setUp() {
        container = LinearLayout(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun addSectionHeader_addsTextViewWithUppercaseTitle() {
        val title = "test section"
        MainUiBinder.addSectionHeader(container, title)

        assertEquals(1, container.childCount)
        val child = container.getChildAt(0)
        assertTrue("Child should be a TextView", child is TextView)
        val textView = child as TextView
        assertEquals("Title should be uppercase", title.uppercase(), textView.text.toString())
    }

    @Test
    fun addYearDivider_addsLayoutWithStructure() {
        val year = "2024"
        MainUiBinder.addYearDivider(container, year)

        assertEquals(1, container.childCount)
        val child = container.getChildAt(0)
        assertTrue("Child should be a LinearLayout", child is LinearLayout)
        val dividerLayout = child as LinearLayout

        // Verify structure: line, year text, line
        assertEquals("Divider layout should have 3 children", 3, dividerLayout.childCount)

        val firstLine = dividerLayout.getChildAt(0)
        assertTrue("First child should be a View (line)", firstLine !is TextView && firstLine is View)

        val yearText = dividerLayout.getChildAt(1)
        assertTrue("Second child should be a TextView", yearText is TextView)
        assertEquals("Year text should match input with padding", "  $year  ", (yearText as TextView).text.toString())

        val secondLine = dividerLayout.getChildAt(2)
        assertTrue("Third child should be a View (line)", secondLine !is TextView && secondLine is View)
    }
}
