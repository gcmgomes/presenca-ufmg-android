package com.example.presensor.communication

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import com.example.presensor.communication.core.*
import com.example.presensor.data.SecureStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderManagerTest {

    private val secureStoreManager: SecureStoreManager = mock()
    private val transport: ReaderTransport = mock()
    private val protocol: ReaderProtocol = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val managerScope = CoroutineScope(testDispatcher)

    private val tConnectionState = MutableStateFlow(TransportConnectionState.DISCONNECTED)
    private val tIsScanning = MutableStateFlow(false)
    private val tIncomingData = MutableSharedFlow<Pair<ByteArray, TransportChannel>>(replay = 1)
    private val tDiscoveredDevices = MutableSharedFlow<ScanResult>(replay = 1)
    private val pIsAuthenticated = MutableStateFlow(false)
    private val pDomainEvents = MutableSharedFlow<ProtocolEvent>(replay = 1)

    private lateinit var readerManager: ReaderManager

    @Before
    fun setup() {
        whenever(transport.connectionState).thenReturn(tConnectionState)
        whenever(transport.isScanning).thenReturn(tIsScanning)
        whenever(transport.incomingData).thenReturn(tIncomingData)
        whenever(transport.lastRssi).thenReturn(MutableStateFlow(null))
        whenever(transport.connectedAddress).thenReturn(MutableStateFlow(null))
        whenever(transport.discoveredDevices).thenReturn(tDiscoveredDevices)

        whenever(protocol.isAuthenticated).thenReturn(pIsAuthenticated)
        whenever(protocol.domainEvents).thenReturn(pDomainEvents)

        readerManager = ReaderManager(
            secureStoreManager = secureStoreManager,
            transport = transport,
            protocol = protocol,
            scope = managerScope,
            currentTimeMillis = { testDispatcher.scheduler.currentTime }
        )
    }

    @After
    fun teardown() {
        managerScope.cancel()
    }

    @Test
    fun `connectionState should map Transport READY and Auth true to CONNECTED`() = testScope.runTest {
        tConnectionState.value = TransportConnectionState.READY
        pIsAuthenticated.value = true
        advanceUntilIdle()
        
        assertEquals(ReaderManager.ConnectionState.CONNECTED, readerManager.connectionState.value)
    }

    @Test
    fun `rfidSwipeFlow should emit when protocol emits RfidSwipe`() = testScope.runTest {
        val results = mutableListOf<Pair<String, Long>>()
        val job = launch {
            readerManager.rfidSwipeFlow.collect { results.add(it) }
        }

        pDomainEvents.emit(ProtocolEvent.RfidSwipe("TAG123", 12345L))
        advanceUntilIdle()
        
        assertEquals(1, results.size)
        assertEquals("TAG123", results[0].first)
        assertEquals(12345L, results[0].second)
        job.cancel()
    }

    @Test
    fun `discoveredDevices match should trigger transport connect in targeted mode`() = testScope.runTest {
        whenever(secureStoreManager.deviceName).thenReturn("TargetReader")
        readerManager.isBroadDiscoveryMode = false
        
        val mockDevice: android.bluetooth.BluetoothDevice = mock()
        whenever(mockDevice.address).thenReturn("00:11:22")
        val mockRecord: ScanRecord = mock()
        whenever(mockRecord.deviceName).thenReturn("TargetReader")
        
        val scanResult: ScanResult = mock()
        whenever(scanResult.device).thenReturn(mockDevice)
        whenever(scanResult.scanRecord).thenReturn(mockRecord)
        
        tDiscoveredDevices.emit(scanResult)
        advanceUntilIdle()
        
        verify(transport).connect("00:11:22")
        verify(transport).stopScan()
    }

    @Test
    fun `handleProtocolEvent AckRequired should write ACK command to transport`() = testScope.runTest {
        val mockAckData = "ACK_BYTES".toByteArray()
        whenever(protocol.formatAckCommand(any(), any())).thenReturn(mockAckData)
        
        pDomainEvents.emit(ProtocolEvent.AckRequired("TAG1", "123"))
        advanceUntilIdle()
        
        verify(protocol).formatAckCommand("TAG1", "123")
        verify(transport).write(eq(mockAckData), eq(TransportChannel.ACK))
    }

    @Test
    fun `setAppMode should write formatted command to transport`() = testScope.runTest {
        val mockData = "MODE_BYTES".toByteArray()
        whenever(protocol.formatAppModeCommand(any())).thenReturn(mockData)
        
        readerManager.setAppMode(AppMode.MANAGEMENT, "Test")
        advanceUntilIdle()
        
        verify(protocol).formatAppModeCommand(AppMode.MANAGEMENT)
        verify(transport).write(eq(mockData), eq(TransportChannel.MODE))
    }

    @Test
    fun `disconnect should reset state and stop scanning`() = testScope.runTest {
        readerManager.disconnect()
        advanceUntilIdle()
        
        verify(protocol).resetAuth()
        verify(transport).stopScan()
        verify(transport).disconnect()
        assertTrue(readerManager.isBroadDiscoveryMode)
    }

    @Test
    fun `setReaderEnabled false should disconnect transport`() = testScope.runTest {
        readerManager.setReaderEnabled(false)
        advanceUntilIdle()
        
        verify(transport).disconnect()
        verify(secureStoreManager).isReaderEnabled = false
        assertFalse(readerManager.isReaderEnabled.value)
    }

    @Test
    fun `discovery should be ignored when reader is disabled`() = testScope.runTest {
        readerManager.setReaderEnabled(false)
        advanceUntilIdle()
        
        val scanResult: ScanResult = mock()
        tDiscoveredDevices.emit(scanResult)
        advanceUntilIdle()
        
        // Should not trigger auto-connect logic even if it matches
        verify(secureStoreManager, never()).deviceName
        verify(transport, never()).connect(any())

        // Discovered devices list should be empty
        assertEquals(0, readerManager.discoveredDevices.value.size)
    }

    @Test
    fun `discoveredDevices flow should reflect transport discovery`() = testScope.runTest {
        val mockDevice: android.bluetooth.BluetoothDevice = mock()
        whenever(mockDevice.address).thenReturn("AA:BB:CC")
        val mockRecord: ScanRecord = mock()
        whenever(mockRecord.deviceName).thenReturn("TestDevice")
        
        val scanResult: ScanResult = mock()
        whenever(scanResult.device).thenReturn(mockDevice)
        whenever(scanResult.scanRecord).thenReturn(mockRecord)
        whenever(scanResult.rssi).thenReturn(-50)
        
        tDiscoveredDevices.emit(scanResult)
        advanceUntilIdle()
        
        val list = readerManager.discoveredDevices.value
        assertEquals(1, list.size)
        assertEquals("TestDevice", list[0].name)
        assertEquals("AA:BB:CC", list[0].address)
        assertEquals(-50, list[0].rssi)
    }

    @Test
    fun `discoveredDevices flow should update on first-hit per cycle`() = testScope.runTest {
        val results = mutableListOf<List<ReaderDevice>>()
        val job = launch {
            readerManager.discoveredDevices.collect { results.add(it) }
        }
        advanceUntilIdle() // Initial empty emission (results[0])

        // Start scan
        tIsScanning.value = true
        advanceUntilIdle()

        // Helper to emit a scan result
        fun emitDevice(name: String, address: String, rssi: Int) {
            val mockDevice: android.bluetooth.BluetoothDevice = mock()
            whenever(mockDevice.address).thenReturn(address)
            val mockRecord: ScanRecord = mock()
            whenever(mockRecord.deviceName).thenReturn(name)
            val scanResult: ScanResult = mock()
            whenever(scanResult.device).thenReturn(mockDevice)
            whenever(scanResult.scanRecord).thenReturn(mockRecord)
            whenever(scanResult.rssi).thenReturn(rssi)
            tDiscoveredDevices.tryEmit(scanResult)
        }

        // 1. First hit for D1 -> Should update UI
        emitDevice("D1", "A1", -50)
        advanceUntilIdle()
        assertEquals(2, results.size)
        assertEquals("D1", results[1][0].name)

        // 2. Second hit for D1 (with better RSSI) -> Should NOT update UI immediately
        emitDevice("D1", "A1", -40)
        advanceUntilIdle()
        assertEquals(2, results.size) // No change in flow emission

        // 3. First hit for D2 -> Should update UI
        emitDevice("D2", "A2", -60)
        advanceUntilIdle()
        assertEquals(3, results.size)
        assertEquals(2, results[2].size)

        // Stop scan -> Should trigger final snapshot (picking up the -40 RSSI for D1)
        tIsScanning.value = false
        advanceUntilIdle()
        
        assertEquals(4, results.size) 
        assertEquals(-40, results[3].find { it.address == "A1" }?.rssi)
        
        job.cancel()
    }

    @Test
    fun `The Reaper should prune devices after 3 seconds in snapshot`() = testScope.runTest {
        tIsScanning.value = true
        advanceUntilIdle()
        
        val mockDevice: android.bluetooth.BluetoothDevice = mock()
        whenever(mockDevice.address).thenReturn("A1")
        val mockRecord: ScanRecord = mock()
        whenever(mockRecord.deviceName).thenReturn("D1")
        val scanResult: ScanResult = mock()
        whenever(scanResult.device).thenReturn(mockDevice)
        whenever(scanResult.scanRecord).thenReturn(mockRecord)
        
        tDiscoveredDevices.emit(scanResult)
        
        testDispatcher.scheduler.advanceTimeBy(4000) // Device is now stale (> 3s)
        
        tIsScanning.value = false // Trigger snapshot
        advanceUntilIdle()
        
        assertTrue(readerManager.discoveredDevices.value.isEmpty()) // Should be pruned in the snapshot
    }

    @Test
    fun `startConnecting should time out after 5 seconds of active wait`() = testScope.runTest {
        val events = mutableListOf<ReaderEvent>()
        val job = launch {
            readerManager.eventFlow.collect { events.add(it) }
        }

        // Use isManual = true to trigger the error event
        readerManager.startConnecting("Test", "pass", "AA:BB:CC", isManual = true)
        
        // Advance 4 seconds -> No timeout yet
        testDispatcher.scheduler.advanceTimeBy(4000)
        assertTrue(events.isEmpty())

        // Advance 2 more seconds -> Should time out
        testDispatcher.scheduler.advanceTimeBy(2000)
        
        assertEquals(1, events.size)
        assertTrue(events[0] is ReaderEvent.Error)
        assertEquals(ReaderManager.ERROR_TIMEOUT, (events[0] as ReaderEvent.Error).message)
        
        // Should have disconnected
        verify(transport).disconnect()
        
        job.cancel()
    }
}
