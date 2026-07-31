package com.example.presensor.controllers

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.controllers.adapters.StudentStatsAdapter
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.AttendanceRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.kotlin.*
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
class DetailedCourseControllerTest : BaseControllerTest() {

    private lateinit var detailedCourseController: DetailedCourseController
    private val courseController: CourseController = mock()
    private val getColorFromAttr: (Int) -> Int = { 0 }

    @Before
    override fun setup() {
        super.setup()
        
        detailedCourseController = DetailedCourseController(
            activity = activity,
            lifecycleOwner = activity,
            db = db,
            courseController = courseController,
            getColorFromAttr = getColorFromAttr,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @Test
    fun inflateAndSetupStatsView_populatesViewAndLoadsData() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "Kotlin 101")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        // Prepare some data in DB
        val student = Student(email = "test@example.com", name = "Test Student")
        db.insertStudents(listOf(student))
        val session = Session(id = 10, courseId = 1, name = "Session 1", date = 1000L)
        db.insertSessions(listOf(session))
        db.recordAttendance(student, session, 1000L)

        val container = LinearLayout(activity)
        val statsView = detailedCourseController.inflateAndSetupStatsView(container)
        
        assertEquals(View.VISIBLE, statsView.visibility)
        
        // Wait for async load
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
        assertNotNull(rv.adapter)
        assertTrue(rv.adapter is StudentStatsAdapter)
        assertEquals(1, rv.adapter?.itemCount)
    }

    @Test
    fun refreshDetailedCourseUI_filtersStudents() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "Filter Test")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        val s1 = Student(email = "a@test.com", name = "Alice")
        val s2 = Student(email = "b@test.com", name = "Bob")
        db.insertStudents(listOf(s1, s2))
        
        val session = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        db.insertSessions(listOf(session))
        
        db.recordAttendance(s1, session, 1000L)
        db.recordAttendance(s2, session, 1000L)

        val container = LinearLayout(activity)
        val statsView = detailedCourseController.inflateAndSetupStatsView(container)
        
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
        val adapter = rv.adapter as StudentStatsAdapter
        
        assertEquals(2, adapter.itemCount)
        
        // Apply filter
        detailedCourseController.refreshDetailedCourseUI("Alice")
        assertEquals(1, adapter.itemCount)
        
        detailedCourseController.refreshDetailedCourseUI("NonExistent")
        assertEquals(0, adapter.itemCount)
    }

    @Test
    fun searchView_triggersFiltering() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "Search Test")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        val s1 = Student(email = "a@test.com", name = "Alice")
        db.insertStudents(listOf(s1))
        val session = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        db.insertSessions(listOf(session))
        db.recordAttendance(s1, session, 1000L)

        val container = LinearLayout(activity)
        val statsView = detailedCourseController.inflateAndSetupStatsView(container)
        
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        val searchView = statsView.findViewById<SearchView>(R.id.searchStudentsAttendance)
        val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
        val adapter = rv.adapter as StudentStatsAdapter
        
        assertEquals(1, adapter.itemCount)
        
        // Simulate search input
        searchView.setQuery("Bob", true)
        
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        assertEquals(0, adapter.itemCount)
    }

    @Test
    fun inflateAndSetupStatsView_noCourseSelected_throwsException() {
        whenever(courseController.getSelectedCourse()).thenReturn(null)
        assertThrows(IllegalStateException::class.java) {
            detailedCourseController.inflateAndSetupStatsView(LinearLayout(activity))
        }
    }

    @Test
    fun fetchDataAndRefresh_nullCourse_doesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        whenever(courseController.getSelectedCourse()).thenReturn(null)
        detailedCourseController.fetchDataAndRefresh()
        advanceUntilIdle()
        // No crash means success
    }

    @Test
    fun refreshDetailedCourseUI_nullStatsView_doesNothing() {
        detailedCourseController.clear()
        detailedCourseController.refreshDetailedCourseUI()
        // No crash means success
    }

    @Test
    fun refreshDetailedCourseUI_nullCourse_doesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "C")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        detailedCourseController.inflateAndSetupStatsView(LinearLayout(activity))
        advanceUntilIdle()
        
        whenever(courseController.getSelectedCourse()).thenReturn(null)
        detailedCourseController.refreshDetailedCourseUI()
        // No crash means success
    }

    @Test
    fun clickEditCourse_triggersRefresh() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "C")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        val statsView = detailedCourseController.inflateAndSetupStatsView(LinearLayout(activity))
        advanceUntilIdle()
        
        val btnEdit = statsView.findViewById<ImageView>(R.id.btnEditCourse)
        
        // Simulate click
        btnEdit.performClick()

        val onCourseEditedCaptor = argumentCaptor<() -> Unit>()
        verify(courseController).showEditCourseDialog(eq(course), onCourseEditedCaptor.capture())
        
        // Simulate edit completion
        onCourseEditedCaptor.firstValue.invoke()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        // Verify another data load was triggered (at least 2: initial + edit refresh)
        verify(courseController, atLeast(2)).getSelectedCourse()
    }

    @Test
    fun fetchDataAndRefresh_viewNulledDuringLoad_exitsGracefully() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "C")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        // Trigger load but don't finish yet
        detailedCourseController.inflateAndSetupStatsView(LinearLayout(activity))
        
        // Null out view immediately
        detailedCourseController.clear()
        
        advanceUntilIdle()
        // Should not crash when trying to access statsView in withContext(Main)
    }

    @Test
    fun refreshDetailedCourseUI_adapterNull_doesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "C")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        val statsView = detailedCourseController.inflateAndSetupStatsView(LinearLayout(activity))
        advanceUntilIdle()
        
        // Force null adapter
        val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
        rv.adapter = null
        
        detailedCourseController.refreshDetailedCourseUI()
        // No crash means success
    }

    @Test
    fun clear_nullsOutView() {
        val container = LinearLayout(activity)
        whenever(courseController.getSelectedCourse()).thenReturn(Course(name = "C"))
        
        detailedCourseController.inflateAndSetupStatsView(container)
        detailedCourseController.clear()
        
        // Since currentStatsView is private, we verify behavior: refresh should do nothing
        // Or if we want to be sure, we'd need to expose it or check for side effects.
        // For now, calling refresh shouldn't crash.
        detailedCourseController.refreshDetailedCourseUI()
    }
}
