package com.example.presensor.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
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

    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHAR_RFID_DATA_UUID = UUID.fromString("e3223119-9445-4e7d-be6d-2308c02c011e")

    @Before
    fun setup() {
        context = spy(ApplicationProvider.getApplicationContext<Context>())
        testScope = TestScope(testDispatcher)

        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(bluetoothManager)
        whenever(bluetoothManager.adapter).thenReturn(bluetoothAdapter)
        whenever(bluetoothAdapter.isEnabled).thenReturn(true)
        whenever(bluetoothAdapter.bluetoothLeScanner).thenReturn(bluetoothLeScanner)

        readerManager = ReaderManager(
            context = context,
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
        val mockChar: BluetoothGattCharacteristic = mock()
        
        // We need to set bluetoothGatt in ReaderManager for syncSystemTime to work
        // ReaderManager sets it in onScanResult, but we can't easily set it here.
        // Actually ReaderManager uses its internal bluetoothGatt variable.
        // Let's look at startConnecting -> onScanResult
        
        readerManager.startConnecting()
        val scanCallbackCaptor = argumentCaptor<ScanCallback>()
        verify(bluetoothLeScanner).startScan(any(), any(), scanCallbackCaptor.capture())
        val mockDevice: BluetoothDevice = mock()
        val scanResult: android.bluetooth.le.ScanResult = mock()
        whenever(scanResult.device).thenReturn(mockDevice)
        whenever(mockDevice.connectGatt(any(), any(), any())).thenReturn(bluetoothGatt)
        scanCallbackCaptor.firstValue.onScanResult(0, scanResult)

        whenever(bluetoothGatt.getService(any())).thenReturn(mockService)
        whenever(mockService.getCharacteristic(any())).thenReturn(mockChar)
        
        gattCallback.onServicesDiscovered(bluetoothGatt, BluetoothGatt.GATT_SUCCESS)
        
        verify(bluetoothGatt).setCharacteristicNotification(mockChar, true)
        
        // Handshake sequence
        advanceTimeBy(601)
        advanceUntilIdle()
        
        // Verify writeCharacteristic
        verify(bluetoothGatt, atLeastOnce()).writeCharacteristic(eq(mockChar))
        
        advanceTimeBy(301)
        advanceUntilIdle()
        verify(bluetoothGatt, atLeast(2)).writeCharacteristic(eq(mockChar))
    }

    @Test
    fun processIncomingData_emitsToFlowAndSendsAck() = testScope.runTest {
        val gattCallback = readerManager.gattCallback
        val data = "TAG123,1625097600".toByteArray()
        val mockChar: BluetoothGattCharacteristic = mock()
        val mockService: BluetoothGattService = mock()
        
        // Set up bluetoothGatt inside ReaderManager
        readerManager.startConnecting()
        val scanCallbackCaptor = argumentCaptor<ScanCallback>()
        verify(bluetoothLeScanner).startScan(any(), any(), scanCallbackCaptor.capture())
        val mockDevice: BluetoothDevice = mock()
        val scanResult: android.bluetooth.le.ScanResult = mock()
        whenever(scanResult.device).thenReturn(mockDevice)
        whenever(mockDevice.connectGatt(any(), any(), any())).thenReturn(bluetoothGatt)
        scanCallbackCaptor.firstValue.onScanResult(0, scanResult)

        whenever(bluetoothGatt.getService(any())).thenReturn(mockService)
        whenever(mockService.getCharacteristic(any())).thenReturn(mockChar)

        val rfidResults = mutableListOf<Pair<String, Long>>()
        val job = launch {
            readerManager.rfidSwipeFlow.collect { rfidResults.add(it) }
        }

        val charForNotification: BluetoothGattCharacteristic = mock()
        whenever(charForNotification.value).thenReturn(data)
        
        gattCallback.onCharacteristicChanged(bluetoothGatt, charForNotification)
        
        advanceUntilIdle()
        
        assert(rfidResults.size == 1)
        assert(rfidResults[0].first == "TAG123")
        assert(rfidResults[0].second == 1625097600L)
        
        verify(bluetoothGatt, atLeastOnce()).writeCharacteristic(eq(mockChar))
        job.cancel()
    }

    @Test
    fun processIncomingData_doneSignal_emitsSyncDone() = testScope.runTest {
        val gattCallback = readerManager.gattCallback
        val data = "DONE".toByteArray()
        val charForNotification: BluetoothGattCharacteristic = mock()
        whenever(charForNotification.value).thenReturn(data)
        
        val rfidResults = mutableListOf<Pair<String, Long>>()
        val job = launch {
            readerManager.rfidSwipeFlow.collect { rfidResults.add(it) }
        }

        gattCallback.onCharacteristicChanged(bluetoothGatt, charForNotification)
        
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
