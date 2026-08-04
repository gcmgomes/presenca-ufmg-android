package com.example.presensor.tools

import android.content.res.TypedArray
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.data.entities.Course
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UiUtilsTest {

    private lateinit var activity: MainActivityForTest

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(MainActivityForTest::class.java).create().get()
    }

    @Test
    fun `updateLockIconUI sets correct icon and alpha`() {
        val imageView = ImageView(activity)
        
        UiUtils.updateLockIconUI(true, imageView)
        // We can't easily check the resource ID without shadows, but we can check alpha
        assertEquals(1.0f, imageView.alpha, 0.01f)
        
        UiUtils.updateLockIconUI(false, imageView)
        assertEquals(0.5f, imageView.alpha, 0.01f)
    }

    @Test
    fun `updateEditIconUI sets correct alpha`() {
        val imageView = ImageView(activity)
        
        UiUtils.updateEditIconUI(true, imageView)
        assertEquals(0.4f, imageView.alpha, 0.01f)
        
        UiUtils.updateEditIconUI(false, imageView)
        assertEquals(1.0f, imageView.alpha, 0.01f)
    }

    @Test
    fun `getColorForAccent returns consistent color for name`() {
        val mockTypedArray = mock<TypedArray>()
        whenever(mockTypedArray.length()).thenReturn(2)
        whenever(mockTypedArray.getColor(eq(0), any())).thenReturn(0xFF0000)
        whenever(mockTypedArray.getColor(eq(1), any())).thenReturn(0x00FF00)
        
        val color1 = UiUtils.getColorForAccent("Course A", mockTypedArray)
        
        // Reset and mock again for second call because it recycles the array
        val mockTypedArray2 = mock<TypedArray>()
        whenever(mockTypedArray2.length()).thenReturn(2)
        whenever(mockTypedArray2.getColor(eq(0), any())).thenReturn(0xFF0000)
        whenever(mockTypedArray2.getColor(eq(1), any())).thenReturn(0x00FF00)
        
        val color2 = UiUtils.getColorForAccent("Course A", mockTypedArray2)
        
        assertEquals(color1, color2)
        verify(mockTypedArray).recycle()
        verify(mockTypedArray2).recycle()
    }

    @Test
    fun `fillCourseDetailedCardStatistics populates views correctly`() {
        val card = activity.layoutInflater.inflate(R.layout.item_detailed_course_card, null)
        val course = Course(name = "Physics", year = 2024, semester = 1)
        val sessions = setOf(1L, 2L)
        val students = setOf("s1@test.com")
        val attendance = emptyList<com.example.presensor.data.entities.AttendanceRecord>()
        
        UiUtils.fillCourseDetailedCardStatistics(activity, card, course, sessions, students, attendance)
        
        assertEquals("Physics", card.findViewById<TextView>(R.id.txtDetailCourseName).text)
        assertEquals("2024 - 1st Semester", card.findViewById<TextView>(R.id.txtDetailCourseSemester).text)
        assertEquals("1", card.findViewById<TextView>(R.id.txtStatStudentCount).text)
        assertEquals("2", card.findViewById<TextView>(R.id.txtStatSessionCount).text)
        assertEquals("0%", card.findViewById<TextView>(R.id.txtStatAvgAttendance).text)
    }
}
