package com.example.presensor.controllers

import android.nfc.Tag
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.communication.core.AppMode
import com.example.presensor.controllers.providers.TagInteractionProvider
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class TagControllerTest : BaseControllerTest() {

    private lateinit var controller: TagController
    private val interactionProvider: TagInteractionProvider = mock()
    private val orchestrator: ReaderOrchestrator = mock()
    private val sessionController: SessionController = mock()
    
    private val rfidSwipeFlow = MutableSharedFlow<Pair<String, Long>>()
    private var currentState = MainActivity.Companion.AppState.COURSE

    @Before
    override fun setup() {
        super.setup()
        
        whenever(orchestrator.rfidSwipeFlow).thenReturn(rfidSwipeFlow)
        
        controller = TagController(
            interactionProvider = interactionProvider,
            db = db,
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            readerOrchestrator = orchestrator,
            sessionController = sessionController,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            isDialogShowingCheck = { false },
            disableRefreshSpinner = {},
            resetSyncTimeout = {},
            getCurrentState = { currentState }
        )
    }

    @Test
    fun `handleTagDiscovered registers attendance when session is active`() = runTest {
        val session = Session(id = 1, courseId = 1, name = "S1", date = 0L)
        whenever(sessionController.activeSession).thenReturn(session)
        
        controller.handleTagDiscovered("01:02:03:04", 1000L)
        advanceUntilIdle()
        
        verify(sessionController).registerAttendance(anyOrNull(), eq(1000L))
    }

    @Test
    fun `handleTagDiscovered shows binding dialog when no student and no session`() = runTest {
        whenever(sessionController.activeSession).thenReturn(null)
        
        controller.handleTagDiscovered("01:02:03:04", 1000L)
        advanceUntilIdle()
        
        verify(interactionProvider).showBindingDialog(eq("01:02:03:04"), any(), any(), any(), any())
    }

    @Test
    fun `startReaderCollection listens to flow`() = runTest {
        controller.startReaderCollection()
        
        rfidSwipeFlow.emit("01020304" to 1000L)
        advanceUntilIdle()
        
        // Should trigger handleTagDiscovered -> sessionController.registerAttendance if session active
        verify(sessionController).registerAttendance(anyOrNull(), eq(1000000L))
    }

    @Test
    fun `onTagDiscovered delegates to handleTagDiscovered`() = runTest {
        val tag: Tag = mock()
        whenever(tag.id).thenReturn(byteArrayOf(1, 2, 3, 4))
        
        controller.onTagDiscovered(tag)
        advanceUntilIdle()
        
        verify(sessionController).registerAttendance(anyOrNull(), any())
    }

    @Test
    fun `pause and resume NFC`() {
        controller.pauseNfcScanning()
        verify(interactionProvider).toggleNfcScanning(eq(false), isNull())
        
        controller.resumeNfcScanning()
        verify(interactionProvider).toggleNfcScanning(eq(true), eq(controller))
    }

    @Test
    fun `resumeReader sets app mode`() {
        controller.resumeReader()
        verify(orchestrator).setAppMode(eq(AppMode.ACTIVE), any())
    }
}
