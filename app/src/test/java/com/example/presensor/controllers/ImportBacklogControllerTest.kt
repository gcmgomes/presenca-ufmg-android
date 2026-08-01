package com.example.presensor.controllers

import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.entities.Student
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.providers.ReaderInteractionProvider
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
    private val interactionProvider: ReaderInteractionProvider = mock()
    private val toggleSpinner: (Boolean) -> Unit = mock()
    private val registerAttendance: (Student?, Long, Boolean) -> Unit = mock()
    private val refreshAttendanceList: () -> Unit = mock()

    private val inventoryFlow = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 10)

    @Before
    override fun setup() {
        super.setup()

        whenever(orchestrator.inventoryFlow).thenReturn(inventoryFlow)
        whenever(orchestrator.isAuthenticated).thenReturn(MutableStateFlow(true))

        controller = ImportBacklogController(
            interactionProvider = interactionProvider,
            scope = TestScope(mainDispatcherRule.testDispatcher),
            db = db,
            orchestrator = orchestrator,
            toggleSpinner = toggleSpinner,
            registerAttendance = registerAttendance,
            refreshAttendanceList = refreshAttendanceList
        )
    }

    @Test
    fun `startImportFlow triggers inventory request`() = runTest {
        controller.startImportFlow()
        advanceUntilIdle()

        verify(orchestrator).requestInventory()
        verify(interactionProvider).showBacklogImportPreview(any(), any())
    }

    @Test
    fun `executeSequentialImport handles success and updates UI`() = runTest {
        val student =
            Student(email = "test@example.com", name = "Test Student", rfid = "AA:BB:CC:DD")
        val item = BacklogItem("AABBCCDD", student, 1000L)

        // Trigger import
        controller.executeSequentialImport(listOf(item))

        // Simulate DEL_OK
        inventoryFlow.emit("DEL_OK" to 0L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        verify(orchestrator).deleteBacklogItem("AABBCCDD", 1000L)
        verify(registerAttendance).invoke(eq(student), eq(1000000L), any())
        verify(interactionProvider).removeBacklogItem(item)
        verify(interactionProvider).dismissActiveDialog()
        verify(refreshAttendanceList).invoke()
    }
}
