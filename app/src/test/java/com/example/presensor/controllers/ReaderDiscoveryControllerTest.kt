package com.example.presensor.controllers

import com.example.presensor.R
import com.example.presensor.communication.ReaderEvent
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.controllers.providers.ReaderInteractionProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDiscoveryControllerTest : BaseControllerTest() {

    private lateinit var controller: ReaderDiscoveryController
    private val mockSecureStore: SecureStoreManager = mock()
    private val mockOrchestrator: ReaderOrchestrator = mock()
    private val mockInteractionProvider = MockReaderInteractionProvider()

    private val eventFlow = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 1)
    private val isReaderEnabledFlow = MutableStateFlow(false)
    private val discoveredDevicesFlow =
        MutableStateFlow<List<com.example.presensor.communication.core.ReaderDevice>>(emptyList())
    private val isAuthenticatedFlow = MutableStateFlow(false)
    private val connectionStateFlow = MutableStateFlow(ReaderOrchestrator.ConnectionState.DISCONNECTED)

    @Before
    override fun setup() {
        super.setup()

        whenever(mockOrchestrator.eventFlow).thenReturn(eventFlow)
        whenever(mockOrchestrator.isReaderEnabled).thenReturn(isReaderEnabledFlow)
        whenever(mockOrchestrator.discoveredDevices).thenReturn(discoveredDevicesFlow)
        whenever(mockOrchestrator.isAuthenticated).thenReturn(isAuthenticatedFlow)
        whenever(mockOrchestrator.connectionState).thenReturn(connectionStateFlow)
        
        isReaderEnabledFlow.value = false
        isAuthenticatedFlow.value = false
        connectionStateFlow.value = ReaderOrchestrator.ConnectionState.DISCONNECTED
    }

    private fun TestScope.initController() {
        controller = ReaderDiscoveryController(
            secureStoreManager = mockSecureStore,
            interactionProvider = mockInteractionProvider,
            orchestrator = mockOrchestrator,
            scope = backgroundScope,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @Test
    fun `handleReaderSelection no password triggers prompt`() = runTest {
        initController()
        val readerName = "TestReader"
        val address = "00:11:22:33:44:55"
        whenever(mockSecureStore.getAuthPasswordFor(readerName)).thenReturn(null)

        controller.handleReaderSelection(readerName, address)

        assert(mockInteractionProvider.lastPasswordReaderName == readerName)
    }

    @Test
    fun `handleReaderSelection with password initiates connection`() = runTest {
        initController()
        val readerName = "TestReader"
        val address = "00:11:22:33:44:55"
        val password = "pass"
        whenever(mockSecureStore.getAuthPasswordFor(readerName)).thenReturn(password)

        controller.handleReaderSelection(readerName, address)

        verify(mockOrchestrator).startConnecting(
            eq(readerName),
            eq(password),
            eq(address),
            eq(true)
        )
        assert(mockInteractionProvider.lastToastResId == R.string.status_connecting)
    }

    @Test
    fun `teardownDiscovery fullDisconnect calls orchestrator disconnect`() = runTest {
        initController()
        controller.teardownDiscovery(fullDisconnect = true)
        verify(mockOrchestrator).disconnect()
    }

    @Test
    fun `setupReaderList registers listeners and updates state`() = runTest {
        isReaderEnabledFlow.value = true
        initController()

        controller.setupReaderList()

        assert(mockInteractionProvider.onReaderEnabledChanged != null)
        assert(mockInteractionProvider.onRefreshRequestedReader != null)
        verify(mockOrchestrator, atLeastOnce()).isReaderEnabled
    }

    @Test
    fun `onReaderEnabledChanged to true starts discovery and connection if password exists`() =
        runTest {
            initController()
            controller.setupReaderList()
            val readerName = "SavedReader"
            val password = "savedPassword"
            whenever(mockSecureStore.deviceName).thenReturn(readerName)
            whenever(mockSecureStore.getAuthPasswordFor(readerName)).thenReturn(password)

            mockInteractionProvider.onReaderEnabledChanged?.invoke(true)

            verify(mockOrchestrator).setReaderEnabled(true)
            verify(mockOrchestrator).startConnecting(
                eq(readerName),
                eq(password),
                anyOrNull(),
                any()
            )
        }

    @Test
    fun `onReaderEnabledChanged to true starts discovery if no password`() = runTest {
        initController()
        controller.setupReaderList()
        whenever(mockSecureStore.deviceName).thenReturn("SomeReader")
        whenever(mockSecureStore.getAuthPasswordFor(any())).thenReturn(null)

        mockInteractionProvider.onReaderEnabledChanged?.invoke(true)

        verify(mockOrchestrator).startScan()
        verify(mockOrchestrator).isBroadDiscoveryMode = true
    }

    @Test
    fun `onReaderEnabledChanged to false teardowns discovery`() = runTest {
        initController()
        controller.setupReaderList()

        mockInteractionProvider.onReaderEnabledChanged?.invoke(false)

        verify(mockOrchestrator).setReaderEnabled(false)
        verify(mockOrchestrator).disconnect()
    }

    @Test
    fun `onRefreshRequested starts discovery and stops refreshing after delay`() = runTest {
        initController()
        controller.setupReaderList()

        mockInteractionProvider.onRefreshRequestedReader?.invoke()

        verify(mockOrchestrator).startScan()
        advanceTimeBy(1001)
        runCurrent()
        assert(mockInteractionProvider.lastDiscoveryRefreshing == false)
    }

    @Test
    fun `eventJob updates UI when reader enabled state changes`() = runTest {
        initController()
        controller.setupReaderList()
        runCurrent()

        isReaderEnabledFlow.value = true
        runCurrent()
        assert(mockInteractionProvider.lastReaderEnabledState == true)

        isReaderEnabledFlow.value = false
        runCurrent()
        assert(mockInteractionProvider.lastReaderEnabledState == false)
        assert(mockInteractionProvider.lastConnectedList?.isEmpty() == true)
    }

    @Test
    fun `handleReaderEvent ConnectionSuccessful saves credentials and updates UI`() = runTest {
        initController()
        controller.setupReaderList()
        val readerName = "TestReader"
        val password = "password"
        whenever(mockSecureStore.getAuthPasswordFor(readerName)).thenReturn(null)

        // Trigger password prompt
        controller.handleReaderSelection(readerName, "00:11")
        mockInteractionProvider.onPasswordEntered?.invoke(password)

        // Simulate event
        eventFlow.emit(ReaderEvent.ConnectionSuccessful)
        runCurrent()

        verify(mockSecureStore).saveReaderCredentials(readerName, password)
        assert(mockInteractionProvider.lastToastResId == R.string.status_connected)
    }

    @Test
    fun `handleReaderEvent AuthenticationFailed shows error toast`() = runTest {
        initController()
        controller.setupReaderList()

        eventFlow.emit(ReaderEvent.AuthenticationFailed)
        runCurrent()

        assert(mockInteractionProvider.lastToastResId == R.string.error_incorrect_password)
    }

    @Test
    fun `handleReaderEvent Error shows timeout toast`() = runTest {
        initController()
        controller.setupReaderList()

        eventFlow.emit(ReaderEvent.Error("timeout"))
        runCurrent()

        assert(mockInteractionProvider.lastToastResId == R.string.toast_connection_timed_out)
    }

    @Test
    fun `handleReaderSelection for connected device disconnects`() = runTest {
        initController()
        val address = "00:11"
        whenever(mockOrchestrator.connectedDeviceAddress).thenReturn(address)
        isAuthenticatedFlow.value = true

        controller.handleReaderSelection("SomeReader", address)

        verify(mockOrchestrator).disconnect(disableAutoReconnect = true)
        assert(mockInteractionProvider.lastToastResId == R.string.status_disconnected)
    }

    @Test
    fun `handleReaderSelection password prompt onDismissed updates UI if disconnected`() = runTest {
        whenever(mockSecureStore.getAuthPasswordFor(any())).thenReturn(null)
        connectionStateFlow.value = ReaderOrchestrator.ConnectionState.DISCONNECTED
        isReaderEnabledFlow.value = true
        initController()
        controller.setupReaderList()

        controller.handleReaderSelection("Test", "00:11")
        mockInteractionProvider.onPasswordDismissed?.invoke()

        // Verify UI update (updateDeviceList calls interactionProvider.updateDeviceList)
        assert(mockInteractionProvider.lastUnknownList != null)
    }

    @Test
    fun `startRefreshLoop behavior`() = runTest {
        isReaderEnabledFlow.value = true
        initController()
        controller.setupReaderList() // This calls startRefreshLoop internally
        runCurrent()

        verify(mockOrchestrator, atLeastOnce()).startScan()
        verify(mockOrchestrator, atLeastOnce()).requestRssiUpdate()

        advanceTimeBy(20001)
        runCurrent()

        verify(mockOrchestrator, atLeast(2)).startScan()
        verify(mockOrchestrator, atLeast(2)).requestRssiUpdate()
    }

    @Test
    fun `startRefreshLoop requests status if authenticated`() = runTest {
        isReaderEnabledFlow.value = true
        isAuthenticatedFlow.value = true
        initController()
        controller.setupReaderList()
        runCurrent()

        verify(mockOrchestrator).requestStatus()

        advanceTimeBy(20001)
        runCurrent()

        verify(mockOrchestrator, atLeast(2)).requestStatus()
    }

    @Test
    fun `teardownDiscovery partialDisconnect stops scanning and sets idle mode`() = runTest {
        initController()
        controller.teardownDiscovery(fullDisconnect = false)

        verify(mockOrchestrator).isBroadDiscoveryMode = false
        verify(mockOrchestrator).stopScanning()
        verify(mockOrchestrator).setAppMode(
            com.example.presensor.communication.core.AppMode.IDLE,
            "Discovery Teardown"
        )
    }

    @Test
    fun `startDiscovery stops scanning after 5 seconds`() = runTest {
        isReaderEnabledFlow.value = true
        initController()
        controller.setupReaderList()
        runCurrent()

        verify(mockOrchestrator).startScan()

        advanceTimeBy(5001)
        runCurrent()

        verify(mockOrchestrator).stopScanning()
    }
}
