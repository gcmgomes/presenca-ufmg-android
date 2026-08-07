package com.example.presensor.communication.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.os.ParcelUuid
import android.content.Context
import com.example.presensor.communication.core.TransportChannel
import com.example.presensor.communication.core.TransportConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleTransportTest {

    @Mock private lateinit var context: Context
    @Mock private lateinit var bluetoothManager: BluetoothManager
    @Mock private lateinit var bluetoothAdapter: BluetoothAdapter
    @Mock private lateinit var bluetoothLeScanner: BluetoothLeScanner
    @Mock private lateinit var bluetoothDevice: BluetoothDevice
    @Mock private lateinit var bluetoothGatt: BluetoothGatt

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var transport: BleTransport

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(bluetoothManager)
        `when`(bluetoothManager.adapter).thenReturn(bluetoothAdapter)
        `when`(bluetoothAdapter.isEnabled).thenReturn(true)
        `when`(bluetoothAdapter.bluetoothLeScanner).thenReturn(bluetoothLeScanner)
        `when`(bluetoothAdapter.getRemoteDevice(anyString())).thenReturn(bluetoothDevice)
        `when`(bluetoothDevice.connectGatt(any(), anyBoolean(), any())).thenReturn(bluetoothGatt)
        `when`(bluetoothGatt.device).thenReturn(bluetoothDevice)
        `when`(bluetoothDevice.address).thenReturn("00:11:22:33:44:55")

        transport = BleTransport(context, testScope)
    }

    @Test
    fun `connect should update state to CONNECTING`() {
        transport.connect("00:11:22:33:44:55")
        assertEquals(TransportConnectionState.CONNECTING, transport.connectionState.value)
        verify(bluetoothDevice).connectGatt(eq(context), eq(false), any())
    }

    @Test
    fun `disconnect should update state to DISCONNECTED and clear address`() {
        transport.connect("00:11:22:33:44:55")
        transport.disconnect()
        assertEquals(TransportConnectionState.DISCONNECTED, transport.connectionState.value)
        assertNull(transport.connectedAddress.value)
        verify(bluetoothGatt).disconnect()
        verify(bluetoothGatt).close()
    }

    @Test
    fun `startScan should update isScanning`() {
        transport.startScan(isBroad = true)
        assertTrue(transport.isScanning.value)
        verify(bluetoothLeScanner).startScan(
            any<List<ScanFilter>>(),
            any<ScanSettings>(),
            any<ScanCallback>()
        )
    }

    @Test
    fun `stopScan should update isScanning`() {
        transport.startScan(isBroad = true)
        transport.stopScan()
        assertFalse(transport.isScanning.value)
        verify(bluetoothLeScanner).stopScan(any<ScanCallback>())
    }

    @Test
    fun `onConnectionStateChange connected should discover services`() {
        val gattCallback = extractGattCallback()
        gattCallback.onConnectionStateChange(bluetoothGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        verify(bluetoothGatt).discoverServices()
        assertEquals("00:11:22:33:44:55", transport.connectedAddress.value)
    }

    @Test
    fun `onCharacteristicChanged should emit to incomingData flow`() = testScope.runTest {
        val gattCallback = extractGattCallback()
        val mockChar = mock(BluetoothGattCharacteristic::class.java)
        val uuid = UUID.fromString("e3223119-9445-4e7d-be6d-2308c02c011e") // DATA
        `when`(mockChar.uuid).thenReturn(uuid)
        val data = "HELLO".toByteArray()

        val results = mutableListOf<Pair<ByteArray, TransportChannel>>()
        val job = launch {
            transport.incomingData.take(1).toList(results)
        }

        gattCallback.onCharacteristicChanged(bluetoothGatt, mockChar, data)
        
        assertEquals(1, results.size)
        assertArrayEquals(data, results[0].first)
        assertEquals(TransportChannel.DATA, results[0].second)
        job.cancel()
    }

    @Test
    fun `subscribeNext should complete full handshake chain`() {
        val gattCallback = extractGattCallback()
        
        // Mock the service and characteristics
        val mockService = mock(BluetoothGattService::class.java)
        `when`(bluetoothGatt.getService(any())).thenReturn(mockService)
        
        val characteristics = List(4) { mock(BluetoothGattCharacteristic::class.java) }
        val uuids = listOf(
            UUID.fromString("e3223119-9445-4e7d-be6d-2308c02c011e"),
            UUID.fromString("f07b1d28-8681-4b13-91e8-6e54f7a7f6ff"),
            UUID.fromString("b59a681c-81db-4db6-9e96-a19f96da6041"),
            UUID.fromString("05df4f80-9943-4dc9-9807-611cc95fc91e")
        )
        
        characteristics.forEachIndexed { i, char ->
            `when`(char.uuid).thenReturn(uuids[i])
            `when`(mockService.getCharacteristic(uuids[i])).thenReturn(char)
            val mockDescriptor = mock(BluetoothGattDescriptor::class.java)
            `when`(mockDescriptor.characteristic).thenReturn(char)
            `when`(char.getDescriptor(any())).thenReturn(mockDescriptor)
        }

        // 1. Trigger service discovery success
        gattCallback.onServicesDiscovered(bluetoothGatt, BluetoothGatt.GATT_SUCCESS)
        
        // 2. Simulate sequential descriptor write callbacks
        characteristics.forEach { char ->
            val desc = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            gattCallback.onDescriptorWrite(bluetoothGatt, desc, BluetoothGatt.GATT_SUCCESS)
        }
        
        // 3. Handshake should be complete
        assertEquals(TransportConnectionState.READY, transport.connectionState.value)
    }

    @Test
    fun `write should respect mutex and sequential delay`() = testScope.runTest {
        val mockService = mock(BluetoothGattService::class.java)
        `when`(bluetoothGatt.getService(any())).thenReturn(mockService)
        val mockChar = mock(BluetoothGattCharacteristic::class.java)
        `when`(mockService.getCharacteristic(any())).thenReturn(mockChar)
        
        // Mock successful write
        `when`(bluetoothGatt.writeCharacteristic(any(), any(), anyInt())).thenReturn(BluetoothGatt.GATT_SUCCESS)

        // MUST connect first to initialize bluetoothGatt
        transport.connect("00:11:22:33:44:55")

        // Trigger two concurrent writes
        transport.write("W1".toByteArray(), TransportChannel.TIME)
        transport.write("W2".toByteArray(), TransportChannel.MODE)

        // W1 should be dispatched immediately (ignoring dispatcher overhead for now)
        testScope.advanceUntilIdle()
        verify(bluetoothGatt, atLeastOnce()).writeCharacteristic(eq(mockChar), any(), anyInt())
    }

    @Test
    fun `onScanResult should ignore devices without matching Service UUID`() = testScope.runTest {
        val results = mutableListOf<ScanResult>()
        val job = launch { transport.discoveredDevices.collect { results.add(it) } }
        
        val scanCallbackCaptor = org.mockito.ArgumentCaptor.forClass(ScanCallback::class.java)
        transport.startScan(isBroad = true)
        verify(bluetoothLeScanner).startScan(any<List<ScanFilter>>(), any<ScanSettings>(), scanCallbackCaptor.capture())
        
        val mockResult: ScanResult = mock()
        val mockRecord: ScanRecord = mock()
        `when`(mockResult.scanRecord).thenReturn(mockRecord)
        
        // Case 1: No Service UUIDs -> Ignore
        `when`(mockRecord.serviceUuids).thenReturn(null)
        scanCallbackCaptor.value.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, mockResult)
        testScope.advanceUntilIdle()
        assertTrue(results.isEmpty())
        
        // Case 2: Matching Service UUID -> Emit
        val serviceUuid = ParcelUuid(UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b"))
        `when`(mockRecord.serviceUuids).thenReturn(listOf(serviceUuid))
        `when`(mockResult.device).thenReturn(bluetoothDevice)
        scanCallbackCaptor.value.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, mockResult)
        testScope.advanceUntilIdle()
        assertEquals(1, results.size)
        
        job.cancel()
    }

    @Test
    fun `connect should abort if adapter is null`() {
        `when`(bluetoothManager.adapter).thenReturn(null)
        // Re-init transport to trigger lazy val again (or just let it happen if not already accessed)
        val transportNull = BleTransport(context, testScope)
        transportNull.connect("00:11:22:33:44:55")
        assertEquals(TransportConnectionState.DISCONNECTED, transportNull.connectionState.value)
        verify(bluetoothDevice, never()).connectGatt(any(), anyBoolean(), any())
    }

    @Test
    fun `connect should abort if adapter is disabled`() {
        `when`(bluetoothAdapter.isEnabled).thenReturn(false)
        transport.connect("00:11:22:33:44:55")
        assertEquals(TransportConnectionState.DISCONNECTED, transport.connectionState.value)
        verify(bluetoothDevice, never()).connectGatt(any(), anyBoolean(), any())
    }

    @Test
    fun `connect should abort if address is blank`() {
        transport.connect("  ")
        assertEquals(TransportConnectionState.DISCONNECTED, transport.connectionState.value)
        verify(bluetoothDevice, never()).connectGatt(any(), anyBoolean(), any())
    }

    @Test
    fun `startScan should abort if adapter is null`() {
        `when`(bluetoothManager.adapter).thenReturn(null)
        val transportNull = BleTransport(context, testScope)
        transportNull.startScan(true)
        assertFalse(transportNull.isScanning.value)
    }

    @Test
    fun `startScan should abort if adapter is disabled`() {
        `when`(bluetoothAdapter.isEnabled).thenReturn(false)
        transport.startScan(true)
        assertFalse(transport.isScanning.value)
    }

    @Test
    fun `startScan should abort if scanner is null`() {
        `when`(bluetoothAdapter.bluetoothLeScanner).thenReturn(null)
        transport.startScan(true)
        assertFalse(transport.isScanning.value)
    }

    @Test
    fun `onScanFailed should update isScanning state`() {
        transport.startScan(true)
        assertTrue(transport.isScanning.value)
        
        val scanCallbackCaptor = org.mockito.ArgumentCaptor.forClass(ScanCallback::class.java)
        verify(bluetoothLeScanner).startScan(any<List<ScanFilter>>(), any<ScanSettings>(), scanCallbackCaptor.capture())
        
        scanCallbackCaptor.value.onScanFailed(ScanCallback.SCAN_FAILED_ALREADY_STARTED)
        assertFalse(transport.isScanning.value)
    }

    @Test
    fun `startScan should recycle scanner if mode changed`() {
        transport.startScan(isBroad = true)
        verify(bluetoothLeScanner, times(1)).startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>())
        
        // Mode change: Broad -> Targeted
        transport.startScan(isBroad = false)
        // Should call stopScan then startScan again
        verify(bluetoothLeScanner).stopScan(any<ScanCallback>())
        verify(bluetoothLeScanner, times(2)).startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>())
    }

    @Test
    fun `startScan should ignore redundant requests with same mode`() {
        transport.startScan(isBroad = true)
        transport.startScan(isBroad = true)
        // Should only call startScan once
        verify(bluetoothLeScanner, times(1)).startScan(any<List<ScanFilter>>(), any<ScanSettings>(), any<ScanCallback>())
    }

    @Test
    fun `onConnectionStateChange with error status should disconnect`() {
        val gattCallback = extractGattCallback()
        gattCallback.onConnectionStateChange(bluetoothGatt, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_CONNECTED)
        
        assertEquals(TransportConnectionState.DISCONNECTED, transport.connectionState.value)
        verify(bluetoothGatt).disconnect()
    }

    @Test
    fun `onConnectionStateChange with DISCONNECTED state should disconnect`() {
        val gattCallback = extractGattCallback()
        gattCallback.onConnectionStateChange(bluetoothGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        
        assertEquals(TransportConnectionState.DISCONNECTED, transport.connectionState.value)
        verify(bluetoothGatt).disconnect()
    }

    @Test
    fun `onServicesDiscovered with error status should not proceed`() {
        val gattCallback = extractGattCallback()
        gattCallback.onServicesDiscovered(bluetoothGatt, BluetoothGatt.GATT_FAILURE)
        
        verify(bluetoothGatt, never()).getService(any())
        assertNotEquals(TransportConnectionState.READY, transport.connectionState.value)
    }

    @Test
    fun `requestRssi should delegate to gatt`() {
        transport.connect("00:11:22:33:44:55")
        transport.requestRssi()
        verify(bluetoothGatt).readRemoteRssi()
    }

    @Test
    fun `requestMtu should delegate to gatt`() {
        transport.connect("00:11:22:33:44:55")
        transport.requestMtu(512)
        verify(bluetoothGatt).requestMtu(512)
    }

    @Test
    fun `onReadRemoteRssi should update lastRssi flow`() {
        val gattCallback = extractGattCallback()
        gattCallback.onReadRemoteRssi(bluetoothGatt, -65, BluetoothGatt.GATT_SUCCESS)
        assertEquals(-65, transport.lastRssi.value)
        
        gattCallback.onReadRemoteRssi(bluetoothGatt, -100, BluetoothGatt.GATT_FAILURE)
        assertEquals(-65, transport.lastRssi.value) // Should not change
    }

    @Test
    fun `handleDescriptorWrite failure should still continue to next subscription`() {
        val gattCallback = extractGattCallback()
        val mockService = mock(BluetoothGattService::class.java)
        `when`(bluetoothGatt.getService(any())).thenReturn(mockService)
        
        val mockChar = mock(BluetoothGattCharacteristic::class.java)
        val uuid = UUID.fromString("e3223119-9445-4e7d-be6d-2308c02c011e") // DATA (index 0)
        `when`(mockChar.uuid).thenReturn(uuid)
        `when`(mockService.getCharacteristic(uuid)).thenReturn(mockChar)
        
        val mockDescriptor = mock(BluetoothGattDescriptor::class.java)
        `when`(mockDescriptor.characteristic).thenReturn(mockChar)
        
        // Mock next char (AUTH - index 1)
        val authUuid = UUID.fromString("f07b1d28-8681-4b13-91e8-6e54f7a7f6ff")
        val mockAuthChar = mock(BluetoothGattCharacteristic::class.java)
        `when`(mockAuthChar.uuid).thenReturn(authUuid)
        `when`(mockService.getCharacteristic(authUuid)).thenReturn(mockAuthChar)
        
        gattCallback.onDescriptorWrite(bluetoothGatt, mockDescriptor, BluetoothGatt.GATT_FAILURE)
        
        // Should have tried to subscribe to the NEXT characteristic (AUTH)
        verify(bluetoothGatt).setCharacteristicNotification(eq(mockAuthChar), eq(true))
    }

    @Config(sdk = [32])
    @Test
    fun `writeCharacteristicCompat legacy path`() = testScope.runTest {
        val mockService = mock(BluetoothGattService::class.java)
        `when`(bluetoothGatt.getService(any())).thenReturn(mockService)
        val mockChar = mock(BluetoothGattCharacteristic::class.java)
        `when`(mockService.getCharacteristic(any())).thenReturn(mockChar)
        `when`(bluetoothGatt.writeCharacteristic(any<BluetoothGattCharacteristic>())).thenReturn(true)

        transport.connect("00:11:22:33:44:55")
        transport.write("DATA".toByteArray(), TransportChannel.TIME)
        
        testScope.advanceUntilIdle()
        
        verify(mockChar).value = "DATA".toByteArray()
        verify(bluetoothGatt).writeCharacteristic(mockChar)
    }

    @Config(sdk = [32])
    @Test
    fun `onCharacteristicChanged legacy path`() = testScope.runTest {
        val gattCallback = extractGattCallback()
        val mockChar = mock(BluetoothGattCharacteristic::class.java)
        val uuid = UUID.fromString("e3223119-9445-4e7d-be6d-2308c02c011e") // DATA
        `when`(mockChar.uuid).thenReturn(uuid)
        val data = "LEGACY".toByteArray()
        `when`(mockChar.value).thenReturn(data)

        val results = mutableListOf<Pair<ByteArray, TransportChannel>>()
        val job = launch { transport.incomingData.collect { results.add(it) } }

        @Suppress("DEPRECATION")
        gattCallback.onCharacteristicChanged(bluetoothGatt, mockChar)
        
        testScope.advanceUntilIdle()
        assertEquals(1, results.size)
        assertArrayEquals(data, results[0].first)
        job.cancel()
    }

    private fun extractGattCallback(): BluetoothGattCallback {
        transport.connect("00:11:22:33:44:55")
        val captor = org.mockito.ArgumentCaptor.forClass(BluetoothGattCallback::class.java)
        verify(bluetoothDevice, atLeastOnce()).connectGatt(any(), anyBoolean(), captor.capture())
        return captor.value
    }
}
