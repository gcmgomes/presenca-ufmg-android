package com.example.presensor.controllers

import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Session
import com.example.presensor.controllers.providers.DetailedCourseInteractionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class DetailedCourseControllerTest : BaseControllerTest() {

    private lateinit var detailedCourseController: DetailedCourseController
    private val courseController: CourseController = mock()
    private val interactionProvider: DetailedCourseInteractionProvider = mock()
    private val getColorFromAttr: (Int) -> Int = { 0 }

    @Before
    override fun setup() {
        super.setup()
        
        detailedCourseController = DetailedCourseController(
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            db = db,
            courseController = courseController,
            interactionProvider = interactionProvider,
            getColorFromAttr = getColorFromAttr,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @Test
    fun openDetailedCourseView_populatesViewAndLoadsData() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "Kotlin 101")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        detailedCourseController.openDetailedCourseView()
        
        verify(interactionProvider).openDetailedCourseView(any(), any())
        
        // Wait for async load
        advanceUntilIdle()
        
        verify(interactionProvider, atLeastOnce()).updateStudentStatsList(any(), any(), any(), any())
    }

    @Test
    fun openDetailedCourseView_noCourseSelected_throwsException() {
        whenever(courseController.getSelectedCourse()).thenReturn(null)
        assertThrows(IllegalStateException::class.java) {
            detailedCourseController.openDetailedCourseView()
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
    fun refreshDetailedCourseUI_notActive_doesNothing() {
        detailedCourseController.clear()
        detailedCourseController.refreshDetailedCourseUI()
        verifyNoInteractions(interactionProvider)
    }

    @Test
    fun clickEditCourse_triggersRefresh() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "C")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        detailedCourseController.openDetailedCourseView()
        advanceUntilIdle()
        
        val onEditCaptor = argumentCaptor<() -> Unit>()
        verify(interactionProvider).openDetailedCourseView(onEditCaptor.capture(), any())
        
        // Simulate click
        onEditCaptor.firstValue.invoke()

        val onCourseEditedCaptor = argumentCaptor<() -> Unit>()
        verify(courseController).showEditCourseDialog(eq(course), onCourseEditedCaptor.capture())
        
        // Simulate edit completion
        onCourseEditedCaptor.firstValue.invoke()
        advanceUntilIdle()
        
        // Verify another data load was triggered
        verify(courseController, atLeast(2)).getSelectedCourse()
    }

    @Test
    fun fetchDataAndRefresh_viewNulledDuringLoad_exitsGracefully() = runTest(mainDispatcherRule.testDispatcher) {
        val course = Course(id = 1, name = "C")
        db.insertCourse(course)
        whenever(courseController.getSelectedCourse()).thenReturn(course)
        
        // Trigger load
        detailedCourseController.openDetailedCourseView()
        
        // Null out view immediately
        detailedCourseController.clear()
        
        // Clear initial interactions from openDetailedCourseView if any (due to Unconfined dispatcher)
        clearInvocations(interactionProvider)
        
        advanceUntilIdle()
        // Should not call updateStudentStatsList if cleared
        verify(interactionProvider, never()).updateStudentStatsList(any(), any(), any(), any())
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
        
        detailedCourseController.openDetailedCourseView()
        advanceUntilIdle()
        
        // Apply filter
        detailedCourseController.refreshDetailedCourseUI("Alice")
        
        val captor = argumentCaptor<List<Student>>()
        verify(interactionProvider, atLeastOnce()).updateStudentStatsList(captor.capture(), any(), any(), any())
        
        val filteredList = captor.lastValue
        assertEquals(1, filteredList.size)
        assertEquals("Alice", filteredList[0].name)
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
        
        detailedCourseController.openDetailedCourseView()
        advanceUntilIdle()
        
        val onSearchCaptor = argumentCaptor<(String) -> Unit>()
        verify(interactionProvider).openDetailedCourseView(any(), onSearchCaptor.capture())
        
        // Simulate search input
        onSearchCaptor.firstValue.invoke("Bob")
        advanceUntilIdle()
        
        verify(interactionProvider, atLeast(2)).updateStudentStatsList(any(), any(), any(), any())
    }
}
