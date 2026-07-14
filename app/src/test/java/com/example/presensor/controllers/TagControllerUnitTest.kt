package com.example.presensor.controllers

import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.TagControllerDialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory
import androidx.appcompat.app.AlertDialog
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.providers.ToastProvider
import android.app.Dialog
import android.nfc.Tag
import android.nfc.NfcAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
class TagControllerUnitTest : BaseControllerTest() {

    private lateinit var tagController: TagController
    private val sessionController: SessionController = mock()
    private val sessionDialogFactory: SessionControllerDialogFactory = mock()
    private val tagControllerDialogFactory: TagControllerDialogFactory = mock()
    private val toastProvider: ToastProvider = mock()
    private val nfcAdapter: NfcAdapter = mock()
    private lateinit var testScope: CoroutineScope

    @Before
    override fun setup() {
        super.setup()
        testScope = CoroutineScope(mainDispatcherRule.testDispatcher)
        tagController = TagController(
            activity = activity,
            db = db,
            scope = testScope,
            sessionController = sessionController,
            sessionDialogFactory = sessionDialogFactory,
            tagControllerDialogFactory = tagControllerDialogFactory,
            toastProvider = toastProvider,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            nfcAdapter = nfcAdapter,
            isDialogShowingCheck = { false }
        )
    }

    @Test
    fun handleTagDiscovered_studentExists_activeSession_registersAttendance() = runTest(mainDispatcherRule.testDispatcher) {
        val rfid = "AA:BB:CC:DD"
        val time = 1000L
        val student = Student(email = "test@example.com", name = "Test Student", rfid = rfid)
        db.insertStudents(listOf(student))
        
        whenever(sessionController.activeSession).thenReturn(mock())

        tagController.handleTagDiscovered(rfid, time)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        verify(sessionController).registerAttendance(any(), eq(time))
    }

    @Test
    fun handleTagDiscovered_studentExists_noActiveSession_showsOverwriteConfirmation() = runTest(mainDispatcherRule.testDispatcher) {
        val rfid = "AA:BB:CC:DD"
        val time = 1000L
        val student = Student(email = "test@example.com", name = "Test Student", rfid = rfid)
        db.insertStudents(listOf(student))
        
        whenever(sessionController.activeSession).thenReturn(null)

        tagController.handleTagDiscovered(rfid, time)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        verify(tagControllerDialogFactory).showOverwriteConfirmation(any(), eq(rfid), any())
    }

    @Test
    fun handleTagDiscovered_noStudent_showsBindingDialog() = runTest(mainDispatcherRule.testDispatcher) {
        val rfid = "EE:FF:GG:HH"
        val time = 1000L
        
        whenever(sessionController.activeSession).thenReturn(null)

        tagController.handleTagDiscovered(rfid, time)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        verify(tagControllerDialogFactory).showBindingDialog(eq(rfid), any(), any(), any(), any())
    }

    @Test
    fun handleTagDiscovered_dialogOpen_earlyExit() = runTest(mainDispatcherRule.testDispatcher) {
        val rfid = "AA:BB:CC:DD"
        val time = 1000L
        
        val tagControllerWithOpenDialog = TagController(
            activity = activity,
            db = db,
            scope = testScope,
            sessionController = sessionController,
            sessionDialogFactory = sessionDialogFactory,
            tagControllerDialogFactory = tagControllerDialogFactory,
            toastProvider = toastProvider,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            nfcAdapter = nfcAdapter,
            isDialogShowingCheck = { true }
        )

        tagControllerWithOpenDialog.handleTagDiscovered(rfid, time)
        advanceUntilIdle()

        verifyNoInteractions(sessionController)
    }

    @Test
    fun showOverwriteConfirmation_onConfirm_unbindsAndShowsBinding() = runTest(mainDispatcherRule.testDispatcher) {
        val rfid = "AA:BB:CC:DD"
        val student = Student(email = "test@example.com", name = "Test Student", rfid = rfid)
        db.insertStudents(listOf(student))
        
        whenever(sessionController.activeSession).thenReturn(null)
        
        tagController.handleTagDiscovered(rfid, 1000L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val captor = argumentCaptor<() -> Unit>()
        verify(tagControllerDialogFactory).showOverwriteConfirmation(any(), eq(rfid), captor.capture())
        
        captor.firstValue.invoke()
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val updatedStudent = db.getStudentByRfid(rfid)
        assert(updatedStudent == null)
        
        verify(toastProvider).showToast(any(), any())
        verify(tagControllerDialogFactory).showBindingDialog(eq(rfid), any(), any(), any(), any())
    }

    @Test
    fun showBindingDialog_onStudentSelected_bindsTag() = runTest(mainDispatcherRule.testDispatcher) {
        val rfid = "EE:FF:GG:HH"
        val student = Student(email = "new@example.com", name = "New Student", rfid = null)
        db.insertStudents(listOf(student))
        
        whenever(sessionController.activeSession).thenReturn(null)

        tagController.handleTagDiscovered(rfid, 1000L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val studentSelectedCaptor = argumentCaptor<(Student) -> Unit>()
        verify(tagControllerDialogFactory).showBindingDialog(
            eq(rfid), any(), studentSelectedCaptor.capture(), any(), any()
        )

        studentSelectedCaptor.firstValue.invoke(student)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val boundStudent = db.getStudentByRfid(rfid)
        assert(boundStudent?.email == student.email)
        verify(toastProvider).showToast(any(), any())
    }

    @Test
    fun showBindingDialog_onManualAttendance_showsRegistrationDialog() = runTest(mainDispatcherRule.testDispatcher) {
        val rfid = "EE:FF:GG:HH"
        whenever(sessionController.activeSession).thenReturn(null)

        tagController.handleTagDiscovered(rfid, 1000L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val manualAttendanceCaptor = argumentCaptor<() -> Unit>()
        verify(tagControllerDialogFactory).showBindingDialog(
            eq(rfid), any(), any(), manualAttendanceCaptor.capture(), any()
        )

        manualAttendanceCaptor.firstValue.invoke()

        verify(sessionDialogFactory).showManualRegistrationDialog(eq(rfid), any())
    }

    @Test
    fun showRegistrationDialog_onStudentSaved_insertsAndShowsToast() = runTest(mainDispatcherRule.testDispatcher) {
        val rfid = "NEW:RFID"
        val name = "New User"
        val email = "new@user.com"
        val mockDialog: AlertDialog = mock()

        tagController.handleTagDiscovered(rfid, 1000L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        val manualAttendanceCaptor = argumentCaptor<() -> Unit>()
        verify(tagControllerDialogFactory).showBindingDialog(any(), any(), any(), manualAttendanceCaptor.capture(), any())
        manualAttendanceCaptor.firstValue.invoke()
        
        val onStudentSavedCaptor = argumentCaptor<(String, String, AlertDialog) -> Unit>()
        verify(sessionDialogFactory).showManualRegistrationDialog(eq(rfid), onStudentSavedCaptor.capture())

        onStudentSavedCaptor.firstValue.invoke(name, email, mockDialog)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val student = db.getStudentByRfid(rfid)
        assert(student?.name == name)
        assert(student?.email == email)
        verify(toastProvider).showToast(any(), any())
        verify(mockDialog).dismiss()
    }

    @Test
    fun pauseNfcScanning_callsDisableReaderMode() {
        tagController.pauseNfcScanning()
        verify(nfcAdapter).disableReaderMode(any())
    }

    @Test
    fun resumeNfcScanning_callsEnableReaderMode() {
        DialogFactory.setDialogOpenForTesting(false)
        tagController.resumeNfcScanning()
        
        val flagsCaptor = argumentCaptor<Int>()
        verify(nfcAdapter).enableReaderMode(any(), any(), flagsCaptor.capture(), any())
        
        assert(flagsCaptor.firstValue and NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS == 0)
    }

    @Test
    fun resumeNfcScanning_dialogOpen_usesNoPlatformSounds() {
        DialogFactory.setDialogOpenForTesting(true)
        tagController.resumeNfcScanning()
        
        val flagsCaptor = argumentCaptor<Int>()
        verify(nfcAdapter).enableReaderMode(any(), any(), flagsCaptor.capture(), any())
        
        assert(flagsCaptor.firstValue and NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS != 0)
    }

    @Test
    fun onTagDiscovered_delegatesToHandleTagDiscovered() = runTest(mainDispatcherRule.testDispatcher) {
        val mockTag: Tag = mock()
        val id = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        whenever(mockTag.id).thenReturn(id)
        
        tagController.onTagDiscovered(mockTag)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        verify(sessionController).activeSession
    }

    @Test
    fun getNfcAdapter_returnsAdapter() {
        assert(tagController.getNfcAdapter() == nfcAdapter)
    }
}
