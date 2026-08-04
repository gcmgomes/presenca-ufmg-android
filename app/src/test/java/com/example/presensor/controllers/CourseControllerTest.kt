package com.example.presensor.controllers

import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.controllers.providers.CourseInteractionProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class CourseControllerTest : BaseControllerTest() {

    private lateinit var controller: CourseController
    private val mockInteractionProvider: CourseInteractionProvider = mock()
    private val onSessionSelected: (Session) -> Unit = mock()
    private val onToggleLockRequested: (Session) -> Unit = mock()
    private val onEditSessionRequested: (Session) -> Unit = mock()
    private val onEditCourseRequested: (Course) -> Unit = mock()
    private val onOpenStatistics: () -> Unit = mock()

    @Before
    override fun setup() {
        super.setup()

        whenever(mockInteractionProvider.getString(any<Int>())).thenReturn("Mock String")
        whenever(mockInteractionProvider.getString(any<Int>(), any())).thenReturn("Mock String")
        whenever(mockInteractionProvider.getContext()).thenReturn(activity)

        controller = CourseController(
            lifecycleOwner = activity,
            selectedCourse = null,
            db = db,
            interactionProvider = mockInteractionProvider,
            onSessionSelected = onSessionSelected,
            onToggleLockRequested = onToggleLockRequested,
            onEditSessionRequested = onEditSessionRequested,
            onEditCourseRequested = onEditCourseRequested,
            onOpenStatistics = onOpenStatistics
        )
    }

    @Test
    fun `prepare sets selected course and loads sessions`() = runTest {
        val course = Course(id = 1L, name = "Test Course", year = 2024, semester = 1)
        controller.prepare(course)?.join()
        advanceUntilIdle()

        assert(controller.getSelectedCourse() == course)
    }

    @Test
    fun `clear nulls out selected course`() {
        val course = Course(id = 1L, name = "Test Course", year = 2024, semester = 1)
        controller.prepare(course)
        controller.clear()
        assert(controller.getSelectedCourse() == null)
    }
}
