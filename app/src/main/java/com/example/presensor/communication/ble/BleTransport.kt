package com.example.presensor.communication.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.presensor.communication.core.ReaderTransport
import com.example.presensor.communication.core.TransportChannel
import com.example.presensor.communication.core.TransportConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : ReaderTransport {

    companion object {
        private const val TAG = "BleTransport"

        private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val CHAR_TIME_SYNC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private val CHAR_RFID_DATA_UUID = UUID.fromString("e3223119-9445-4e7d-be6d-2308c02c011e")
        private val CHAR_RFID_ACK_UUID = UUID.fromString("c485602d-1eb8-422f-981f-e053d71249b6")
        private val CHAR_APP_MODE_UUID = UUID.fromString("a29a0912-32b0-4dbf-9b16-43e936526131")
        private val CHAR_AUTH_UUID = UUID.fromString("f07b1d28-8681-4b13-91e8-6e54f7a7f6ff")
        private val CHAR_CONFIG_UPDATE_UUID =
            UUID.fromString("d117c60e-744d-4475-b6d9-aa3cf047ee2d")
        private val CHAR_INVENTORY_UUID = UUID.fromString("b59a681c-81db-4db6-9e96-a19f96da6041")
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val CHANNEL_MAP = mapOf(
            CHAR_RFID_DATA_UUID to TransportChannel.DATA,
            CHAR_AUTH_UUID to TransportChannel.AUTH,
            CHAR_INVENTORY_UUID to TransportChannel.INVENTORY,
            CHAR_TIME_SYNC_UUID to TransportChannel.TIME,
            CHAR_RFID_ACK_UUID to TransportChannel.ACK,
            CHAR_APP_MODE_UUID to TransportChannel.MODE,
            CHAR_CONFIG_UPDATE_UUID to TransportChannel.CONFIG
        )

        private val UUID_MAP = CHANNEL_MAP.entries.associate { it.value to it.key }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanningInternal = false
    override val isScanning = MutableStateFlow(false)
    private var lastScanModeBroad: Boolean? = null

    private val _connectionState = MutableStateFlow(TransportConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<TransportConnectionState> = _connectionState

    private val _incomingData = MutableSharedFlow<Pair<ByteArray, TransportChannel>>(replay = 0)
    override val incomingData: SharedFlow<Pair<ByteArray, TransportChannel>> = _incomingData

    private val _discoveredDevices = MutableSharedFlow<ScanResult>(replay = 0)
    override val discoveredDevices: SharedFlow<ScanResult> = _discoveredDevices

    private val _lastRssi = MutableStateFlow<Int?>(null)
    override val lastRssi: StateFlow<Int?> = _lastRssi

    private val _connectedAddress = MutableStateFlow<String?>(null)
    override val connectedAddress: StateFlow<String?> = _connectedAddress

    private val gattMutex = Mutex()

    override fun connect(address: String) {
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "[connect] [CRITICAL] Failed: BluetoothAdapter is null")
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "[connect] [CRITICAL] Failed: Bluetooth is disabled")
            return
        }

        // --- STABILIZATION: Close any existing GATT before creating a new one ---
        bluetoothGatt?.let {
            Log.w(TAG, "[connect] Closing stale GATT instance before new connection to $address")
            it.disconnect()
            it.close()
        }

        val device = adapter.getRemoteDevice(address)
        Log.i(TAG, "[connect] Initiating GATT connection to $address")
        _connectionState.value = TransportConnectionState.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    override fun disconnect() {
        Log.i(TAG, "[disconnect] Explicitly requested. Tearing down GATT link.")
        bluetoothGatt?.let { gatt ->
            Log.d(TAG, "[disconnect] Closing GATT instance: $gatt")
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "[disconnect] Security exception closing GATT", e)
            }
        }
        bluetoothGatt = null
        // --- STABILIZATION: Clear MAC address before emitting DISCONNECTED ---
        _connectedAddress.value = null
        _connectionState.value = TransportConnectionState.DISCONNECTED
    }

    override fun startScan(isBroad: Boolean) {
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "[startScan] Failed: BluetoothAdapter is null")
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "[startScan] Failed: Bluetooth is disabled")
            return
        }

        // --- OPTIMIZATION: Only recycle if the mode actually changed ---
        if (isScanningInternal && lastScanModeBroad == isBroad) {
            Log.v(TAG, "[startScan] Scan already running in requested mode ($isBroad). Ignoring redundant request.")
            return
        }

        if (isScanningInternal) {
            Log.d(TAG, "[startScan] Mode change detected ($lastScanModeBroad -> $isBroad). Recycling scanner.")
            stopScan()
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "[startScan] Failed: BluetoothLeScanner is null")
            return
        }

        val filter = if (!isBroad) {
            Log.d(TAG, "[startScan] Targeted scan: filtering for Service $SERVICE_UUID")
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        } else {
            Log.d(TAG, "[startScan] Broad scan: showing all devices")
            null
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.i(TAG, "[startScan] Starting BLE scanner (Broad: $isBroad)")
        scanner.startScan(filter?.let { listOf(it) }, settings, scanCallback)
        isScanningInternal = true
        isScanning.value = true
        lastScanModeBroad = isBroad
    }

    override fun stopScan() {
        if (!isScanningInternal) {
            Log.v(TAG, "[stopScan] No active scan to stop.")
            return
        }
        Log.i(TAG, "[stopScan] Stopping BLE scanner")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanningInternal = false
        isScanning.value = false
        lastScanModeBroad = null
        reportedDevicesInCurrentScan.clear() // Clear for next cycle
    }

    override fun write(payload: ByteArray, channel: TransportChannel) {
        val gatt = bluetoothGatt ?: run {
            Log.e(TAG, "[write] Failed: bluetoothGatt is null (Channel: $channel)")
            return
        }
        val uuid = UUID_MAP[channel] ?: run {
            Log.e(TAG, "[write] Failed: No UUID mapping for Channel $channel")
            return
        }
        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.e(TAG, "[write] Failed: Service $SERVICE_UUID not found")
            return
        }
        val char = service.getCharacteristic(uuid) ?: run {
            Log.e(TAG, "[write] Failed: Characteristic $uuid not found on device")
            return
        }

        val writeType = if (channel == TransportChannel.ACK) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        // --- SEQUENTIAL WRITE QUEUE ---
        scope.launch {
            gattMutex.withLock {
                Log.d(TAG, "[write] Dispatching payload to $channel ($uuid). Size: ${payload.size} bytes.")
                writeCharacteristicCompat(gatt, char, payload, writeType)
                // Small breathing space between writes to prevent GATT congestion
                delay(150)
            }
        }
    }

    override fun requestRssi() {
        bluetoothGatt?.let {
            Log.v(TAG, "[requestRssi] Triggering remote RSSI read")
            it.readRemoteRssi()
        } ?: Log.w(TAG, "[requestRssi] Ignored: GATT is null")
    }

    override fun requestMtu(mtu: Int) {
        bluetoothGatt?.let {
            Log.i(TAG, "[requestMtu] Requesting MTU change to $mtu")
            it.requestMtu(mtu)
        } ?: Log.w(TAG, "[requestMtu] Ignored: GATT is null")
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(characteristic, payload, writeType)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(
                    TAG,
                    "[writeCharacteristicCompat] API 33+ write failed with status $status for ${characteristic.uuid}"
                )
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            val status = gatt.writeCharacteristic(characteristic)
            if (!status) {
                Log.e(
                    TAG,
                    "[writeCharacteristicCompat] Legacy write call returned false for ${characteristic.uuid}"
                )
            }
        }
    }

    private val reportedDevicesInCurrentScan = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceUuids = result.scanRecord?.serviceUuids

            // --- SECURITY/UI FILTER ---
            // Only emit devices that actually advertise our specific Service UUID.
            if (serviceUuids == null || !serviceUuids.contains(ParcelUuid(SERVICE_UUID))) {
                return
            }

            val name = result.scanRecord?.deviceName ?: result.device.name ?: "Unknown"
            val address = result.device.address
            
            // --- LOG OPTIMIZATION: Only log once per unique device during this scan window ---
            if (!reportedDevicesInCurrentScan.contains(address)) {
                Log.i(TAG, "[onScanResult] Match Found: $name [$address]. Emitting to flows.")
                reportedDevicesInCurrentScan.add(address)
            }

            scope.launch { _discoveredDevices.emit(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "[onScanFailed] BLE scan failed with error code $errorCode")
            isScanningInternal = false
            isScanning.value = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(
                    TAG,
                    "[onConnectionStateChange] GATT Error: status=$status, address=${gatt.device.address}"
                )
                disconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(
                    TAG,
                    "[onConnectionStateChange] CONNECTED to ${gatt.device.address}. Starting service discovery..."
                )
                _connectedAddress.value = gatt.device.address
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "[onConnectionStateChange] DISCONNECTED from ${gatt.device.address}")
                disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[onServicesDiscovered] Failed: status=$status")
                return
            }
            Log.i(TAG, "[onServicesDiscovered] Success. Initializing notification chain...")
            subscribeToAll(gatt)
        }

        private fun subscribeToAll(gatt: BluetoothGatt) {
            Log.d(TAG, "[subscribeToAll] Sequential characteristic subscription START")
            subscribeNext(gatt, 0)
        }

        private fun subscribeNext(gatt: BluetoothGatt, index: Int) {
            val uuids = listOf(
                CHAR_RFID_DATA_UUID,
                CHAR_AUTH_UUID,
                CHAR_INVENTORY_UUID
            )

            if (index >= uuids.size) {
                Log.i(TAG, "[subscribeNext] All channels READY. Handshake complete.")
                _connectionState.value = TransportConnectionState.READY
                return
            }

            val targetUuid = uuids[index]
            val service = gatt.getService(SERVICE_UUID) ?: run {
                Log.e(
                    TAG,
                    "[subscribeNext] Critical Error: Service $SERVICE_UUID missing during subscription"
                )
                return
            }
            val char = service.getCharacteristic(targetUuid) ?: run {
                Log.w(
                    TAG,
                    "[subscribeNext] Warning: Target char $targetUuid not found. Skipping to next."
                )
                subscribeNext(gatt, index + 1)
                return
            }

            Log.d(TAG, "[subscribeNext] Processing [$index/${uuids.size - 1}]: $targetUuid")
            gatt.setCharacteristicNotification(char, true)

            val descriptor = char.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (descriptor != null) {
                Log.v(TAG, "[subscribeNext] Writing CCCD descriptor for $targetUuid")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            } else {
                Log.w(
                    TAG,
                    "[subscribeNext] Warning: CCCD missing for $targetUuid. Forcing next step."
                )
                subscribeNext(gatt, index + 1)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.v(
                TAG,
                "[onDescriptorWrite] Callback received. Status: $status. Target: ${descriptor.characteristic.uuid}"
            )
            handleDescriptorWrite(gatt, descriptor, status)
        }

        private fun handleDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(
                    TAG,
                    "[handleDescriptorWrite] FAILED for ${descriptor.characteristic.uuid} with status $status"
                )
            } else {
                Log.v(TAG, "[handleDescriptorWrite] SUCCESS for ${descriptor.characteristic.uuid}")
            }

            val uuids = listOf(CHAR_RFID_DATA_UUID, CHAR_AUTH_UUID, CHAR_INVENTORY_UUID)
            val nextIndex = uuids.indexOf(descriptor.characteristic.uuid) + 1
            subscribeNext(gatt, nextIndex)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val channel = CHANNEL_MAP[characteristic.uuid] ?: return
            Log.v(TAG, "[onCharacteristicChanged] Incoming on $channel. Size: ${value.size} bytes")
            scope.launch { _incomingData.emit(value to channel) }
        }

        @Deprecated("Older SDK support")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                val channel = CHANNEL_MAP[characteristic.uuid] ?: return
                Log.v(
                    TAG,
                    "[onCharacteristicChanged-Legacy] Incoming on $channel. Size: ${value.size} bytes"
                )
                scope.launch { _incomingData.emit(value to channel) }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "[onReadRemoteRssi] Update: $rssi dBm")
                _lastRssi.value = rssi
            } else {
                Log.w(TAG, "[onReadRemoteRssi] Failed with status $status")
            }
        }
    }
}
