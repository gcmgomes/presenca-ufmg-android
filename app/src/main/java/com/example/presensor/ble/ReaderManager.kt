package com.example.presensor.ble

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
import com.example.presensor.data.SecureStoreManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

sealed class ReaderEvent {
    object ConnectionSuccessful : ReaderEvent()
    object AuthenticationFailed : ReaderEvent()
    data class Error(val message: String) : ReaderEvent()
}

@SuppressLint("MissingPermission")
class ReaderManager(
    private val context: Context,
    private val secureStoreManager: SecureStoreManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    companion object {
        private const val TAG = "ReaderManager"

        // --- UUIDs matching the ESP32 configuration exactly ---
        private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val CHAR_TIME_SYNC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private val CHAR_RFID_DATA_UUID = UUID.fromString("e3223119-9445-4e7d-be6d-2308c02c011e")
        private val CHAR_RFID_ACK_UUID = UUID.fromString("c485602d-1eb8-422f-981f-e053d71249b6")
        private val CHAR_APP_MODE_UUID = UUID.fromString("a29a0912-32b0-4dbf-9b16-43e936526131")

        private val CHAR_AUTH_UUID = UUID.fromString("f07b1d28-8681-4b13-91e8-6e54f7a7f6ff")

        private val CHAR_CONFIG_UPDATE_UUID =
            UUID.fromString("d117c60e-744d-4475-b6d9-aa3cf047ee2d")

        private val CHAR_INVENTORY_UUID =
            UUID.fromString("b59a681c-81db-4db6-9e96-a19f96da6041")

        // Client Characteristic Configuration Descriptor (CCCD)
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var lastProcessedTag: String? = null
    private var lastProcessedTimestamp: String? = null
    var isBroadDiscoveryMode: Boolean = false
    private var isAuthenticationFailure = false
    private var isAutoReconnectEnabled = true

    // --- Targeted Connection Context (Captured at startConnecting call) ---
    private data class ConnectionContext(
        val deviceName: String,
        val passwordBytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ConnectionContext) return false
            if (deviceName != other.deviceName) return false
            return passwordBytes.contentEquals(other.passwordBytes)
        }

        override fun hashCode(): Int {
            var result = deviceName.hashCode()
            result = 31 * result + passwordBytes.contentHashCode()
            return result
        }
    }

    private var activeConnectionContext: ConnectionContext? = null

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }
    enum class AppMode { IDLE, ACTIVE, MANAGEMENT }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var currentAppMode = AppMode.IDLE
    private var pairingReceiver: BlePairingReceiver? = null
    var lastConnectedRssi: Int? = null
        private set
    private var isScanning = false
    var isAuthenticated = false
        private set

    var onDeviceFoundListener: ((ScanResult) -> Unit)? = null

    // --- Exposed Reactive States ---
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _rfidSwipeFlow = MutableSharedFlow<Pair<String, Long>>(replay = 0)
    val rfidSwipeFlow: SharedFlow<Pair<String, Long>> = _rfidSwipeFlow

    private val _inventoryFlow = MutableSharedFlow<Pair<String, Long>>(replay = 0)
    val inventoryFlow: SharedFlow<Pair<String, Long>> = _inventoryFlow

    private val _metricsFlow = MutableSharedFlow<Pair<Long, Int>>(replay = 0)
    val metricsFlow: SharedFlow<Pair<Long, Int>> = _metricsFlow

    private val _eventFlow = MutableSharedFlow<ReaderEvent>(replay = 0)
    val eventFlow: SharedFlow<ReaderEvent> = _eventFlow

    fun isInManagementMode(): Boolean = currentAppMode == ReaderManager.AppMode.MANAGEMENT

    /**
     * Initiates a targeted connection to a specific reader.
     * @param deviceName The name of the reader to target.
     * @param password The password to use for authentication.
     */
    fun startConnecting(deviceName: String, password: String) {
        val passBytes = password.toByteArray(Charsets.UTF_8)
        Log.i(
            TAG,
            "[Connect Flow] startConnecting() triggered for '$deviceName' (Pass length: ${passBytes.size})"
        )

        // 1. Reset all state for this fresh attempt
        isAuthenticated = false
        isAuthenticationFailure = false
        isAutoReconnectEnabled = true

        // 2. Capture the exact credentials into an immutable context for this lifecycle
        this.activeConnectionContext = ConnectionContext(deviceName, passBytes)

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "[Connect Flow Error] Bluetooth is disabled.")
            scope.launch { _eventFlow.emit(ReaderEvent.Error("Bluetooth is disabled")) }
            return
        }

        // 3. Register automated pairing handler
        pairingReceiver?.unregister(context)
        pairingReceiver = BlePairingReceiver(password).apply {
            Log.d(TAG, "[Connect Flow] Registering automated pairing receiver for '$deviceName'")
            register(context)
        }

        // 4. Cleanup any existing link if we are switching targets
        if (bluetoothGatt != null) {
            Log.i(TAG, "[Connect Flow] Active GATT detected. Disconnecting to target new reader.")
            disconnect()
        }

        if (_connectionState.value == ConnectionState.SCANNING ||
            _connectionState.value == ConnectionState.CONNECTING
        ) {
            Log.d(
                TAG,
                "[Connect Flow] Already in state ${_connectionState.value}. Ignoring request."
            )
            return
        }

        Log.i(TAG, "[Connect Flow] Initiating target search (Scanning)...")
        startScan()
    }

    /**
     * Internal/Auto-Reconnect version. Uses stored credentials.
     */
    fun startConnecting() {
        val name = secureStoreManager.deviceName
        val password = secureStoreManager.getAuthPasswordFor(name)

        if (password != null) {
            Log.i(TAG, "[Auto-Connect] Using stored credentials for '$name'")
            startConnecting(name, password)
        } else {
            Log.e(TAG, "[Auto-Connect] Failed: No stored password for '$name'")
        }
    }

    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "[Scan Error] BluetoothLeScanner is unavailable.")
            return
        }

        if (isScanning) {
            Log.d(TAG, "[Scan] Scan already in progress. Ignoring request.")
            return
        }

        if (_connectionState.value == ConnectionState.DISCONNECTED) {
            _connectionState.value = ConnectionState.SCANNING
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
        } catch (e: Exception) {
            Log.e(TAG, "[Scan Error] Failed to start scan: ${e.message}")
            isScanning = false
        }
    }

    fun setAppMode(mode: AppMode, caller: String) {
        currentAppMode = mode
        Log.i(TAG, "[Command] Requesting App Mode change to: $mode (Caller: $caller)")
        writeAppModeState(mode)
    }

    fun disconnect(disableAutoReconnect: Boolean = false) {
        Log.d(TAG, "[Disconnect] Explicitly requested. Tearing down connection.")
        if (disableAutoReconnect) {
            isAutoReconnectEnabled = false
        }
        stopScanning()

        pairingReceiver?.unregister(context)
        pairingReceiver = null
        isBroadDiscoveryMode = false
        isAuthenticated = false

        bluetoothGatt?.let { gatt ->
            Log.d(TAG, "[Cleanup] Explicitly disconnecting and closing GATT: $gatt")
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing permission to close GATT", e)
            }
        }
        bluetoothGatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun stopScanning() {
        Log.d(TAG, "[Stop Scan] Explicitly requested.")
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "[Stop Scan] Failed to stop scan safely: ${e.message}")
        }
        isScanning = false
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    val connectedDeviceAddress: String?
        get() = bluetoothGatt?.device?.address

    fun updateReaderConfig(newName: String, newPassword: String) {
        val gatt = bluetoothGatt ?: run {
            Log.e(TAG, "[Command Error] Cannot update config: bluetoothGatt is null")
            return
        }
        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.e(TAG, "[Command Error] Cannot update config: Service not found")
            return
        }
        val charConfig = service.getCharacteristic(CHAR_CONFIG_UPDATE_UUID) ?: run {
            Log.e(TAG, "[Command Error] Cannot update config: Characteristic missing")
            return
        }

        val payload = "$newName\t$newPassword"
        Log.i(
            TAG,
            "[Command] Sending Reader Config Update: '$newName' [Payload size: ${payload.length}]"
        )
        writeCharacteristicCompat(gatt, charConfig, payload.toByteArray(Charsets.UTF_8))
    }

    fun requestRssiUpdate() {
        bluetoothGatt?.readRemoteRssi()
    }

    fun syncTime() {
        val gatt = bluetoothGatt ?: run {
            Log.e(TAG, "[Command Error] Cannot sync time: bluetoothGatt is null")
            return
        }
        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.e(TAG, "[Command Error] Cannot sync time: Service not found")
            return
        }
        val charTimeSync = service.getCharacteristic(CHAR_TIME_SYNC_UUID) ?: run {
            Log.e(TAG, "[Command Error] Cannot sync time: Characteristic missing")
            return
        }

        val epochString = (System.currentTimeMillis() / 1000).toString()
        Log.i(TAG, "[Command] Sending Time Sync: $epochString")
        writeCharacteristicCompat(gatt, charTimeSync, epochString.toByteArray(Charsets.UTF_8))
    }

    fun requestInventory() {
        Log.i(TAG, "[Command] requestInventory() triggered.")
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.e(TAG, "[Command Error] Cannot request inventory: bluetoothGatt is null")
            return
        }
        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "[Command Error] Cannot request inventory: Service $SERVICE_UUID not found")
            return
        }
        val charInventory = service.getCharacteristic(CHAR_INVENTORY_UUID)
        if (charInventory == null) {
            Log.e(
                TAG,
                "[Command Error] Cannot request inventory: Characteristic $CHAR_INVENTORY_UUID not found"
            )
            return
        }

        Log.i(TAG, "[Command] Dispatching 'GET' to CHAR_INVENTORY...")
        writeCharacteristicCompat(gatt, charInventory, "GET".toByteArray(Charsets.UTF_8))
    }

    fun deleteBacklogItem(tagId: String, timestamp: Long) {
        Log.i(TAG, "[Command] deleteBacklogItem() triggered for $tagId at $timestamp")
        val gatt = bluetoothGatt ?: run {
            Log.e(TAG, "[Command Error] Cannot delete: bluetoothGatt is null")
            return
        }
        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.e(TAG, "[Command Error] Cannot delete: Service not found")
            return
        }
        val charInventory = service.getCharacteristic(CHAR_INVENTORY_UUID) ?: run {
            Log.e(TAG, "[Command Error] Cannot delete: Characteristic missing")
            return
        }

        // Format per ESP32 InventoryCallback: 'DEL,TagID,Timestamp'
        val cleanTagId = tagId.replace(":", "")
        val payload = "DEL,$cleanTagId,$timestamp"

        Log.i(TAG, "[Command] Dispatching deletion payload: '$payload'")
        writeCharacteristicCompat(gatt, charInventory, payload.toByteArray(Charsets.UTF_8))
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ) {
        val payloadStr = String(payload, Charsets.UTF_8)
        Log.d(TAG, "[GATT Write] Executing write to ${characteristic.uuid}. Payload: '$payloadStr'")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(characteristic, payload, writeType)
            if (result != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT Write Error] Tiramisu+ write failed with code $result")
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            val result = gatt.writeCharacteristic(characteristic)
            if (!result) {
                Log.e(TAG, "[GATT Write Error] Legacy write failed (returned false)")
            }
        }
    }

    private fun writeAppModeState(mode: AppMode) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val charAppMode = service.getCharacteristic(CHAR_APP_MODE_UUID) ?: return

        val payload = when (mode) {
            AppMode.IDLE -> "IDLE"
            AppMode.ACTIVE -> "ACTIVE"
            AppMode.MANAGEMENT -> "MANAGEMENT"
        }
        Log.i(TAG, "[Command] Writing App Mode payload: '$payload'")
        writeCharacteristicCompat(gatt, charAppMode, payload.toByteArray(Charsets.UTF_8))
    }

    private fun writeAckToEsp32(tagId: String, timestamp: String) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val charAck = service.getCharacteristic(CHAR_RFID_ACK_UUID) ?: return

        scope.launch {
            delay(50)
            val payload = "$tagId,$timestamp"
            Log.d(TAG, "[BLE Write-ACK] Sending handshake to ESP32: '$payload'")
            writeCharacteristicCompat(
                gatt,
                charAck,
                payload.toByteArray(Charsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        }
    }

    internal val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (_connectionState.value == ConnectionState.CONNECTING ||
                _connectionState.value == ConnectionState.CONNECTED
            ) {
                return
            }

            val device = result.device
            val advertisedName = result.scanRecord?.deviceName ?: device.name ?: "Unknown"

            // CRITICAL FIX: Only connect in targeted mode if we have a valid context
            val context = activeConnectionContext
            if (isBroadDiscoveryMode || context == null) {
                onDeviceFoundListener?.invoke(result)
                return
            }

            if (advertisedName != context.deviceName) {
                Log.v(
                    TAG,
                    "[Connect Flow] Scan mismatch: '$advertisedName' != '${context.deviceName}'"
                )
                return
            }

            Log.i(
                TAG,
                "[Connect Flow] MATCH FOUND! Targeting '$advertisedName' [${device.address}]"
            )
            _connectionState.value = ConnectionState.CONNECTING

            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                isScanning = false
            } catch (e: SecurityException) {
                Log.e(TAG, "[Security Exception] Scan permission missing during stop scan.", e)
            }

            bluetoothGatt = device.connectGatt(this@ReaderManager.context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "[Scan Failed] BLE scanner failed with code: $errorCode")
            isScanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scope.launch { _eventFlow.emit(ReaderEvent.Error("Scan failed: $errorCode")) }
        }
    }

    internal val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT Callback] Error: status = $status, newState = $newState")
                disconnect()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "[GATT Callback] Connected. Discovering services...")
                _connectionState.value = ConnectionState.CONNECTED
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "[GATT Callback] Disconnected.")
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security permission missing during callback close", e)
                }

                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }

                _connectionState.value = ConnectionState.DISCONNECTED

                if (isAuthenticationFailure || !isAutoReconnectEnabled) {
                    isAuthenticationFailure = false
                    isAutoReconnectEnabled = true
                    return
                }

                scope.launch {
                    delay(3000)
                    withContext(mainDispatcher) {
                        startConnecting()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            Log.i(TAG, "[GATT Callback] Services discovered. Initializing notification chain...")
            gatt.readRemoteRssi()
            proceedToNextSubscriptionStep(gatt, null)
        }

        private fun proceedToNextSubscriptionStep(gatt: BluetoothGatt, lastUuid: UUID?) {
            val service = gatt.getService(SERVICE_UUID) ?: return

            val nextUuid = when (lastUuid) {
                null -> CHAR_RFID_DATA_UUID
                CHAR_RFID_DATA_UUID -> CHAR_AUTH_UUID
                CHAR_AUTH_UUID -> CHAR_INVENTORY_UUID
                CHAR_INVENTORY_UUID -> {
                    triggerPasswordChallenge(gatt, service)
                    return
                }

                else -> return
            }

            val char = service.getCharacteristic(nextUuid)
            if (char != null) {
                Log.d(TAG, "[GATT Chain] Subscribing to $nextUuid...")
                subscribeToNotifications(gatt, char)
            } else {
                Log.w(TAG, "[GATT Chain] Optional characteristic $nextUuid missing. Skipping...")
                proceedToNextSubscriptionStep(gatt, nextUuid)
            }
        }

        private fun subscribeToNotifications(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic
        ) {
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (descriptor != null) {
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
                    "[GATT Chain] CCCD Descriptor missing for ${char.uuid}. Proceeding manually..."
                )
                proceedToNextSubscriptionStep(gatt, char.uuid)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(
                    TAG,
                    "[GATT Callback] Descriptor write failed for UUID: ${descriptor.uuid} with status $status"
                )
            }
            proceedToNextSubscriptionStep(gatt, descriptor.characteristic.uuid)
        }

        private fun triggerPasswordChallenge(gatt: BluetoothGatt, service: BluetoothGattService) {
            val authChar = service.getCharacteristic(CHAR_AUTH_UUID) ?: return
            val bytesToSend = activeConnectionContext?.passwordBytes ?: ByteArray(0)

            Log.i(TAG, "[Auth Flow] Submitting password challenge (${bytesToSend.size} bytes)...")

            scope.launch {
                delay(300)
                writeCharacteristicCompat(gatt, authChar, bytesToSend)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processIncomingData(value, characteristic.uuid)
        }

        @Deprecated("Used for older SDK compatibility")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                processIncomingData(characteristic.value, characteristic.uuid)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "[GATT] Remote RSSI update: $rssi dBm")
                lastConnectedRssi = rssi
            }
        }

        private fun processIncomingData(value: ByteArray?, charUuid: UUID) {
            val data = value?.toString(Charsets.UTF_8)?.replace("\u0000", "")?.trim() ?: return
            val instanceId = System.identityHashCode(this).toString(16).uppercase()
            Log.i(TAG, "[BLE Notification from $instanceId] Raw Payload: '$data' (from $charUuid)")

            if (charUuid == CHAR_AUTH_UUID) {
                if (data == "SUCCESS") {
                    Log.i(TAG, "[Auth] Device authenticated successfully!")
                    isAuthenticated = true
                    scope.launch {
                        _eventFlow.emit(ReaderEvent.ConnectionSuccessful)
                        syncTime()
                        delay(300)
                        writeAppModeState(currentAppMode)
                    }
                    return
                }
                if (data == "FAIL") {
                    Log.e(TAG, "[Auth Denied] Invalid password credential.")
                    isAuthenticationFailure = true
                    isAuthenticated = false
                    isAutoReconnectEnabled =
                        false // CRITICAL: Stop the "every other time" retry loop
                    scope.launch { _eventFlow.emit(ReaderEvent.AuthenticationFailed) }
                    secureStoreManager.clearCredentialsFor(secureStoreManager.deviceName)
                    disconnect()
                    return
                }
            }

            if (charUuid == CHAR_RFID_DATA_UUID || charUuid == CHAR_INVENTORY_UUID) {
                if (data == "DONE") {
                    Log.d(TAG, "ESP32 sent DONE signal.")
                    scope.launch {
                        if (charUuid == CHAR_RFID_DATA_UUID) {
                            _rfidSwipeFlow.emit(Pair("SYNC_DONE", 0L))
                        } else {
                            _inventoryFlow.emit(Pair("SYNC_DONE", 0L))
                        }
                    }
                    return
                }

                if (charUuid == CHAR_INVENTORY_UUID && data == "DEL_OK") {
                    Log.i(TAG, "[Management] Item deleted successfully from reader.")
                    scope.launch { _inventoryFlow.emit(Pair("DEL_OK", 0L)) }
                    return
                }

                if (charUuid == CHAR_INVENTORY_UUID && data == "DEL_ERR") {
                    Log.e(TAG, "[Management Error] Failed to delete item from reader.")
                    scope.launch { _inventoryFlow.emit(Pair("DEL_ERR", 0L)) }
                    return
                }

                if (charUuid == CHAR_INVENTORY_UUID && data.startsWith("INFO,")) {
                    val parts = data.split(",")
                    if (parts.size == 3) {
                        try {
                            val epoch = parts[1].trim().toLong()
                            val battery = parts[2].trim().toInt()
                            scope.launch { _metricsFlow.emit(Pair(epoch, battery)) }
                        } catch (e: Exception) {
                            Log.e(TAG, "[Parser Error] Failed parsing INFO frame: $data")
                        }
                    }
                    return
                }

                val parts = data.split(",")
                if (parts.size == 2) {
                    val tagId = parts[0].trim()
                    val timestampStr = parts[1].trim()
                    try {
                        val epochSec = timestampStr.toLong()

                        if (charUuid == CHAR_RFID_DATA_UUID) {
                            if (tagId == lastProcessedTag && timestampStr == lastProcessedTimestamp) {
                                writeAckToEsp32(tagId, timestampStr)
                                return
                            }
                            lastProcessedTag = tagId
                            lastProcessedTimestamp = timestampStr
                            scope.launch { _rfidSwipeFlow.emit(Pair(tagId, epochSec)) }
                            writeAckToEsp32(tagId, timestampStr)
                        } else {
                            scope.launch { _inventoryFlow.emit(Pair(tagId, epochSec)) }
                        }
                    } catch (e: NumberFormatException) {
                        Log.e(
                            TAG,
                            "[Parser Error] Failed parsing timestamp: '$timestampStr' in data '$data'"
                        )
                    }
                }
            }
        }
    }

    fun requestBacklogSync() {
        val gatt = bluetoothGatt
        if (gatt == null || _connectionState.value != ConnectionState.CONNECTED) return
        val service = gatt.getService(SERVICE_UUID)
        val charAppMode = service?.getCharacteristic(CHAR_APP_MODE_UUID)
        if (charAppMode != null) {
            Log.i(TAG, "[Sync] Requesting SYNC...")
            writeCharacteristicCompat(gatt, charAppMode, "SYNC".toByteArray(Charsets.UTF_8))
        }
    }
}
