package com.example.presensor.controllers

import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.communication.core.AppMode
import com.example.presensor.communication.core.ReaderDevice
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.CoroutineScope
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
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderManagementControllerTest : BaseControllerTest() {

    private lateinit var controller: ReaderManagementController
    private val mockDb: AppDatabase = mock()
    private val mockSecureStore: SecureStoreManager = mock()
    private val mockOrchestrator: ReaderOrchestrator = mock()
    private val mockInteractionProvider = MockReaderInteractionProvider()

    private val connectionStateFlow =
        MutableStateFlow(ReaderOrchestrator.ConnectionState.DISCONNECTED)
    private val isAuthenticatedFlow = MutableStateFlow(false)
    private val metricsFlow = MutableSharedFlow<Pair<Long, Int>>()
    private val inventoryFlow = MutableSharedFlow<Pair<String, Long>>()
    private val discoveredDevicesFlow = MutableStateFlow<List<ReaderDevice>>(emptyList())

    @Before
    override fun setup() {
        super.setup()

        whenever(mockOrchestrator.connectionState).thenReturn(connectionStateFlow)
        whenever(mockOrchestrator.isAuthenticated).thenReturn(isAuthenticatedFlow)
        whenever(mockOrchestrator.metricsFlow).thenReturn(metricsFlow)
        whenever(mockOrchestrator.inventoryFlow).thenReturn(inventoryFlow)
        whenever(mockOrchestrator.discoveredDevices).thenReturn(discoveredDevicesFlow)
        whenever(mockSecureStore.deviceName).thenReturn("TestReader")

        controller = ReaderManagementController(
            db = mockDb,
            secureStoreManager = mockSecureStore,
            interactionProvider = mockInteractionProvider,
            orchestrator = mockOrchestrator,
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @Test
    fun `setupReaderManagementView initializes UI and collects flows`() = runTest {
        controller.setupReaderManagementView("00:11:22:33:44:55")

        assert(mockInteractionProvider.onEditDeviceRequested != null)
        assert(mockInteractionProvider.onSyncTimeRequested != null)

        // Test metricsFlow collection
        val now = System.currentTimeMillis() / 1000
        metricsFlow.emit(now to 85)

        val expectedTime =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now * 1000L))
        assert(mockInteractionProvider.lastHeaderBatteryLevel == "85%")
        assert(mockInteractionProvider.lastHeaderDeviceTime == expectedTime)
    }

    @Test
    fun `connection state changes update UI status`() = runTest {
        controller.setupReaderManagementView()

        // Connecting
        connectionStateFlow.value = ReaderOrchestrator.ConnectionState.CONNECTING
        assert(mockInteractionProvider.lastIsConnecting == true)
        assert(mockInteractionProvider.lastIsReady == false)

        // Connected but not authenticated
        connectionStateFlow.value = ReaderOrchestrator.ConnectionState.CONNECTED
        isAuthenticatedFlow.value = false
        assert(mockInteractionProvider.lastIsConnecting == true)
        assert(mockInteractionProvider.lastIsReady == false)

        // Fully ready
        isAuthenticatedFlow.value = true
        assert(mockInteractionProvider.lastIsConnecting == false)
        assert(mockInteractionProvider.lastIsReady == true)

        // Verify app mode set to management
        verify(mockOrchestrator).setAppMode(eq(AppMode.MANAGEMENT), any())
    }

    @Test
    fun `inventoryFlow SYNC_DONE ends refreshing`() = runTest {
        controller.setupReaderManagementView()

        inventoryFlow.emit("SYNC_DONE" to 0L)

        assert(mockInteractionProvider.lastIsManagementRefreshing == false)
    }

    @Test
    fun `inventoryFlow DEL_OK triggers toast and refresh`() = runTest {
        controller.setupReaderManagementView()

        inventoryFlow.emit("DEL_OK" to 0L)
        
        // Assert success toast immediately before timeout can overwrite it
        assert(mockInteractionProvider.lastToastResId == R.string.toast_backlog_deleted_success)
        
        advanceTimeBy(7000)
        advanceUntilIdle()

        verify(mockOrchestrator, atLeastOnce()).requestStatus()
    }

    @Test
    fun `inventoryFlow tag data adds items with student mapping`() = runTest {
        val tagIdRaw = "AABBCCDD"
        val tagIdFormatted = "AA:BB:CC:DD"
        val timestamp = 123456789L
        val mockStudent = Student("john@example.com", "John Doe", tagIdFormatted)

        whenever(mockDb.getStudentByRfid(tagIdFormatted)).thenReturn(mockStudent)

        controller.setupReaderManagementView()
        inventoryFlow.emit(tagIdRaw to timestamp)

        assert(mockInteractionProvider.lastBacklogItems?.size == 1)
        assert(mockInteractionProvider.lastBacklogItems?.first()?.student?.name == "John Doe")
    }

    @Test
    fun `inventoryFlow tag data adds items without student mapping`() = runTest {
        val tagIdRaw = "AABBCCDD"
        val tagIdFormatted = "AA:BB:CC:DD"

        whenever(mockDb.getStudentByRfid(tagIdFormatted)).thenReturn(null)

        controller.setupReaderManagementView()
        inventoryFlow.emit(tagIdRaw to 1000L)

        assert(mockInteractionProvider.lastBacklogItems?.size == 1)
        assert(mockInteractionProvider.lastBacklogItems?.first()?.student == null)
    }

    @Test
    fun `handleSyncTime requests time sync when authenticated`() {
        isAuthenticatedFlow.value = true
        controller.setupReaderManagementView()

        mockInteractionProvider.onSyncTimeRequested?.invoke()

        verify(mockOrchestrator).syncTime()
        assert(mockInteractionProvider.lastToastResId == R.string.action_sync_time)
    }

    @Test
    fun `handleForgetDevice disconnects and clears credentials`() {
        controller.setupReaderManagementView()

        mockInteractionProvider.onForgetDeviceRequested?.invoke()

        verify(mockOrchestrator).disconnect(eq(true))
        verify(mockSecureStore).clearCredentialsFor(any())
        assert(mockInteractionProvider.lastIsManagementRefreshing == false)
    }

    @Test
    fun `handleRefreshRequested refreshes data when authenticated`() = runTest {
        isAuthenticatedFlow.value = true
        controller.setupReaderManagementView()

        mockInteractionProvider.onRefreshRequested?.invoke()
        advanceTimeBy(7000)
        advanceUntilIdle()

        verify(mockOrchestrator, atLeastOnce()).requestStatus()
    }

    @Test
    fun `handleRefreshRequested toasts error when not authenticated`() {
        isAuthenticatedFlow.value = false
        controller.setupReaderManagementView()

        mockInteractionProvider.onRefreshRequested?.invoke()

        assert(mockInteractionProvider.lastToastResId == R.string.status_not_found)
        assert(mockInteractionProvider.lastIsManagementRefreshing == false)
    }

    @Test
    fun `handleDisconnect disconnects and resets UI`() {
        controller.setupReaderManagementView()

        mockInteractionProvider.onDisconnectRequested?.invoke()

        verify(mockOrchestrator).disconnect(eq(true))
        assert(mockInteractionProvider.lastIsManagementRefreshing == false)
    }

    @Test
    fun `handleConnect starts connecting if password exists`() {
        whenever(mockSecureStore.getAuthPasswordFor("TestReader")).thenReturn("pass123")
        whenever(mockOrchestrator.connectedDeviceAddress).thenReturn("AA:BB:CC")

        controller.setupReaderManagementView()

        mockInteractionProvider.onConnectRequested?.invoke()

        verify(mockOrchestrator).startConnecting(
            eq("TestReader"),
            eq("pass123"),
            eq("AA:BB:CC"),
            eq(true)
        )
    }

    @Test
    fun `handleEditDevice updates config and reboots`() {
        whenever(mockOrchestrator.connectedDeviceAddress).thenReturn("AA:BB:CC")
        controller.setupReaderManagementView()

        mockInteractionProvider.onEditDeviceRequested?.invoke()

        // Trigger dialog confirmation
        mockInteractionProvider.onConfigSaved?.invoke("NewName", "NewPass")

        verify(mockOrchestrator).updateReaderConfig("NewName", "NewPass")
        verify(mockSecureStore).saveReaderCredentials("NewName", "NewPass")
        verify(mockOrchestrator).rebootReader(eq("NewName"), eq("NewPass"), eq("AA:BB:CC"))
    }

    @Test
    fun `handleBacklogItemLongClick deletion confirmation`() {
        val item = BacklogItem("TAG123", null, 1000L)
        controller.setupReaderManagementView()

        mockInteractionProvider.onBacklogItemLongClicked?.invoke(item)

        assert(mockInteractionProvider.lastDestructiveTitle != null)

        // Confirm deletion
        mockInteractionProvider.onDestructiveConfirmed?.invoke()

        verify(mockOrchestrator).deleteBacklogItem("TAG123", 1000L)
    }

    @Test
    fun `handleBacklogItemLongClick with student name`() {
        val student = Student("test@test.com", "Jane Doe")
        val item = BacklogItem("TAG123", student, 1000L)
        controller.setupReaderManagementView()

        mockInteractionProvider.onBacklogItemLongClicked?.invoke(item)

        assert(mockInteractionProvider.lastDestructiveMessage?.contains("Jane Doe") == true)
    }

    @Test
    fun `teardownView cleans up resources`() {
        controller.setupReaderManagementView()
        controller.teardownView()

        verify(mockOrchestrator, atLeastOnce()).setAppMode(eq(AppMode.IDLE), any())
    }

    @Test
    fun `refreshManagementData handles timeout`() = runTest {
        isAuthenticatedFlow.value = true
        controller.setupReaderManagementView()

        // Trigger refresh
        mockInteractionProvider.onRefreshRequested?.invoke()

        // Advance time to trigger timeout (500 + 1000 + 5000 = 6500ms)
        advanceTimeBy(7000)

        assert(mockInteractionProvider.lastIsManagementRefreshing == false)
        assert(mockInteractionProvider.lastToastResId == R.string.toast_device_communication_time_out)
    }
}
