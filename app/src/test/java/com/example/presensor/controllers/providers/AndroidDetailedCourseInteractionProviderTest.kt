package com.example.presensor.controllers.providers

import android.view.View
import android.widget.LinearLayout
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.AttendanceRecord
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidDetailedCourseInteractionProviderTest {

    private lateinit var controller: ActivityController<MainActivityForTest>
    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidDetailedCourseInteractionProvider

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(MainActivityForTest::class.java)
        testActivity = controller.get()
        provider = AndroidDetailedCourseInteractionProvider(testActivity)
        controller.setup()
        testActivity.setContentView(R.layout.activity_main)
    }

    @Test
    fun `openDetailedCourseView inflates view in container and sets search listener`() {
        var queryChanged = ""
        provider.openDetailedCourseView({}, { queryChanged = it })
        ShadowLooper.idleMainLooper()
        
        val container = testActivity.findViewById<LinearLayout>(R.id.layoutCourseStatisticsView)
        assertNotNull(container)
        assertTrue(container!!.childCount > 0)

        val searchView = container.findViewById<androidx.appcompat.widget.SearchView>(R.id.searchStudentsAttendance)
        searchView?.setQuery("Alice", false)
        assertEquals("Alice", queryChanged)
        
        searchView?.setQuery("Submit", true)
    }

    @Test
    fun `updateDetailedCourseHeader handles call`() {
        val course = Course(id = 1L, name = "Test Course")
        val sessionIds = setOf(1L, 2L)
        val studentEmails = setOf("e1", "e2")
        val attendance = listOf(AttendanceRecord(0L, "N", "R", "e1", "S", 1L))
        
        provider.updateDetailedCourseHeader(course, sessionIds, studentEmails, attendance)
        ShadowLooper.idleMainLooper()
        
        // Early return branch
        testActivity.findViewById<LinearLayout>(R.id.layoutCourseStatisticsView)?.id = View.NO_ID
        provider.updateDetailedCourseHeader(course, sessionIds, studentEmails, attendance)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `updateStudentStatsList populates recycler and updates existing adapter`() {
        provider.openDetailedCourseView({}, {})
        ShadowLooper.idleMainLooper()
        
        val rv = testActivity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvStudentStats)
        
        provider.updateStudentStatsList(listOf(Student(email="e", name="n")), emptyList(), emptyList()) { 0 }
        ShadowLooper.idleMainLooper()
        val adapter1 = rv?.adapter
        assertNotNull(adapter1)
        
        provider.updateStudentStatsList(listOf(Student(email="e2", name="n2")), emptyList(), emptyList()) { 0 }
        ShadowLooper.idleMainLooper()
        assertSame(adapter1, rv?.adapter)
        
        // Early return branch
        testActivity.findViewById<LinearLayout>(R.id.layoutCourseStatisticsView)?.id = View.NO_ID
        provider.updateStudentStatsList(emptyList(), emptyList(), emptyList()) { 0 }
        ShadowLooper.idleMainLooper()
    }
}
