package com.example.presensor.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.presensor.rules.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ReaderManagerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var readerManager: ReaderManager
    private lateinit var testScope: TestScope
    private val testDispatcher = StandardTestDispatcher()

    private val bluetoothManager: BluetoothManager = mock()
    private val bluetoothAdapter: BluetoothAdapter = mock()
    private val bluetoothLeScanner: BluetoothLeScanner = mock()
    private val bluetoothGatt: BluetoothGatt = mock()
    private val secureStoreManager: com.example.presensor.data.SecureStoreManager = mock()

    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHAR_RFID_DATA_UUID = UUID.fromString("e3223119-9445-4e7d-be6d-2308c02c011e")
    private val CHAR_AUTH_UUID = UUID.fromString("f07b1d28-8681-4b13-91e8-6e54f7a7f6ff")
    private val CHAR_RFID_ACK_UUID = UUID.fromString("c485602d-1eb8-422f-981f-e053d71249b6")
    private val CHAR_INVENTORY_UUID = UUID.fromString("b59a681c-81db-4db6-9e96-a19f96da6041")

    @Before
    fun setup() {
        context = spy(ApplicationProvider.getApplicationContext<Context>())
        testScope = TestScope(testDispatcher)

        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(bluetoothManager)
        whenever(bluetoothManager.adapter).thenReturn(bluetoothAdapter)
        whenever(bluetoothAdapter.isEnabled).thenReturn(true)
        whenever(bluetoothAdapter.bluetoothLeScanner).thenReturn(bluetoothLeScanner)
        whenever(secureStoreManager.deviceName).thenReturn("Presensor_Reader")
        whenever(secureStoreManager.getAuthPasswordFor(any())).thenReturn("password123")

        readerManager = ReaderManager(
            context = context,
            secureStoreManager = secureStoreManager,
            scope = testScope,
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun startConnecting_transitionsToScanningAndStartsLeScan() {
        readerManager.startConnecting()
        
        assert(readerManager.connectionState.value == ReaderManager.ConnectionState.SCANNING)
        verify(bluetoothLeScanner).startScan(any(), any(), any<ScanCallback>())
    }

    @Test
    fun startConnecting_bluetoothDisabled_doesNothing() {
        whenever(bluetoothAdapter.isEnabled).thenReturn(false)
        
        readerManager.startConnecting()
        
        assert(readerManager.connectionState.value == ReaderManager.ConnectionState.DISCONNECTED)
        verifyNoInteractions(bluetoothLeScanner)
    }

    @Test
    fun onScanResult_transitionsToConnectingAndStartsGatt() {
        readerManager.startConnecting()
        
        val scanCallbackCaptor = argumentCaptor<ScanCallback>()
        verify(bluetoothLeScanner).startScan(any(), any(), scanCallbackCaptor.capture())
        
        val mockDevice: BluetoothDevice = mock()
        whenever(mockDevice.address).thenReturn("00:11:22:33:44:55")
        whenever(mockDevice.name).thenReturn("Presensor_Reader")
        
        val scanResult: android.bluetooth.le.ScanResult = mock()
        whenever(scanResult.device).thenReturn(mockDevice)
        
        scanCallbackCaptor.firstValue.onScanResult(0, scanResult)
        
        assert(readerManager.connectionState.value == ReaderManager.ConnectionState.CONNECTING)
        verify(mockDevice).connectGatt(eq(context), eq(false), any())
    }

    @Test
    fun onConnectionStateChange_connected_transitionsToConnectedAndDiscoversServices() {
        val gattCallback = readerManager.gattCallback
        
        gattCallback.onConnectionStateChange(bluetoothGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        
        assert(readerManager.connectionState.value == ReaderManager.ConnectionState.CONNECTED)
        verify(bluetoothGatt).discoverServices()
    }

    @Test
    fun onConnectionStateChange_disconnected_schedulesReconnect() = testScope.runTest {
        val gattCallback = readerManager.gattCallback
        
        // First get into a state where we can reconnect
        readerManager.startConnecting() 
        
        // Simulate stopScan during connect attempt (non-broad mode) to reset isScanning
        readerManager.stopScanning()

        gattCallback.onConnectionStateChange(bluetoothGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        
        assert(readerManager.connectionState.value == ReaderManager.ConnectionState.DISCONNECTED)
        
        // Wait 3 seconds
        advanceTimeBy(3001)
        advanceUntilIdle()
        
        // Should be scanning again
        assert(readerManager.connectionState.value == ReaderManager.ConnectionState.SCANNING)
    }

    @Test
    fun onServicesDiscovered_enablesNotificationsAndStartsHandshake() = testScope.runTest {
        val gattCallback = readerManager.gattCallback
        val mockService: BluetoothGattService = mock()
        val rfidChar: BluetoothGattCharacteristic = mock()
        val authChar: BluetoothGattCharacteristic = mock()
        val invChar: BluetoothGattCharacteristic = mock()
        val descriptor: BluetoothGattDescriptor = mock()
        
        whenever(rfidChar.uuid).thenReturn(CHAR_RFID_DATA_UUID)
        whenever(authChar.uuid).thenReturn(CHAR_AUTH_UUID)
        whenever(invChar.uuid).thenReturn(CHAR_INVENTORY_UUID)
        whenever(descriptor.uuid).thenReturn(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))

        readerManager.startConnecting()
        val scanCallbackCaptor = argumentCaptor<ScanCallback>()
        verify(bluetoothLeScanner).startScan(any(), any(), scanCallbackCaptor.capture())
        val mockDevice: BluetoothDevice = mock()
        whenever(mockDevice.name).thenReturn("Presensor_Reader")
        val scanResult: android.bluetooth.le.ScanResult = mock()
        whenever(scanResult.device).thenReturn(mockDevice)
        whenever(mockDevice.connectGatt(any(), any(), any())).thenReturn(bluetoothGatt)
        scanCallbackCaptor.firstValue.onScanResult(0, scanResult)

        whenever(bluetoothGatt.getService(any())).thenReturn(mockService)
        whenever(mockService.getCharacteristic(eq(CHAR_RFID_DATA_UUID))).thenReturn(rfidChar)
        whenever(mockService.getCharacteristic(eq(CHAR_AUTH_UUID))).thenReturn(authChar)
        whenever(mockService.getCharacteristic(eq(CHAR_INVENTORY_UUID))).thenReturn(invChar)
        whenever(rfidChar.getDescriptor(any())).thenReturn(descriptor)
        whenever(authChar.getDescriptor(any())).thenReturn(descriptor)
        whenever(invChar.getDescriptor(any())).thenReturn(descriptor)
        
        gattCallback.onServicesDiscovered(bluetoothGatt, BluetoothGatt.GATT_SUCCESS)
        
        verify(bluetoothGatt).setCharacteristicNotification(rfidChar, true)
        
        // Simulate RFID descriptor write success
        val rfidDescriptor: BluetoothGattDescriptor = mock()
        whenever(rfidDescriptor.characteristic).thenReturn(rfidChar)
        gattCallback.onDescriptorWrite(bluetoothGatt, rfidDescriptor, BluetoothGatt.GATT_SUCCESS)
        
        verify(bluetoothGatt).setCharacteristicNotification(authChar, true)

        // Simulate AUTH descriptor write success
        val authDescriptor: BluetoothGattDescriptor = mock()
        whenever(authDescriptor.characteristic).thenReturn(authChar)
        gattCallback.onDescriptorWrite(bluetoothGatt, authDescriptor, BluetoothGatt.GATT_SUCCESS)
        
        verify(bluetoothGatt).setCharacteristicNotification(invChar, true)
        
        // Simulate INVENTORY descriptor write success
        val invDescriptor: BluetoothGattDescriptor = mock()
        whenever(invDescriptor.characteristic).thenReturn(invChar)
        gattCallback.onDescriptorWrite(bluetoothGatt, invDescriptor, BluetoothGatt.GATT_SUCCESS)

        // Authentication challenge triggered after INVENTORY
        advanceTimeBy(301)
        advanceUntilIdle()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            verify(bluetoothGatt, atLeastOnce()).writeCharacteristic(eq(authChar), any(), any())
        } else {
            verify(bluetoothGatt, atLeastOnce()).writeCharacteristic(eq(authChar))
        }
    }

    @Test
    fun processIncomingData_emitsToFlowAndSendsAck() = testScope.runTest {
        val gattCallback = readerManager.gattCallback
        val data = "TAG123,1625097600".toByteArray()
        val mockChar: BluetoothGattCharacteristic = mock()
        val mockService: BluetoothGattService = mock()
        val ackChar: BluetoothGattCharacteristic = mock()
        
        whenever(mockChar.uuid).thenReturn(CHAR_RFID_DATA_UUID)
        whenever(ackChar.uuid).thenReturn(CHAR_RFID_ACK_UUID)

        // Set up bluetoothGatt inside ReaderManager
        readerManager.startConnecting()
        val scanCallbackCaptor = argumentCaptor<ScanCallback>()
        verify(bluetoothLeScanner).startScan(any(), any(), scanCallbackCaptor.capture())
        val mockDevice: BluetoothDevice = mock()
        whenever(mockDevice.name).thenReturn("Presensor_Reader")
        val scanResult: android.bluetooth.le.ScanResult = mock()
        whenever(scanResult.device).thenReturn(mockDevice)
        whenever(mockDevice.connectGatt(any(), any(), any())).thenReturn(bluetoothGatt)
        scanCallbackCaptor.firstValue.onScanResult(0, scanResult)

        whenever(bluetoothGatt.getService(any())).thenReturn(mockService)
        whenever(mockService.getCharacteristic(eq(CHAR_RFID_ACK_UUID))).thenReturn(ackChar)

        val rfidResults = mutableListOf<Pair<String, Long>>()
        val job = launch {
            readerManager.rfidSwipeFlow.collect { rfidResults.add(it) }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gattCallback.onCharacteristicChanged(bluetoothGatt, mockChar, data)
        } else {
            whenever(mockChar.value).thenReturn(data)
            gattCallback.onCharacteristicChanged(bluetoothGatt, mockChar)
        }
        
        // Wait for 50ms ACK delay
        advanceTimeBy(51)
        advanceUntilIdle()
        
        assert(rfidResults.size == 1)
        assert(rfidResults[0].first == "TAG123")
        assert(rfidResults[0].second == 1625097600L)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            verify(bluetoothGatt, atLeastOnce()).writeCharacteristic(eq(ackChar), any(), any())
        } else {
            verify(bluetoothGatt, atLeastOnce()).writeCharacteristic(eq(ackChar))
        }
        job.cancel()
    }

    @Test
    fun processIncomingData_doneSignal_emitsSyncDone() = testScope.runTest {
        val gattCallback = readerManager.gattCallback
        val data = "DONE".toByteArray()
        val rfidChar: BluetoothGattCharacteristic = mock()
        whenever(rfidChar.uuid).thenReturn(CHAR_RFID_DATA_UUID)
        
        val rfidResults = mutableListOf<Pair<String, Long>>()
        val job = launch {
            readerManager.rfidSwipeFlow.collect { rfidResults.add(it) }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gattCallback.onCharacteristicChanged(bluetoothGatt, rfidChar, data)
        } else {
            whenever(rfidChar.value).thenReturn(data)
            gattCallback.onCharacteristicChanged(bluetoothGatt, rfidChar)
        }
        
        advanceUntilIdle()
        
        assert(rfidResults.size == 1)
        assert(rfidResults[0].first == "SYNC_DONE")
        job.cancel()
    }

    @Test
    fun disconnect_cleansUpGattAndScanner() {
        readerManager.startConnecting()
        readerManager.disconnect()
        
        assert(readerManager.connectionState.value == ReaderManager.ConnectionState.DISCONNECTED)
        verify(bluetoothLeScanner).stopScan(any<ScanCallback>())
    }
}
