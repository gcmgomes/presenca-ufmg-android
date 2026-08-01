package com.example.presensor.controllers

import com.example.presensor.R
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.controllers.providers.SessionInteractionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerUnitTest : BaseControllerTest() {

    private lateinit var sessionController: SessionController
    private val interactionProvider: SessionInteractionProvider = mock()
    private val onSessionStateMutated: () -> Unit = mock()
    private val onPulldown: () -> Unit = mock()

    @Before
    override fun setup() {
        super.setup()

        whenever(interactionProvider.getContext()).thenReturn(activity)
        whenever(interactionProvider.getString(any())).thenReturn("Mock String")
        whenever(interactionProvider.getString(any(), any())).thenReturn("Mock String")

        sessionController = SessionController(
            interactionProvider = interactionProvider,
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            db = db,
            getColorForAccent = { 0 },
            onSessionStateMutated = onSessionStateMutated,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher,
            onPulldown = onPulldown,
            onSyncTimeout = {}
        )
    }

    @Test
    fun openSessionView_populatesUIAndLoadsAttendance() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test Session", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        
        sessionController.openSessionView(insertedSession)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        verify(interactionProvider).updateSessionCard(eq("Test Session"), eq(1000L), any())
        verify(interactionProvider).updateLockState(false)
        verify(interactionProvider).setupSessionListeners(any(), any())
        assert(sessionController.activeSession == insertedSession)
    }

    @Test
    fun handleLockToggleSequence_unlocked_locksDirectly() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test Session", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        
        sessionController.handleLockToggleSequence(insertedSession)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val updatedSession = db.getSessionById(insertedSession.id)
        assert(updatedSession?.isLocked == true)
        verify(interactionProvider).updateLockState(true)
        verify(interactionProvider).showToast(any<String>(), any())
    }

    @Test
    fun handleLockToggleSequence_locked_showsUnlockDialog() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test Session", date = 1000L, isLocked = true)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        
        sessionController.handleLockToggleSequence(insertedSession)
        advanceUntilIdle()
        
        verify(interactionProvider).showUnlockDialog(eq("Test Session"), any())
    }

    @Test
    fun handleLockToggleSequence_locked_correctPassword_unlocks() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "UnlockMe", date = 1000L, isLocked = true)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        
        val onUnlockedCaptor = argumentCaptor<() -> Unit>()
        sessionController.handleLockToggleSequence(insertedSession)
        verify(interactionProvider).showUnlockDialog(any(), onUnlockedCaptor.capture())
        
        onUnlockedCaptor.firstValue.invoke()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        assert(sessionController.activeSession?.isLocked == false)
        verify(interactionProvider).updateLockState(false)
    }

    @Test
    fun registerAttendance_lockedSession_showsToastAndEarlyExits() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Locked", date = 1000L, isLocked = true)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        
        sessionController.registerAttendance(mock(), 2000L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        verify(interactionProvider).showToast(eq(R.string.msg_session_locked), any())
        val records = db.getAttendanceRecordsForSession(insertedSession.id)
        assert(records.isEmpty())
    }

    @Test
    fun registerAttendance_unlockedSession_recordsAttendance_and_scrollsToBottom() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Unlocked", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        val student = Student(email = "s@test.com", name = "Student", rfid = "RFID")
        db.insertStudents(listOf(student))
        sessionController.openSessionView(insertedSession)
        
        sessionController.registerAttendance(student, 2000L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val records = db.getAttendanceRecordsForSession(insertedSession.id)
        assert(records.size == 1)
        assert(records[0].studentEmail == student.email)
        
        // Verify it was sorted and scrolled to bottom (index 0)
        verify(interactionProvider).submitAttendanceList(any(), eq(0))
    }

    @Test
    fun registerAttendance_appendsToBottom_and_scrolls() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "S1", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        
        val s1 = Student(email = "s1@test.com", name = "S1", rfid = "R1")
        val s2 = Student(email = "s2@test.com", name = "S2", rfid = "R2")
        db.insertStudents(listOf(s1, s2))
        
        sessionController.openSessionView(insertedSession)
        
        // Add first
        sessionController.registerAttendance(s1, 2000L)
        advanceUntilIdle()
        verify(interactionProvider).submitAttendanceList(any(), eq(0))
        
        // Add second with later time
        sessionController.registerAttendance(s2, 3000L)
        advanceUntilIdle()
        
        // The list should have 2 items, sorted by time, so index 1 is the new one
        val captor = argumentCaptor<List<AttendanceRecord>>()
        verify(interactionProvider, times(3)).submitAttendanceList(captor.capture(), anyOrNull())
        
        val lastList = captor.lastValue
        assert(lastList.size == 2)
        assert(lastList[0].timestamp == 2000L)
        assert(lastList[1].timestamp == 3000L)
        
        verify(interactionProvider).submitAttendanceList(any(), eq(1))
    }

    @Test
    fun showManualAttendanceDialog_callsProvider() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        
        sessionController.openSessionView(insertedSession)
        advanceUntilIdle()
        
        sessionController.showManualAttendanceDialog()
        advanceUntilIdle()
        
        verify(interactionProvider).showStudentSearchDialog(any(), any(), any())
    }

    @Test
    fun showEditSessionDialog_locked_earlyExit() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Locked", date = 1000L, isLocked = true)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.showEditSessionDialog(insertedSession)
        verify(interactionProvider).showToast(eq(R.string.msg_session_locked), any())
        verify(interactionProvider, never()).showEditSessionDialog(any(), any())
    }

    @Test
    fun showEditSessionDialog_unlocked_callsProvider() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Unlocked", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        
        sessionController.showEditSessionDialog(insertedSession)
        verify(interactionProvider).showEditSessionDialog(eq(insertedSession), any())
    }

    @Test
    fun clearActiveSession_setsNull() {
        sessionController.clearActiveSession()
        assert(sessionController.activeSession == null)
    }

    @Test
    fun registerAttendance_nullSession_returnsEarly() = runTest(mainDispatcherRule.testDispatcher) {
        sessionController.clearActiveSession()
        sessionController.registerAttendance(mock(), 1000L)
        advanceUntilIdle()
        verify(interactionProvider, never()).showToast(any<Int>(), any())
    }

    @Test
    fun loadAttendanceList_nullSession_returnsEarly() {
        sessionController.clearActiveSession()
        sessionController.loadAttendanceList()
        verify(interactionProvider, never()).submitAttendanceList(any(), anyOrNull())
    }

    @Test
    fun resetSyncTimeout_triggersAfter5Seconds() = runTest(mainDispatcherRule.testDispatcher) {
        sessionController.resetSyncTimeout()
        
        // Advance 4 seconds - nothing should happen yet
        advanceTimeBy(4000)
        verify(interactionProvider, never()).showLayoutRefreshSpinner(false)
        
        // Advance the final second
        advanceTimeBy(1001)
        
        verify(interactionProvider).showLayoutRefreshSpinner(false)
        verify(interactionProvider).showToast(argThat<String> { contains("Sync timed out") }, eq(true))
    }

    @Test
    fun resetSyncTimeout_multipleCalls_resetsTimer() = runTest(mainDispatcherRule.testDispatcher) {
        sessionController.resetSyncTimeout()
        
        advanceTimeBy(3000)
        sessionController.resetSyncTimeout() // Reset at 3s
        
        advanceTimeBy(4000) // Total 7s, but only 4s since last reset
        verify(interactionProvider, never()).showLayoutRefreshSpinner(false)
        
        advanceTimeBy(1001) // Total 8s, 5s since last reset
        verify(interactionProvider).showLayoutRefreshSpinner(false)
    }

    @Test
    fun cancelSyncTimeout_stopsTimer() = runTest(mainDispatcherRule.testDispatcher) {
        sessionController.resetSyncTimeout()
        
        advanceTimeBy(3000)
        sessionController.cancelSyncTimeout()
        
        advanceTimeBy(3000) // Past the original 5s mark
        verify(interactionProvider, never()).showLayoutRefreshSpinner(false)
        verify(interactionProvider, never()).showToast(any<String>(), any())
    }

    @Test
    fun cancelSyncTimeout_noActiveJob_worksFine() = runTest(mainDispatcherRule.testDispatcher) {
        sessionController.cancelSyncTimeout()
        advanceUntilIdle()
    }

    @Test
    fun onSessionUpdated_nullSession_updatesUIOnly() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(id = 500, courseId = courseId, name = "Original", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        
        val onSessionUpdatedCaptor = argumentCaptor<(String, Long) -> Unit>()
        sessionController.showEditSessionDialog(session)
        verify(interactionProvider).showEditSessionDialog(eq(session), onSessionUpdatedCaptor.capture())
        
        sessionController.clearActiveSession()
        onSessionUpdatedCaptor.firstValue.invoke("New", 2000L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        assert(sessionController.activeSession == null)
        verify(interactionProvider).updateSessionCard(eq("New"), eq(2000L), any())
    }

    @Test
    fun openSessionView_setupListeners() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test", date = 1000L)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()

        sessionController.openSessionView(insertedSession)
        
        val lockCaptor = argumentCaptor<() -> Unit>()
        val editCaptor = argumentCaptor<() -> Unit>()
        verify(interactionProvider).setupSessionListeners(lockCaptor.capture(), editCaptor.capture())
        
        // Test lock listener separately from edit to avoid state pollution
    }

    @Test
    fun showLayoutRefreshSpinner_callsProvider() {
        sessionController.showLayoutRefreshSpinner(true)
        verify(interactionProvider).showLayoutRefreshSpinner(true)
    }
}
