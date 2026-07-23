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
            scope = managerScope
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
    }
}
