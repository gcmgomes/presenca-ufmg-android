package com.example.presensor.communication

import android.bluetooth.le.ScanResult
import com.example.presensor.communication.core.*
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.rules.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.mockito.kotlin.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderOrchestratorTest {

    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(15)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val secureStoreManager: SecureStoreManager = mock()
    private val transport = FakeReaderTransport()
    private val protocol: ReaderProtocol = mock()

    private lateinit var pAuth: MutableStateFlow<Boolean>
    private lateinit var pEvents: MutableSharedFlow<ProtocolEvent>

    private lateinit var readerOrchestrator: ReaderOrchestrator

    class FakeReaderTransport : ReaderTransport {
        var lastConnectedAddress: String? = null
        var stopScanCalled = false
        var disconnectCalled = false
        var startScanCalled = false
        var requestRssiCalled = false
        var requestMtuValue: Int? = null
        val writeCalls = mutableListOf<Pair<ByteArray, TransportChannel>>()

        override val connectionState = MutableStateFlow(TransportConnectionState.DISCONNECTED)
        override val isScanning = MutableStateFlow(false)
        override val incomingData = MutableSharedFlow<Pair<ByteArray, TransportChannel>>(replay = 0)
        override val discoveredDevices = MutableSharedFlow<ScanResult>(replay = 1)
        override val lastRssi = MutableStateFlow<Int?>(null)
        override val connectedAddress = MutableStateFlow<String?>(null)

        override fun connect(address: String) { 
            lastConnectedAddress = address
            connectedAddress.value = address 
        }
        override fun disconnect() { 
            disconnectCalled = true
            connectedAddress.value = null 
        }
        override fun startScan(isBroad: Boolean) { 
            startScanCalled = true
            isScanning.value = true 
        }
        override fun stopScan() { 
            stopScanCalled = true
            isScanning.value = false 
        }
        override fun write(payload: ByteArray, channel: TransportChannel) {
            writeCalls.add(payload to channel)
        }
        override fun requestRssi() { requestRssiCalled = true }
        override fun requestMtu(mtu: Int) { requestMtuValue = mtu }
        
        fun reset() {
            lastConnectedAddress = null
            stopScanCalled = false
            disconnectCalled = false
            startScanCalled = false
            requestRssiCalled = false
            requestMtuValue = null
            writeCalls.clear()
            connectionState.value = TransportConnectionState.DISCONNECTED
            isScanning.value = false
            connectedAddress.value = null
        }
    }

    @Before
    fun setup() {
        DialogFactory.resetForTesting()
        transport.reset()
        reset(protocol)
        
        pAuth = MutableStateFlow(false)
        pEvents = MutableSharedFlow(replay = 1)

        whenever(protocol.isAuthenticated).thenReturn(pAuth)
        whenever(protocol.domainEvents).thenReturn(pEvents)
        whenever(secureStoreManager.isReaderEnabled).thenReturn(true)
        
        whenever(protocol.formatStatusGetCommand()).thenReturn("STATUS".toByteArray())
        whenever(protocol.formatAppModeCommand(any())).thenReturn("MODE".toByteArray())
        whenever(protocol.formatTimeSyncCommand(any())).thenReturn("TIME".toByteArray())
        whenever(protocol.formatInventoryListCommand()).thenReturn("LIST".toByteArray())
        whenever(protocol.formatInventoryDeleteCommand(any(), any())).thenReturn("DEL".toByteArray())
        whenever(protocol.formatSyncCommand()).thenReturn("SYNC".toByteArray())
        whenever(protocol.formatAckCommand(any(), any())).thenReturn("ACK".toByteArray())
        whenever(protocol.formatConfigUpdateCommand(any(), any())).thenReturn("CONFIG".toByteArray())
    }

    private fun TestScope.createOrchestrator() {
        readerOrchestrator = ReaderOrchestrator(
            secureStoreManager = secureStoreManager,
            transport = transport,
            protocol = protocol,
            scope = backgroundScope,
            currentTimeMillis = { testScheduler.currentTime }
        )
    }

    @Test
    fun `connectionState should map READY and AUTH to CONNECTED`() = runTest {
        createOrchestrator()
        transport.connectionState.value = TransportConnectionState.READY
        pAuth.value = true
        runCurrent()
        assertEquals(ReaderOrchestrator.ConnectionState.CONNECTED, readerOrchestrator.connectionState.value)
    }

    @Test
    fun `startConnecting with MAC should trigger transport connect`() = runTest {
        createOrchestrator()
        val mac = "00:11:22:33:44:55"
        readerOrchestrator.startConnecting("Reader", "pass", mac)
        runCurrent()
        assertEquals(mac, transport.lastConnectedAddress)
        assertTrue(transport.stopScanCalled)
    }

    @Test
    fun `manual connection should time out after 5 seconds`() = runTest {
        createOrchestrator()
        val events = mutableListOf<ReaderEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            readerOrchestrator.eventFlow.collect { events.add(it) }
        }

        readerOrchestrator.startConnecting("Reader", "pass", "00:11:22:33:44:55", isManual = true)
        runCurrent()

        advanceTimeBy(4500.milliseconds)
        runCurrent()
        assertTrue("Should not time out before 5s", events.none { it is ReaderEvent.Error })

        advanceTimeBy(1000.milliseconds)
        runCurrent()
        
        assertTrue("Should have timed out", events.any { it is ReaderEvent.Error && it.message == ReaderOrchestrator.ERROR_TIMEOUT })
        assertTrue(transport.disconnectCalled)
    }

    @Test
    fun `startConnecting without MAC should trigger scan`() = runTest {
        createOrchestrator()
        readerOrchestrator.startConnecting("Reader", "pass", null)
        runCurrent()
        
        assertTrue("Should have called startScan", transport.startScanCalled)
        assertNull("Should not have called connect yet", transport.lastConnectedAddress)
    }

    @Test
    fun `background startConnecting should trigger targeted reconnection`() = runTest {
        whenever(secureStoreManager.deviceName).thenReturn("StoredReader")
        whenever(secureStoreManager.getAuthPasswordFor("StoredReader")).thenReturn("StoredPass")
        createOrchestrator()
        
        readerOrchestrator.startConnecting()
        runCurrent()
        
        verify(protocol).authPassword = "StoredPass"
        assertTrue("Should have triggered scan to find StoredReader", transport.startScanCalled)
    }

    @Test
    fun `Inventory events should propagate to inventoryFlow`() = runTest {
        createOrchestrator()
        val items = mutableListOf<Pair<String, Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            readerOrchestrator.inventoryFlow.collect { items.add(it) }
        }

        pEvents.emit(ProtocolEvent.InventoryItem("TAG1", 100L))
        runCurrent()
        pEvents.emit(ProtocolEvent.DeletionSuccess)
        runCurrent()
        pEvents.emit(ProtocolEvent.DeletionError)
        runCurrent()

        assertEquals(3, items.size)
        assertEquals("TAG1", items[0].first)
        assertEquals("DEL_OK", items[1].first)
        assertEquals("DEL_ERR", items[2].first)
    }

    @Test
    fun `protocol RfidSwipe should propagate to rfidSwipeFlow`() = runTest {
        createOrchestrator()
        val swipes = mutableListOf<Pair<String, Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            readerOrchestrator.rfidSwipeFlow.collect { swipes.add(it) }
        }

        pEvents.emit(ProtocolEvent.RfidSwipe("TAG_X", 999L))
        runCurrent()
        pEvents.emit(ProtocolEvent.SyncDone)
        runCurrent()

        assertEquals(2, swipes.size)
        assertEquals("TAG_X", swipes[0].first)
        assertEquals("SYNC_DONE", swipes[1].first)
    }

    @Test
    fun `AuthSuccess should trigger side effects after delays`() = runTest {
        createOrchestrator()
        pAuth.value = true
        runCurrent()

        pEvents.emit(ProtocolEvent.AuthSuccess)
        runCurrent()

        // 1. syncTime (500ms)
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertTrue("Should have sent TIME command", transport.writeCalls.any { String(it.first) == "TIME" })

        // 2. requestStatus (300ms)
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertTrue("Should have sent STATUS command", transport.writeCalls.any { String(it.first) == "STATUS" })

        // 3. setAppMode (300ms)
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertTrue("Should have sent MODE command", transport.writeCalls.any { String(it.first) == "MODE" })
    }

    @Test
    fun `Metrics event should update related state`() = runTest {
        createOrchestrator()
        val metrics = mutableListOf<Pair<Long, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            readerOrchestrator.metricsFlow.collect { metrics.add(it) }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            readerOrchestrator.discoveredDevices.collect {}
        }

        val mac = "00:11:22:33:44:55"
        transport.connectedAddress.value = mac
        
        val mockDevice: android.bluetooth.BluetoothDevice = mock { on { address } doReturn mac }
        val mockRecord: android.bluetooth.le.ScanRecord = mock { on { deviceName } doReturn "Reader" }
        val scanResult: android.bluetooth.le.ScanResult = mock {
            on { device } doReturn mockDevice
            on { scanRecord } doReturn mockRecord
            on { rssi } doReturn -60
        }
        transport.discoveredDevices.emit(scanResult)
        runCurrent()

        pEvents.emit(ProtocolEvent.Metrics(timestamp = 999L, batteryLevel = 80))
        runCurrent()

        assertEquals(1, metrics.size)
        assertEquals(999L, metrics[0].first)
        assertEquals(80, metrics[0].second)

        val deviceList = readerOrchestrator.discoveredDevices.value
        val device = deviceList.find { it.address == mac }
        assertNotNull("Device should be in discovery list", device)
        assertEquals(80, device!!.batteryLevel)
        assertEquals(999L, device.deviceEpoch)
    }

    @Test
    fun `rebootReader should wait 7 seconds before reconnecting`() = runTest {
        createOrchestrator()
        val mac = "00:11:22:33:44:55"
        readerOrchestrator.rebootReader("Reader", "pass", mac)
        runCurrent()

        assertTrue(readerOrchestrator.isRebooting.value)
        assertTrue("Should disconnect transport during reboot", transport.disconnectCalled)

        advanceTimeBy(6900.milliseconds)
        runCurrent()
        assertTrue("Should still be rebooting at 6.9s", readerOrchestrator.isRebooting.value)

        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertFalse("Should finish rebooting after 7s", readerOrchestrator.isRebooting.value)
        assertTrue("Should start scan to reconnect", transport.startScanCalled)
    }

    @Test
    fun `radio down should force auth reset`() = runTest {
        createOrchestrator()
        transport.connectionState.value = TransportConnectionState.READY
        pAuth.value = true
        runCurrent()
        assertTrue(readerOrchestrator.isAuthenticated.value)

        transport.connectionState.value = TransportConnectionState.DISCONNECTED
        runCurrent()

        verify(protocol).resetAuth()
    }

    @Test
    fun `setReaderEnabled(false) should disconnect and stop scanning`() = runTest {
        createOrchestrator()
        
        readerOrchestrator.setReaderEnabled(true)
        runCurrent()
        assertTrue(readerOrchestrator.isReaderEnabled.value)
        
        transport.connectionState.value = TransportConnectionState.READY
        runCurrent()
        
        readerOrchestrator.setReaderEnabled(false)
        runCurrent()
        
        assertFalse(readerOrchestrator.isReaderEnabled.value)
        assertTrue("Should disconnect when disabled", transport.disconnectCalled)
        assertTrue("Should stop scan when disabled", transport.stopScanCalled)
        verify(secureStoreManager).isReaderEnabled = false
    }

    @Test
    fun `intentional disconnect should skip connecting state`() = runTest {
        createOrchestrator()
        transport.connectionState.value = TransportConnectionState.READY
        pAuth.value = true
        runCurrent()

        readerOrchestrator.disconnect(disableAutoReconnect = false)
        runCurrent()
        
        assertTrue(readerOrchestrator.isIntentionalDisconnect.value)
        
        transport.connectionState.value = TransportConnectionState.DISCONNECTED
        runCurrent()

        assertEquals(ReaderOrchestrator.ConnectionState.DISCONNECTED, readerOrchestrator.connectionState.value)
    }

    @Test
    fun `peripheral commands should respect authentication`() = runTest {
        createOrchestrator()

        // Ensure NOT authenticated
        pAuth.value = false
        runCurrent()
        transport.writeCalls.clear()

        readerOrchestrator.setAppMode(AppMode.MANAGEMENT, "test")
        readerOrchestrator.requestStatus()
        runCurrent()
        
        assertTrue("Should NOT send commands while unauthenticated. Sent: ${transport.writeCalls.map { String(it.first) }}", transport.writeCalls.isEmpty())

        // Now authenticate
        pAuth.value = true
        runCurrent()
        
        // Clear side effects if any
        transport.writeCalls.clear()
        
        readerOrchestrator.setAppMode(AppMode.MANAGEMENT, "test")
        readerOrchestrator.requestStatus()
        runCurrent()
        
        assertTrue("Should send commands while authenticated", transport.writeCalls.size >= 2)
    }

    @Test
    fun `The Reaper should prune stale devices if not seen in a full cycle`() = runTest {
        createOrchestrator()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            readerOrchestrator.discoveredDevices.collect { }
        }

        val mac = "00:11:22:33:44:55"
        val mockDevice: android.bluetooth.BluetoothDevice = mock { on { address } doReturn mac }
        val mockRecord: android.bluetooth.le.ScanRecord = mock { on { deviceName } doReturn "D1" }
        val result: android.bluetooth.le.ScanResult = mock {
            on { device } doReturn mockDevice
            on { scanRecord } doReturn mockRecord
            on { rssi } doReturn -50
        }

        // 1. Cycle where device is seen
        transport.isScanning.value = true
        runCurrent()
        transport.discoveredDevices.emit(result)
        runCurrent()
        transport.isScanning.value = false
        runCurrent()

        assertFalse("Device should be in the list as Nearby", readerOrchestrator.discoveredDevices.value.isEmpty())

        // 2. Cycle where device is NOT seen
        transport.isScanning.value = true
        runCurrent()
        transport.isScanning.value = false
        runCurrent()

        assertTrue("Device should be pruned after a cycle of absence", readerOrchestrator.discoveredDevices.value.isEmpty())
    }

    @Test
    fun `AuthFailed should clear credentials and disconnect`() = runTest {
        whenever(secureStoreManager.deviceName).thenReturn("Reader")
        createOrchestrator()
        
        pEvents.emit(ProtocolEvent.AuthFailed)
        runCurrent()
        
        verify(secureStoreManager).clearCredentialsFor("Reader")
        assertTrue(transport.disconnectCalled)
    }

    @Test
    fun `Protocol AckRequired should trigger ACK write`() = runTest {
        createOrchestrator()
        
        pEvents.emit(ProtocolEvent.AckRequired("TAG1", "100"))
        runCurrent()
        
        assertTrue(transport.writeCalls.any { String(it.first) == "ACK" && it.second == TransportChannel.ACK })
    }

    @Test
    fun `updateReaderConfig should write to CONFIG channel`() = runTest {
        createOrchestrator()
        pAuth.value = true
        runCurrent()
        transport.writeCalls.clear()

        readerOrchestrator.updateReaderConfig("NewName", "NewPass")
        runCurrent()

        assertTrue(transport.writeCalls.any { String(it.first) == "CONFIG" && it.second == TransportChannel.CONFIG })
    }

    @Test
    fun `requestInventory should write LIST to INVENTORY channel`() = runTest {
        createOrchestrator()
        pAuth.value = true
        runCurrent()
        transport.writeCalls.clear()
        
        readerOrchestrator.requestInventory()
        runCurrent()
        
        assertTrue(transport.writeCalls.any { String(it.first) == "LIST" && it.second == TransportChannel.INVENTORY })
    }

    @Test
    fun `deleteBacklogItem should write DEL command`() = runTest {
        createOrchestrator()
        pAuth.value = true
        runCurrent()
        transport.writeCalls.clear()
        
        readerOrchestrator.deleteBacklogItem("TAG1", 12345)
        runCurrent()
        
        assertTrue(transport.writeCalls.any { String(it.first) == "DEL" && it.second == TransportChannel.INVENTORY })
    }

    @Test
    fun `requestBacklogSync should write SYNC command`() = runTest {
        createOrchestrator()
        pAuth.value = true
        runCurrent()
        transport.writeCalls.clear()
        
        readerOrchestrator.requestBacklogSync()
        runCurrent()
        
        assertTrue(transport.writeCalls.any { String(it.first) == "SYNC" && it.second == TransportChannel.MODE })
    }

    @Test
    fun `requestRssiUpdate should delegate to transport`() = runTest {
        createOrchestrator()
        readerOrchestrator.requestRssiUpdate()
        assertTrue(transport.requestRssiCalled)
    }

    @Test
    fun `Nearby status should be sticky during idle periods`() = runTest {
        createOrchestrator()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            readerOrchestrator.discoveredDevices.collect { }
        }

        val mac = "00:11:22:33:44:55"
        val mockDevice: android.bluetooth.BluetoothDevice = mock { on { address } doReturn mac }
        val mockRecord: android.bluetooth.le.ScanRecord = mock { on { deviceName } doReturn "D1" }
        val result: android.bluetooth.le.ScanResult = mock {
            on { device } doReturn mockDevice
            on { scanRecord } doReturn mockRecord
            on { rssi } doReturn -50
        }

        // 1. Seen in scan
        transport.isScanning.value = true
        runCurrent()
        transport.discoveredDevices.emit(result)
        runCurrent()
        transport.isScanning.value = false
        runCurrent()
        
        assertTrue(readerOrchestrator.discoveredDevices.value.any { it.address == mac && it.isNearby })

        // 2. Idle maintenance (scanning false)
        advanceTimeBy(5000.milliseconds)
        runCurrent()

        assertTrue("Should still be nearby in idle", readerOrchestrator.discoveredDevices.value.any { it.address == mac && it.isNearby })
    }

    @Test
    fun `isAutoReconnectedEnabled should default to true and change on disconnect`() = runTest {
        createOrchestrator()
        assertTrue(readerOrchestrator.isAutoReconnectedEnabled())
        
        readerOrchestrator.disconnect(disableAutoReconnect = true)
        runCurrent()
        assertFalse(readerOrchestrator.isAutoReconnectedEnabled())
    }

    @Test
    fun `stopScanning should delegate to transport`() = runTest {
        createOrchestrator()
        readerOrchestrator.stopScanning()
        assertTrue(transport.stopScanCalled)
    }

    @Test
    fun `isInManagementMode should reflect current mode`() = runTest {
        createOrchestrator()
        assertFalse(readerOrchestrator.isInManagementMode())
        
        pAuth.value = true
        runCurrent()
        readerOrchestrator.setAppMode(AppMode.MANAGEMENT, "test")
        runCurrent()
        assertTrue(readerOrchestrator.isInManagementMode())
        
        readerOrchestrator.setAppMode(AppMode.IDLE, "test")
        runCurrent()
        assertFalse(readerOrchestrator.isInManagementMode())
    }

    @Test
    fun `setBroadDiscoveryMode should update state`() = runTest {
        createOrchestrator()
        readerOrchestrator.isBroadDiscoveryMode = true
        assertTrue(readerOrchestrator.isBroadDiscoveryMode)
        readerOrchestrator.isBroadDiscoveryMode = false
        assertFalse(readerOrchestrator.isBroadDiscoveryMode)
    }

    @Test
    fun `lastConnectedRssi should reflect transport state`() = runTest {
        createOrchestrator()
        transport.lastRssi.value = -42
        runCurrent()
        assertEquals(-42, readerOrchestrator.lastConnectedRssi)
    }
}
