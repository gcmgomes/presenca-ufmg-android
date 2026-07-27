package com.example.presensor.controllers

import androidx.appcompat.app.AlertDialog
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.providers.ToastProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
class ImportBacklogControllerTest : BaseControllerTest() {

    private lateinit var controller: ImportBacklogController
    private val orchestrator: ReaderOrchestrator = mock()
    private val toastProvider: ToastProvider = mock()
    private val toggleSpinner: (Boolean) -> Unit = mock()
    private val registerAttendance: (Student?, Long) -> Unit = mock()
    private val refreshAttendanceList: () -> Unit = mock()
    
    private val inventoryFlow = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 10)

    @Before
    override fun setup() {
        super.setup()
        
        whenever(orchestrator.inventoryFlow).thenReturn(inventoryFlow)
        whenever(orchestrator.isAuthenticated).thenReturn(MutableStateFlow(true))

        controller = ImportBacklogController(
            activity = activity,
            scope = TestScope(mainDispatcherRule.testDispatcher),
            db = db,
            orchestrator = orchestrator,
            toastProvider = toastProvider,
            toggleSpinner = toggleSpinner,
            registerAttendance = registerAttendance,
            refreshAttendanceList = refreshAttendanceList
        )
    }

    @Test
    fun `startImportFlow triggers LIST`() = runTest {
        controller.startImportFlow()
        advanceUntilIdle()

        verify(orchestrator).requestInventory()
    }

    @Test
    fun `executeSequentialImport handles success and updates dialog`() = runTest {
        val student = Student(email = "test@example.com", name = "Test Student", rfid = "AA:BB:CC:DD")
        val item = ImportBacklogController.BacklogUIItem("AABBCCDD", student, 1000L)
        val mockAdapter: ImportBacklogController.ImportBacklogAdapter = mock()
        whenever(mockAdapter.currentList).thenReturn(listOf(item))
        val mockDialog: AlertDialog = mock()

        // Trigger import
        controller.executeSequentialImport(listOf(item), mockAdapter, mockDialog)
        
        // Simulate DEL_OK
        inventoryFlow.emit("DEL_OK" to 0L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        verify(orchestrator).deleteBacklogItem("AABBCCDD", 1000L)
        verify(registerAttendance).invoke(eq(student), eq(1000000L))
        verify(mockAdapter).submitList(emptyList())
        verify(mockDialog).dismiss()
        verify(refreshAttendanceList).invoke()
    }
}
