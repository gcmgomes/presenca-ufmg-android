package com.example.presensor.communication.ble

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanCallback
import android.content.Context
import com.example.presensor.communication.core.TransportChannel
import com.example.presensor.communication.core.TransportConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private fun extractGattCallback(): BluetoothGattCallback {
        transport.connect("00:11:22:33:44:55")
        val captor = org.mockito.ArgumentCaptor.forClass(BluetoothGattCallback::class.java)
        verify(bluetoothDevice).connectGatt(any(), anyBoolean(), captor.capture())
        return captor.value
    }
}
