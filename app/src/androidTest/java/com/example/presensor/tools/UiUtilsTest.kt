package com.example.presensor.tools

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.presensor.R
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiUtilsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun updateLockIconUI_Locked() {
        val imageView = ImageView(context)
        UiUtils.updateLockIconUI(true, imageView)
        assertEquals(1.0f, imageView.alpha, 0.01f)
    }

    @Test
    fun updateLockIconUI_Unlocked() {
        val imageView = ImageView(context)
        UiUtils.updateLockIconUI(false, imageView)
        assertEquals(0.5f, imageView.alpha, 0.01f)
    }

    @Test
    fun updateEditIconUI_Locked() {
        val imageView = ImageView(context)
        UiUtils.updateEditIconUI(true, imageView)
        assertEquals(0.4f, imageView.alpha, 0.01f)
    }

    @Test
    fun updateEditIconUI_Unlocked() {
        val imageView = ImageView(context)
        UiUtils.updateEditIconUI(false, imageView)
        assertEquals(1.0f, imageView.alpha, 0.01f)
    }

    @Test
    fun getColorForAccent_Consistency() {
        val typedArray = context.resources.obtainTypedArray(R.array.chalk_colors_list)
        val color1 = UiUtils.getColorForAccent("Course A", typedArray)
        
        val typedArray2 = context.resources.obtainTypedArray(R.array.chalk_colors_list)
        val color2 = UiUtils.getColorForAccent("Course A", typedArray2)
        
        assertEquals(color1, color2)

        val typedArray3 = context.resources.obtainTypedArray(R.array.chalk_colors_list)
        val color3 = UiUtils.getColorForAccent("Course B", typedArray3)
        assertNotEquals(color1, color3)
    }

    @Test
    fun fillCourseDetailedCardStatistics_UpdatesViews() {
        // We use ActivityScenario to get a valid AppCompatActivity
        ActivityScenario.launch(com.example.presensor.MainActivity::class.java).onActivity { activity ->
            val card = LayoutInflater.from(activity).inflate(R.layout.item_detailed_course_card, null)
            val course = Course(id = 1L, name = "Test Course", year = 2024, semester = 1)
            val sessionIds = setOf(10L, 11L)
            val studentEmails = setOf("s1@test.com", "s2@test.com")
            val records = listOf(
                AttendanceRecord(100L, "S1", null, "s1@test.com", "S1", 10L),
                AttendanceRecord(200L, "S1", null, "s1@test.com", "S2", 11L)
            )

            UiUtils.fillCourseDetailedCardStatistics(
                activity,
                card,
                course,
                sessionIds,
                studentEmails,
                records
            )

            assertEquals("Test Course", card.findViewById<TextView>(R.id.txtDetailCourseName).text)
            assertEquals("2", card.findViewById<TextView>(R.id.txtStatStudentCount).text)
            assertEquals("2", card.findViewById<TextView>(R.id.txtStatSessionCount).text)
            // 2 actual logs / (2 students * 2 sessions) = 50%
            assertEquals("50%", card.findViewById<TextView>(R.id.txtStatAvgAttendance).text)
        }
    }

    @Test
    fun fillCourseDetailedCardStatistics_ZeroStats() {
        ActivityScenario.launch(com.example.presensor.MainActivity::class.java).onActivity { activity ->
            val card = LayoutInflater.from(activity).inflate(R.layout.item_detailed_course_card, null)
            val course = Course(id = 1L, name = "C", year = 2024, semester = 1)
            
            UiUtils.fillCourseDetailedCardStatistics(
                activity, card, course, emptySet(), emptySet(), emptyList()
            )

            assertEquals("0", card.findViewById<TextView>(R.id.txtStatStudentCount).text)
            assertEquals("0", card.findViewById<TextView>(R.id.txtStatSessionCount).text)
            assertEquals("0%", card.findViewById<TextView>(R.id.txtStatAvgAttendance).text)
        }
    }

    @Test
    fun fillCourseDetailedCardStatistics_2ndSemester() {
        ActivityScenario.launch(com.example.presensor.MainActivity::class.java).onActivity { activity ->
            val card = LayoutInflater.from(activity).inflate(R.layout.item_detailed_course_card, null)
            val course = Course(id = 1L, name = "C", year = 2024, semester = 2)
            
            UiUtils.fillCourseDetailedCardStatistics(
                activity, card, course, emptySet(), emptySet(), emptyList()
            )

            val semesterText = card.findViewById<TextView>(R.id.txtDetailCourseSemester).text.toString()
            // Check if it contains 2nd or equivalent (depends on localization, but we check if it's not the same as 1st)
            val course1 = Course(id = 1L, name = "C", year = 2024, semester = 1)
            val card1 = LayoutInflater.from(activity).inflate(R.layout.item_detailed_course_card, null)
            UiUtils.fillCourseDetailedCardStatistics(activity, card1, course1, emptySet(), emptySet(), emptyList())
            val semesterText1 = card1.findViewById<TextView>(R.id.txtDetailCourseSemester).text.toString()
            
            assertNotEquals(semesterText, semesterText1)
        }
    }
}
