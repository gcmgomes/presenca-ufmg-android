package com.example.presensor.controllers

import android.view.LayoutInflater
import com.example.presensor.R
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Course
import com.example.presensor.tools.providers.ToastProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.shadows.ShadowLooper
import androidx.appcompat.app.AlertDialog
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows
import android.widget.EditText

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerUnitTest : BaseControllerTest() {

    private lateinit var sessionController: SessionController
    private val dialogFactory: SessionControllerDialogFactory = mock()
    private val toastProvider: ToastProvider = mock()
    private val onSessionStateMutated: () -> Unit = mock()
    private val onPulldown: () -> Unit = mock()

    private lateinit var rvAttendance: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var txtSessionTitle: TextView
    private lateinit var txtSessionSubtitle: TextView
    private lateinit var viewSessionDetailAccent: View
    private lateinit var imgMasterLock: ImageView
    private lateinit var btnEditSession: ImageView

    @Before
    override fun setup() {
        super.setup()

        rvAttendance = RecyclerView(activity)
        swipeRefreshLayout = SwipeRefreshLayout(activity)
        txtSessionTitle = TextView(activity)
        txtSessionSubtitle = TextView(activity)
        viewSessionDetailAccent = View(activity)
        imgMasterLock = ImageView(activity)
        btnEditSession = ImageView(activity)

        sessionController = SessionController(
            activity = activity,
            context = activity,
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            db = db,
            layoutInflater = LayoutInflater.from(activity),
            rvAttendance = rvAttendance,
            swipeRefreshLayout = swipeRefreshLayout,
            txtSessionTitle = txtSessionTitle,
            txtSessionSubtitle = txtSessionSubtitle,
            viewSessionDetailAccent = viewSessionDetailAccent,
            imgMasterLock = imgMasterLock,
            btnEditSession = btnEditSession,
            getColorForAccent = { 0 },
            onSessionStateMutated = onSessionStateMutated,
            dialogFactory = dialogFactory,
            toastProvider = toastProvider,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher,
            onPulldown = onPulldown,
            onSyncTimeout = {}
        )
    }

    @After
    override fun tearDown() {
        ShadowDialog.getShownDialogs().forEach { it.dismiss() }
        super.tearDown()
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

        assert(txtSessionTitle.text == "Test Session")
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
        verify(toastProvider).showToast(any(), any())
    }

    @Test
    fun handleLockToggleSequence_locked_wrongPassword_showsError() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test Session", date = 1000L, isLocked = true)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        
        sessionController.handleLockToggleSequence(insertedSession)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assert(dialog != null)
        assert(dialog!!.isShowing)
        
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        verify(toastProvider).showToast(argThat { contains("Incorrect", ignoreCase = true) }, any())
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

        verify(toastProvider).showToast(argThat { contains("locked", ignoreCase = true) }, any())
        val records = db.getAttendanceRecordsForSession(insertedSession.id)
        assert(records.isEmpty())
    }

    @Test
    fun handleLockToggleSequence_locked_correctPassword_unlocks() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "UnlockMe", date = 1000L, isLocked = true)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        advanceUntilIdle()
        
        sessionController.handleLockToggleSequence(insertedSession)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        val dialog = ShadowDialog.getShownDialogs().lastOrNull() as? AlertDialog
        
        // Find EditText via tag
        val input = dialog?.window?.decorView?.findViewWithTag<EditText>("unlock_input")
        input?.setText("UnlockMe")
        
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        assert(sessionController.activeSession?.isLocked == false)
    }

    @Test
    fun registerAttendance_unlockedSession_recordsAttendance() = runTest(mainDispatcherRule.testDispatcher) {
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
    }

    @Test
    fun showManualAttendanceDialog_populatesStudentList() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        val student = Student(email = "s@test.com", name = "Test Student", rfid = null)
        db.insertStudents(listOf(student))
        
        sessionController.openSessionView(insertedSession)
        advanceUntilIdle()
        
        sessionController.showManualAttendanceDialog()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        val dialog = ShadowDialog.getShownDialogs().lastOrNull()
        assert(dialog != null)
        
        val rv = dialog!!.findViewById<RecyclerView>(R.id.rvStudentSearch)
        assert(rv != null)

        ShadowLooper.idleMainLooper()
        advanceUntilIdle()

        assert((rv!!.adapter?.itemCount ?: 0) > 0)
    }

    @Test
    fun `manualAttendanceDialog CreateNewStudent triggers registration and records attendance`() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "S1", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        advanceUntilIdle()

        sessionController.showManualAttendanceDialog()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val dialog = ShadowDialog.getLatestDialog()
        val btnCreate = dialog.findViewById<View>(R.id.btnCreateNewStudent)
        btnCreate.performClick()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        // Verify registration dialog was shown
        val regCaptor = argumentCaptor<(String, String, AlertDialog) -> Unit>()
        verify(dialogFactory).showManualRegistrationDialog(any(), regCaptor.capture())

        // Simulate saving
        val regDialog: AlertDialog = mock()
        regCaptor.firstValue.invoke("New Student", "new@test.com", regDialog)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        // Verify student inserted and attendance registered
        val student = db.getAllStudents().find { it.email == "new@test.com" }
        assert(student != null)
        assert(db.getAttendanceRecordsForSession(insertedSession.id).isNotEmpty())
        verify(regDialog).dismiss()
    }

    @Test
    fun showEditSessionDialog_locked_earlyExit() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Locked", date = 1000L, isLocked = true)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.showEditSessionDialog(insertedSession)
        verify(toastProvider).showToast(argThat { contains("locked", ignoreCase = true) }, any())
        verifyNoInteractions(dialogFactory)
    }

    @Test
    fun showEditSessionDialog_unlocked_callsFactory() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Unlocked", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        
        val onSessionUpdatedCaptor = argumentCaptor<(String, Long) -> Unit>()
        sessionController.showEditSessionDialog(insertedSession)
        verify(dialogFactory).showEditSessionDialog(eq(insertedSession), onSessionUpdatedCaptor.capture())
        
        onSessionUpdatedCaptor.firstValue.invoke("New Name", 2000L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        assert(sessionController.activeSession?.name == "New Name")
        assert(txtSessionTitle.text == "New Name")
    }

    @Test
    fun clearActiveSession_setsNull() {
        sessionController.clearActiveSession()
        assert(sessionController.activeSession == null)
    }

    @Test
    fun openSessionView_clickLock_togglesLock() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        
        imgMasterLock.performClick()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        assert(sessionController.activeSession?.isLocked == true)
    }

    @Test
    fun openSessionView_clickEdit_showsDialog() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        sessionController.openSessionView(insertedSession)
        
        btnEditSession.performClick()
        verify(dialogFactory).showEditSessionDialog(any(), any())
    }

    @Test
    fun registerAttendance_nullSession_returnsEarly() = runTest(mainDispatcherRule.testDispatcher) {
        sessionController.clearActiveSession()
        sessionController.registerAttendance(mock(), 1000L)
        advanceUntilIdle()
        verifyNoInteractions(toastProvider)
    }

    @Test
    fun loadAttendanceList_nullSession_returnsEarly() {
        sessionController.clearActiveSession()
        sessionController.loadAttendanceList()
        assert(sessionController.attendanceAdapter.itemCount == 0)
    }

    @Test
    fun showManualAttendanceDialog_nullSession_returnsEarly() = runTest(mainDispatcherRule.testDispatcher) {
        sessionController.clearActiveSession()
        sessionController.showManualAttendanceDialog()
        advanceUntilIdle()
        assert(ShadowDialog.getShownDialogs().isEmpty())
    }

    @Test
    fun clickListeners_nullSession_doNothing() {
        sessionController.clearActiveSession()
        imgMasterLock.performClick()
        btnEditSession.performClick()
        verifyNoInteractions(dialogFactory)
    }

    @Test
    fun onSessionUpdated_nullSession_updatesUIOnly() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(id = 500, courseId = courseId, name = "Original", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        
        val onSessionUpdatedCaptor = argumentCaptor<(String, Long) -> Unit>()
        sessionController.showEditSessionDialog(session)
        verify(dialogFactory).showEditSessionDialog(eq(session), onSessionUpdatedCaptor.capture())
        
        sessionController.clearActiveSession()
        onSessionUpdatedCaptor.firstValue.invoke("New", 2000L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        assert(sessionController.activeSession == null)
        assert(txtSessionTitle.text == "New")
    }

    @Test
    fun resetSyncTimeout_triggersAfter10Seconds() = runTest(mainDispatcherRule.testDispatcher) {
        swipeRefreshLayout.isRefreshing = true
        sessionController.resetSyncTimeout()
        
        // Advance 4 seconds - nothing should happen yet
        advanceTimeBy(4000)
        assert(swipeRefreshLayout.isRefreshing)
        
        // Advance the final second
        advanceTimeBy(1001)
        
        assert(!swipeRefreshLayout.isRefreshing)
        verify(toastProvider).showToast(argThat { contains("Sync timed out") }, any())
    }

    @Test
    fun resetSyncTimeout_multipleCalls_resetsTimer() = runTest(mainDispatcherRule.testDispatcher) {
        swipeRefreshLayout.isRefreshing = true
        sessionController.resetSyncTimeout()
        
        advanceTimeBy(3000)
        sessionController.resetSyncTimeout() // Reset at 3s
        
        advanceTimeBy(4000) // Total 7s, but only 4s since last reset
        assert(swipeRefreshLayout.isRefreshing)
        
        advanceTimeBy(1001) // Total 8s, 5s since last reset
        assert(!swipeRefreshLayout.isRefreshing)
    }

    @Test
    fun cancelSyncTimeout_stopsTimer() = runTest(mainDispatcherRule.testDispatcher) {
        swipeRefreshLayout.isRefreshing = true
        sessionController.resetSyncTimeout()
        
        advanceTimeBy(3000)
        sessionController.cancelSyncTimeout()
        
        advanceTimeBy(3000) // Past the original 5s mark
        assert(swipeRefreshLayout.isRefreshing)
        verifyNoInteractions(toastProvider)
    }

    @Test
    fun cancelSyncTimeout_noActiveJob_worksFine() = runTest(mainDispatcherRule.testDispatcher) {
        // This covers the branch where syncTimeoutJob is null in cancelSyncTimeout()
        sessionController.cancelSyncTimeout()
        advanceUntilIdle()
        // No crash means success
    }

    @Test
    fun openSessionView_clickLock_nullActiveSession_doesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        
        // Setup listeners by opening a session
        sessionController.openSessionView(insertedSession)
        
        // Manually clear the session to trigger the null branch in the listener
        sessionController.clearActiveSession()
        
        imgMasterLock.performClick()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        // Verify handleLockToggleSequence was NOT called for any session
        // (If it was called, it would have changed something in the DB or shown a toast)
        val updatedSession = db.getSessionById(insertedSession.id)
        assert(updatedSession?.isLocked == false)
    }

    @Test
    fun openSessionView_clickEdit_nullActiveSession_doesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        val courseId = db.insertCourse(Course(name = "C1"))
        val session = Session(courseId = courseId, name = "Test", date = 1000L, isLocked = false)
        db.insertSessions(listOf(session))
        val insertedSession = db.getSessionsByCourse(courseId).first()
        
        sessionController.openSessionView(insertedSession)
        sessionController.clearActiveSession()
        
        btnEditSession.performClick()
        verifyNoInteractions(dialogFactory)
    }

    @Test
    fun showLayoutRefreshSpinner_togglesRefreshing() {
        sessionController.showLayoutRefreshSpinner(true)
        assert(swipeRefreshLayout.isRefreshing)
        
        sessionController.showLayoutRefreshSpinner(false)
        assert(!swipeRefreshLayout.isRefreshing)
    }

    @Test
    fun constructor_defaultParams_initializesCorrectly() {
        val sc = SessionController(
            activity = activity,
            context = activity,
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            db = db,
            layoutInflater = LayoutInflater.from(activity),
            rvAttendance = rvAttendance,
            swipeRefreshLayout = swipeRefreshLayout,
            txtSessionTitle = txtSessionTitle,
            txtSessionSubtitle = txtSessionSubtitle,
            viewSessionDetailAccent = viewSessionDetailAccent,
            imgMasterLock = imgMasterLock,
            btnEditSession = btnEditSession,
            getColorForAccent = { 0 },
            onSessionStateMutated = {},
            dialogFactory = dialogFactory,
            toastProvider = toastProvider,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher,
            onPulldown = {},
            onSyncTimeout = {}
        )
        assert(sc.activeSession == null)
    }
}
