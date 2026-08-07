package com.example.presensor.controllers

import android.net.Uri
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.controllers.providers.CourseInteractionProvider
import com.example.presensor.controllers.items.ActionItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.io.ByteArrayOutputStream
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
class CourseControllerTest : BaseControllerTest() {

    private lateinit var controller: CourseController
    private val mockInteractionProvider: CourseInteractionProvider = mock()
    private val onSessionSelected: (Session) -> Unit = mock()
    private val onToggleLockRequested: (Session) -> Unit = mock()
    private val onEditSessionRequested: (Session) -> Unit = mock()
    private val onEditCourseRequested: (Course) -> Unit = mock()
    private val onOpenStatistics: () -> Unit = mock()

    private val importLauncherCaptor = argumentCaptor<(Uri) -> Unit>()
    private val exportLauncherCaptor = argumentCaptor<(Uri) -> Unit>()

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
            onOpenStatistics = onOpenStatistics,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )

        verify(mockInteractionProvider).registerImportSessionLauncher(importLauncherCaptor.capture())
        verify(mockInteractionProvider).registerExportLauncher(exportLauncherCaptor.capture())
    }

    @Test
    fun `initialization sets up accordion and quick actions`() {
        val accordionCallback = argumentCaptor<(Boolean) -> Unit>()
        verify(mockInteractionProvider).setupCourseUtilsAccordion(accordionCallback.capture())

        // Test accordion toggle
        accordionCallback.firstValue.invoke(true)
        verify(mockInteractionProvider).setUtilsExpandIconRotation(180f)
        verify(mockInteractionProvider).setUtilsContentVisibility(false)

        accordionCallback.firstValue.invoke(false)
        verify(mockInteractionProvider).setUtilsExpandIconRotation(0f)
        verify(mockInteractionProvider).setUtilsContentVisibility(true)

        // Test quick actions registration
        verify(mockInteractionProvider).setupQuickActions(any(), any())
    }

    @Test
    fun `quick actions trigger expected callbacks`() = runTest(mainDispatcherRule.testDispatcher) {
        val actionsCaptor = argumentCaptor<List<ActionItem>>()
        verify(mockInteractionProvider).setupQuickActions(actionsCaptor.capture(), any())
        val actions = actionsCaptor.firstValue

        val course = Course(id = 1L, name = "Test Course", year = 2024, semester = 1)
        controller.prepare(course)

        // PAGE 1: Statistics
        actions[0].onClick()
        verify(onOpenStatistics).invoke()

        // PAGE 1: Postpone
        actions[1].onClick()
        verify(mockInteractionProvider).showMassDateChangeDialog(1L)

        // PAGE 2: Export
        actions[2].onClick()
        verify(mockInteractionProvider).launchExportPicker(any())

        // PAGE 2: Import
        actions[3].onClick()
        verify(mockInteractionProvider).launchImportPicker()

        // PAGE 3: Cloud Export
        actions[4].onClick()
        verify(mockInteractionProvider).triggerCloudAttendanceExport()

        // PAGE 3: Cloud Import
        actions[5].onClick()
        verify(mockInteractionProvider).triggerCloudScheduleImport(any())
    }

    @Test
    fun `prepare sets selected course and loads sessions`() =
        runTest(mainDispatcherRule.testDispatcher) {
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

    @Test
    fun `showCreateSessionDialog calls interaction provider`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val courseId = db.insertCourse(Course(name = "Test Course", year = 2024, semester = 1))
            val course = db.getAllCourses().first()
            controller.prepare(course)

            controller.showCreateSessionDialog()

            val callbackCaptor = argumentCaptor<(Long, String, Long, Long?, Long?) -> Unit>()
            verify(mockInteractionProvider).showCreateSessionDialog(
                eq(courseId),
                callbackCaptor.capture()
            )

            // Simulate confirmation
            callbackCaptor.firstValue.invoke(courseId, "New Session", 1000L, null, null)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()
            verify(mockInteractionProvider).refreshSessionsList(any(), any(), any(), any(), any())
        }

    @Test
    fun `refreshCourseUI updates sessions and header`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val courseId = db.insertCourse(Course(name = "Test Course", year = 2024, semester = 1))
            val course = db.getAllCourses().first()
            controller.prepare(course)

            val session = Session(courseId = courseId, name = "S1", date = 1000L)
            db.insertSessions(listOf(session))
            val sessions = db.getSessionsByCourse(courseId)
            
            val student = Student(email = "s1@test.com", name = "Student Name", rfid = "RFID")
            db.insertStudents(listOf(student))
            db.recordAttendance(student, sessions.first(), 1001L)
            
            val attendance = db.getAllAttendanceForCourse(courseId)

            controller.refreshCourseUI()
            advanceUntilIdle()

            verify(mockInteractionProvider).refreshSessionsList(
                eq(sessions),
                any(),
                any(),
                any(),
                any()
            )
            verify(mockInteractionProvider).updateCourseHeader(
                eq(course),
                eq(setOf(sessions.first().id)),
                eq(setOf("s1@test.com")),
                eq(attendance),
                any()
            )
        }

    @Test
    fun `showMassDateChangeDialog calls interaction provider`() {
        val course = Course(id = 1L, name = "Test Course", year = 2024, semester = 1)
        controller.prepare(course)
        controller.showMassDateChangeDialog()
        verify(mockInteractionProvider).showMassDateChangeDialog(1L)
    }

    @Test
    fun `showCreateCourseDialog calls interaction provider`() {
        val onCreated: () -> Unit = mock()
        controller.showCreateCourseDialog(onCreated)
        verify(mockInteractionProvider).showCreateCourseDialog(onCreated)
    }

    @Test
    fun `showEditCourseDialog updates state and calls interaction provider`() {
        val course = Course(id = 1L, name = "Test Course", year = 2024, semester = 1)
        val updatedCourse = course.copy(name = "Updated")
        val onEdited: () -> Unit = mock()

        controller.showEditCourseDialog(course, onEdited)

        val updateCallbackCaptor = argumentCaptor<(Course) -> Unit>()
        verify(mockInteractionProvider).showEditCourseDialog(
            eq(course),
            updateCallbackCaptor.capture(),
            eq(onEdited)
        )

        updateCallbackCaptor.firstValue.invoke(updatedCourse)
        assert(controller.getSelectedCourse() == updatedCourse)
    }

    @Test
    fun `importSessionsFromCsv trigger via launcher`() {
        val course = Course(id = 1L, name = "Test Course", year = 2024, semester = 1)
        controller.prepare(course)

        val uri: Uri = mock()
        importLauncherCaptor.firstValue.invoke(uri)

        verify(mockInteractionProvider).importSessionsFromCsv(eq(uri), eq(1L), any())
    }

    @Test
    fun `performExport success`() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "Test Course", year = 2024, semester = 1))
        val course = db.getAllCourses().first()
        controller.prepare(course)

        db.insertSessions(listOf(Session(courseId = courseId, name = "S1", date = 1000L)))

        val uri: Uri = mock()
        val outputStream = ByteArrayOutputStream()
        whenever(mockInteractionProvider.openOutputStream(uri)).thenReturn(outputStream)

        exportLauncherCaptor.firstValue.invoke(uri)
        advanceUntilIdle()

        verify(mockInteractionProvider).showToast(com.example.presensor.R.string.toast_export_success)
        assert(outputStream.size() > 0)
    }

    @Test
    fun `performExport failure`() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "Test Course", year = 2024, semester = 1))
        val course = db.getAllCourses().first()
        controller.prepare(course)

        val uri: Uri = mock()
        whenever(mockInteractionProvider.openOutputStream(uri)).thenThrow(RuntimeException("IO Error"))

        exportLauncherCaptor.firstValue.invoke(uri)
        advanceUntilIdle()

        verify(mockInteractionProvider).showToast(com.example.presensor.R.string.toast_export_failed)
    }
}
