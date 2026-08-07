package com.example.presensor.controllers

import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.entities.Student
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.providers.ReaderInteractionProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ImportBacklogControllerTest : BaseControllerTest() {

    private lateinit var controller: ImportBacklogController
    private val orchestrator: ReaderOrchestrator = mock()
    private val interactionProvider: ReaderInteractionProvider = mock()
    private val toggleSpinner: (Boolean) -> Unit = mock()
    private val registerAttendance: (Student?, Long, Boolean) -> Unit = mock()
    private val refreshAttendanceList: () -> Unit = mock()

    private val inventoryFlow = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 10)
    private val isAuthenticatedFlow = MutableStateFlow(true)

    @Before
    override fun setup() {
        super.setup()

        whenever(orchestrator.inventoryFlow).thenReturn(inventoryFlow)
        whenever(orchestrator.isAuthenticated).thenReturn(isAuthenticatedFlow)

        controller = ImportBacklogController(
            interactionProvider = interactionProvider,
            scope = TestScope(mainDispatcherRule.testDispatcher),
            db = db,
            orchestrator = orchestrator,
            toggleSpinner = toggleSpinner,
            registerAttendance = registerAttendance,
            refreshAttendanceList = refreshAttendanceList,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
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
    fun `startImportFlow handles null orchestrator`() = runTest {
        val controllerWithNullOrch = ImportBacklogController(
            interactionProvider = interactionProvider,
            scope = TestScope(mainDispatcherRule.testDispatcher),
            db = db,
            orchestrator = null,
            toggleSpinner = toggleSpinner,
            registerAttendance = registerAttendance,
            refreshAttendanceList = refreshAttendanceList,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )

        controllerWithNullOrch.startImportFlow()
        advanceUntilIdle()

        verify(toggleSpinner).invoke(false)
        verify(interactionProvider).showToast("No authenticated device found.")
    }

    @Test
    fun `startImportFlow handles unauthenticated state`() = runTest {
        isAuthenticatedFlow.value = false

        controller.startImportFlow()
        advanceUntilIdle()

        verify(toggleSpinner).invoke(false)
        verify(interactionProvider).showToast("No authenticated device found.")
    }

    @Test
    fun `fetchBacklogItems stops on SYNC_DONE`() = runTest {
        controller.startImportFlow()

        inventoryFlow.emit("TAG1" to 1000L)
        inventoryFlow.emit("SYNC_DONE" to 0L)
        inventoryFlow.emit("TAG2" to 2000L) // Should be ignored
        advanceUntilIdle()

        verify(interactionProvider, atLeastOnce()).addBacklogItem(argThat { tagId == "TAG1" }, any())
    }

    @Test
    fun `fetchBacklogItems filters DEL_OK and DEL_ERR`() = runTest {
        controller.startImportFlow()

        inventoryFlow.emit("DEL_OK" to 0L)
        inventoryFlow.emit("DEL_ERR" to 0L)
        inventoryFlow.emit("TAG1" to 1000L)
        inventoryFlow.emit("SYNC_DONE" to 0L)
        advanceUntilIdle()

        verify(interactionProvider, atLeastOnce()).addBacklogItem(argThat { tagId == "TAG1" }, any())
    }

    @Test
    fun `fetchBacklogItems lookups student by RFID`() = runTest {
        val rfid = "AA:BB:CC:DD"
        val student = Student(email = "test@example.com", name = "Test Student", rfid = rfid)
        db.insertStudents(listOf(student))

        controller.startImportFlow()

        inventoryFlow.emit("AABBCCDD" to 1000L)
        inventoryFlow.emit("SYNC_DONE" to 0L)
        advanceUntilIdle()

        verify(interactionProvider).addBacklogItem(argThat { this.student?.rfid == rfid }, any())
    }

    @Test
    fun `fetchBacklogItems auto-selects matching session`() = runTest {
        // Thursday 2026-08-06 10:00:00 (matching our mock date below)
        val sessionDate = LocalDateTime.of(2026, 8, 6, 0, 0)
        val sessionDateMillis =
            sessionDate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val startTime = 600L // 10:00
        val endTime = 720L   // 12:00

        controller.startImportFlow(startTime, endTime, sessionDateMillis)

        // 10:30 (matching)
        val timeInside =
            LocalDateTime.of(2026, 8, 6, 10, 30).atZone(ZoneId.systemDefault()).toEpochSecond()
        inventoryFlow.emit("TAG1" to timeInside)

        // 12:10 (too late, but within +10 buffer)
        val timeEdge =
            LocalDateTime.of(2026, 8, 6, 12, 10).atZone(ZoneId.systemDefault()).toEpochSecond()
        inventoryFlow.emit("TAG2" to timeEdge)

        inventoryFlow.emit("SYNC_DONE" to 0L)
        advanceUntilIdle()

        verify(interactionProvider).addBacklogItem(argThat { tagId == "TAG1" }, eq(true))
        verify(interactionProvider).addBacklogItem(argThat { tagId == "TAG2" }, eq(true))
    }

    @Test
    fun `fetchBacklogItems handles timeout`() = runTest {
        controller.startImportFlow()
        advanceUntilIdle()

        inventoryFlow.emit("TAG1" to 1000L)

        // Wait for 10s timeout
        advanceTimeBy(11000L)
        advanceUntilIdle()

        verify(interactionProvider).toggleBacklogImportLoading(false)
        verify(toggleSpinner).invoke(false)
    }

    @Test
    fun `startImportFlow preview onConfirm handles empty selection`() = runTest {
        controller.startImportFlow()
        advanceUntilIdle()

        val captor = argumentCaptor<(List<BacklogItem>) -> Unit>()
        verify(interactionProvider).showBacklogImportPreview(captor.capture(), any())

        captor.firstValue.invoke(emptyList())
        advanceUntilIdle()

        verify(interactionProvider).showToast("Please select at least one item")
    }

    @Test
    fun `startImportFlow preview onConfirm triggers import`() = runTest {
        controller.startImportFlow()

        val captor = argumentCaptor<(List<BacklogItem>) -> Unit>()
        verify(interactionProvider).showBacklogImportPreview(captor.capture(), any())

        val item = BacklogItem("TAG1", null, 1000L)
        captor.firstValue.invoke(listOf(item))
        advanceUntilIdle()

        verify(interactionProvider, atLeastOnce()).toggleBacklogImportLoading(true)
        verify(orchestrator).deleteBacklogItem("TAG1", 1000L)
    }

    @Test
    fun `executeSequentialImport handles success and updates UI`() = runTest {
        val student =
            Student(email = "test@example.com", name = "Test Student", rfid = "AA:BB:CC:DD")
        val item = BacklogItem("AABBCCDD", student, 1000L)

        controller.executeSequentialImport(listOf(item))

        inventoryFlow.emit("DEL_OK" to 0L)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        verify(orchestrator).deleteBacklogItem("AABBCCDD", 1000L)
        verify(registerAttendance).invoke(eq(student), eq(1000000L), any())
        verify(interactionProvider).removeBacklogItem(item)
        verify(interactionProvider).dismissActiveDialog()
        verify(refreshAttendanceList).invoke()
    }

    @Test
    fun `executeSequentialImport handles failure DEL_ERR`() = runTest {
        val item = BacklogItem("TAG1", null, 1000L)

        controller.executeSequentialImport(listOf(item))

        inventoryFlow.emit("DEL_ERR" to 0L)
        advanceUntilIdle()

        verify(orchestrator).deleteBacklogItem("TAG1", 1000L)
        verify(registerAttendance, never()).invoke(any(), any(), any())
        verify(interactionProvider, never()).removeBacklogItem(any())

        // Cleanup still happens
        verify(interactionProvider).dismissActiveDialog()
        verify(refreshAttendanceList).invoke()
    }

    @Test
    fun `executeSequentialImport handles timeout`() = runTest {
        val item = BacklogItem("TAG1", null, 1000L)

        controller.executeSequentialImport(listOf(item))

        // Wait for 5s timeout
        advanceTimeBy(6000)
        advanceUntilIdle()

        verify(orchestrator).deleteBacklogItem("TAG1", 1000L)
        verify(registerAttendance, never()).invoke(any(), any(), any())

        // Cleanup still happens
        verify(interactionProvider).dismissActiveDialog()
        verify(refreshAttendanceList).invoke()
    }

    @Test
    fun `executeSequentialImport shows summary toast on success`() = runTest {
        val item1 = BacklogItem("TAG1", null, 1000L)
        val item2 = BacklogItem("TAG2", null, 2000L)

        whenever(interactionProvider.getString(eq(R.string.toast_imported_sessions), any()))
            .thenReturn("Imported 2 sessions")

        controller.executeSequentialImport(listOf(item1, item2))

        inventoryFlow.emit("DEL_OK" to 0L)
        inventoryFlow.emit("DEL_OK" to 0L)
        advanceUntilIdle()

        verify(interactionProvider).showToast("Imported 2 sessions")
    }
}
