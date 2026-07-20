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
    private var lastProcessedTime: Long = 0
    var isBroadDiscoveryMode: Boolean = false
    private var isAuthenticationFailure = false
    private var isAutoReconnectEnabled = true

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var isAppActiveState = false
    private var pairingReceiver: BlePairingReceiver? = null
    var lastConnectedRssi: Int? = null
        private set
    private var isScanning = false

    var onDeviceFoundListener: ((ScanResult) -> Unit)? = null

    // --- Exposed Reactive States ---
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _rfidSwipeFlow = MutableSharedFlow<Pair<String, Long>>(replay = 0)
    val rfidSwipeFlow: SharedFlow<Pair<String, Long>> = _rfidSwipeFlow

    private val _inventoryFlow = MutableSharedFlow<Pair<String, Long>>(replay = 0)
    val inventoryFlow: SharedFlow<Pair<String, Long>> = _inventoryFlow

    private val _eventFlow = MutableSharedFlow<ReaderEvent>(replay = 0)
    val eventFlow: SharedFlow<ReaderEvent> = _eventFlow

    // --- Public API ---
    fun startConnecting() {
        isAuthenticationFailure = false
        isAutoReconnectEnabled = true
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "[Init Error] Bluetooth is disabled or not supported.")
            scope.launch { _eventFlow.emit(ReaderEvent.Error("Bluetooth is disabled")) }
            return
        }

        // --- Hook up Pairing Receiver for automated bonding ---
        if (!isBroadDiscoveryMode) {
            val password = secureStoreManager.getAuthPasswordFor(secureStoreManager.deviceName)
            if (password != null) {
                pairingReceiver?.unregister(context)
                pairingReceiver = BlePairingReceiver(password).apply {
                    Log.d(TAG, "[Pairing] Registering automated pairing receiver for device.")
                    register(context)
                }
            }
        }

        // CRITICAL: Only clean up if we are NOT in broad discovery mode.
        // If we are looking for a specific target, we reset the link.
        if (bluetoothGatt != null && !isBroadDiscoveryMode) {
            Log.d(TAG, "[Scan Prep] Cleaning up old GATT reference for targeted connection.")
            disconnect()
        }

        if (_connectionState.value == ConnectionState.SCANNING ||
            _connectionState.value == ConnectionState.CONNECTING ||
            (_connectionState.value == ConnectionState.CONNECTED && !isBroadDiscoveryMode)
        ) {
            return
        }

        startScan()
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

        Log.d(TAG, "[Scan] Starting BLE scanner. Broad Discovery: $isBroadDiscoveryMode")

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

    fun setAppActive(active: Boolean) {
        isAppActiveState = active
        Log.d(TAG, "[App Mode Change] Updating app readiness state to: $active")
        writeAppModeState(active)
    }

    fun disconnect(disableAutoReconnect: Boolean = false) {
        Log.d(TAG, "[Disconnect] Explicitly requested. Tearing down connection.")
        if (disableAutoReconnect) {
            isAutoReconnectEnabled = false
        }
        stopScanning()

        pairingReceiver?.unregister(context)
        pairingReceiver = null

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
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val charConfig = service.getCharacteristic(CHAR_CONFIG_UPDATE_UUID) ?: return

        val payload = "$newName\t$newPassword"
        Log.d(TAG, "[BLE Write] Sending Config Update to ESP32: '$newName' (password hidden)")
        writeCharacteristicCompat(gatt, charConfig, payload.toByteArray(Charsets.UTF_8))
    }

    fun requestRssiUpdate() {
        bluetoothGatt?.readRemoteRssi()
    }

    fun requestInventory() {
        Log.i(TAG, "[Inventory] requestInventory() called.")
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.e(TAG, "[Inventory Error] Cannot request: bluetoothGatt is null")
            return
        }
        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "[Inventory Error] Cannot request: Service $SERVICE_UUID not found")
            return
        }
        val charInventory = service.getCharacteristic(CHAR_INVENTORY_UUID)
        if (charInventory == null) {
            Log.e(TAG, "[Inventory Error] Cannot request: Characteristic $CHAR_INVENTORY_UUID not found in service")
            // Log all available characteristics for debugging
            service.characteristics.forEach { 
                Log.d(TAG, "  Found characteristic: ${it.uuid}")
            }
            return
        }

        Log.i(TAG, "[Inventory] Sending 'GET' command to ESP32...")
        writeCharacteristicCompat(gatt, charInventory, "GET".toByteArray(Charsets.UTF_8))
    }

    // --- Modern, Safe Write Helper ---
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ modern API
            gatt.writeCharacteristic(
                characteristic,
                payload,
                writeType
            )
        } else {
            // Deprecated fallback for older Android versions
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun writeAppModeState(active: Boolean) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val charAppMode = service.getCharacteristic(CHAR_APP_MODE_UUID) ?: return

        val payload = if (active) "1" else "0"
        Log.d(TAG, "[BLE Write] Setting ESP32 CHAR_APP_MODE to '$payload'")
        writeCharacteristicCompat(gatt, charAppMode, payload.toByteArray(Charsets.UTF_8))
    }

    private fun syncSystemTime() {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val charTimeSync = service.getCharacteristic(CHAR_TIME_SYNC_UUID) ?: return

        val epochString = (System.currentTimeMillis() / 1000).toString()
        Log.d(TAG, "[BLE Write] Sending Time Sync to ESP32: $epochString")
        writeCharacteristicCompat(gatt, charTimeSync, epochString.toByteArray(Charsets.UTF_8))
    }

    private fun writeAckToEsp32(tagId: String, timestamp: String) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val charAck = service.getCharacteristic(CHAR_RFID_ACK_UUID) ?: return

        // Dispatch to background and add a small delay to avoid GATT congestion/collision
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

    // --- BLE Scan Callback ---
    internal val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (_connectionState.value == ConnectionState.CONNECTING ||
                _connectionState.value == ConnectionState.CONNECTED
            ) {
                return
            }

            val device = result.device
            val advertisedName = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
            val targetDeviceName = secureStoreManager.deviceName

            // 1. Report the found device to your dialog list right away
            onDeviceFoundListener?.invoke(result)
            Log.d(
                TAG,
                "[Scan Found] Detected Service Device: '$advertisedName' [${device.address}]"
            )

            if (device.address == connectedDeviceAddress) {
                lastConnectedRssi = result.rssi
            }

            // --- THE FIX: If building the list, STOP HERE. Do not connect automatically! ---
            if (isBroadDiscoveryMode) {
                return
            }

            // 2. Strict targeting filter for normal connection runs
            if (advertisedName != targetDeviceName) {
                Log.d(TAG, "[Scan Filter] Ignored '$advertisedName' due to strict targeting.")
                return
            }

            Log.i(
                TAG,
                "[Scan Match!] Found target reader matching configuration: '$advertisedName'"
            )

            _connectionState.value = ConnectionState.CONNECTING

            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                isScanning = false
            } catch (e: SecurityException) {
                Log.e(TAG, "[Security Exception] Scan permission missing during stop scan.", e)
            }

            Log.d(TAG, "[Connection] Initiating single GATT connection to ${device.address}")
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "[Scan Failed] BLE scanner failed with code: $errorCode")
            isScanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
            scope.launch { _eventFlow.emit(ReaderEvent.Error("Scan failed: $errorCode")) }
        }
    }

    // --- BLE GATT Callback ---

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
                Log.w(TAG, "[GATT Callback] Disconnected. Cleaning up and scheduling reconnect...")

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

                // --- PROTECTIVE WALL: Do not auto-reconnect if auth failed or explicitly disabled ---
                if (isAuthenticationFailure || !isAutoReconnectEnabled) {
                    Log.i(
                        TAG,
                        "[GATT Callback] Auto-reconnect canceled. Auth failure: $isAuthenticationFailure, Auto-reconnect enabled: $isAutoReconnectEnabled"
                    )
                    // Reset the flags so future intentional manual connection attempts can go through
                    isAuthenticationFailure = false
                    isAutoReconnectEnabled = true
                    return
                }

                // Regular accidental disconnections still get the auto-reconnect logic
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
            gatt.readRemoteRssi() // Initial RSSI fetch

            // Start the subscription chain from the beginning
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

        private fun subscribeToNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            } else {
                Log.w(TAG, "[GATT Chain] CCCD Descriptor missing for ${char.uuid}. Proceeding manually...")
                proceedToNextSubscriptionStep(gatt, char.uuid)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT Callback] Descriptor write failed for UUID: ${descriptor.uuid}")
            }
            proceedToNextSubscriptionStep(gatt, descriptor.characteristic.uuid)
        }

        private fun triggerPasswordChallenge(gatt: BluetoothGatt, service: BluetoothGattService) {
            val authChar = service.getCharacteristic(CHAR_AUTH_UUID) ?: return
            scope.launch {
                delay(300) 
                val passwordBytes = secureStoreManager.getAuthPasswordBytes()
                Log.i(TAG, "[Auth] Submitting password challenge bytes...")
                writeCharacteristicCompat(gatt, authChar, passwordBytes)
            }
        }

        // 1. Deprecated callback (for Android 12 and below)
        @Deprecated("Used for older SDK compatibility")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Only process here if we are on Android 12 or below
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                processIncomingData(characteristic.value, characteristic.uuid)
            }
        }

        // 2. Modern callback (for Android 13+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                processIncomingData(value, characteristic.uuid)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "[GATT] Remote RSSI update: $rssi dBm")
                lastConnectedRssi = rssi
            }
        }

        private var lastProcessedTimestamp: String? = null

        private fun processIncomingData(value: ByteArray?, charUuid: UUID) {
            // Convert to string and aggressively sanitize (trim and strip null terminators)
            val data = value?.toString(Charsets.UTF_8)
                ?.replace("\u0000", "")
                ?.trim() ?: return

            // --- RESTORED GLOBAL INSTANCE LOGGING ---
            val instanceId = System.identityHashCode(this).toString(16).uppercase()
            Log.i(TAG, "[BLE Notification from $instanceId] Raw Payload: '$data' (from $charUuid)")

            // --- Handle Authentication Verification ---
            if (charUuid == CHAR_AUTH_UUID) {
                if (data == "SUCCESS") {
                    Log.i(TAG, "[Auth] Device authenticated successfully! Running post-auth sync...")
                    scope.launch {
                        _eventFlow.emit(ReaderEvent.ConnectionSuccessful)
                    }
                    scope.launch {
                        syncSystemTime()
                        delay(300)
                        writeAppModeState(isAppActiveState)
                    }
                    return
                }

                if (data == "FAIL") {
                    Log.e(TAG, "[Auth Denied] Invalid password credential. Dropping connection link.")
                    isAuthenticationFailure = true
                    scope.launch {
                        _eventFlow.emit(ReaderEvent.AuthenticationFailed)
                    }
                    secureStoreManager.clearCredentialsFor(secureStoreManager.deviceName)
                    disconnect()
                    return
                }
            }

            if (charUuid == CHAR_RFID_DATA_UUID || charUuid == CHAR_INVENTORY_UUID) {
                if (data == "DONE") {
                    Log.d(TAG, "ESP32 sent DONE signal. Sync finished.")
                    scope.launch {
                        if (charUuid == CHAR_RFID_DATA_UUID) {
                            _rfidSwipeFlow.emit(Pair("SYNC_DONE", 0L))
                        } else {
                            _inventoryFlow.emit(Pair("SYNC_DONE", 0L))
                        }
                    }
                    return
                }

                // --- Handle Regular Tag Data Influx ---
                val parts = data.split(",")
                if (parts.size == 2) {
                    val tagId = parts[0].trim()
                    val timestampStr = parts[1].trim()
                    try {
                        val epochSec = timestampStr.toLong()

                        if (charUuid == CHAR_RFID_DATA_UUID) {
                            // --- ACCURATE STOP-AND-WAIT DEDUPLICATION ---
                            if (tagId == lastProcessedTag && timestampStr == lastProcessedTimestamp) {
                                Log.d(
                                    TAG,
                                    "[Deduplication] Retransmission detected for $tagId. Re-sending ACK to unstick ESP32."
                                )
                                writeAckToEsp32(tagId, timestampStr)
                                return
                            }

                            // Update our tracking variables with the unique historical record markers
                            lastProcessedTag = tagId
                            lastProcessedTimestamp = timestampStr

                            // Fresh new record! Process it normally
                            scope.launch {
                                _rfidSwipeFlow.emit(Pair(tagId, epochSec))
                            }
                            writeAckToEsp32(tagId, timestampStr)
                        } else {
                            // Inventory - No ACK needed, just emit
                            scope.launch {
                                _inventoryFlow.emit(Pair(tagId, epochSec))
                            }
                        }
                    } catch (e: NumberFormatException) {
                        Log.e(TAG, "[Parser Error] Failed parsing timestamp: '$timestampStr' in data '$data'")
                    }
                } else {
                    Log.w(TAG, "[Protocol Warning] Received invalid packet format: '$data'")
                }
            }
        }
    }

    fun requestBacklogSync() {
        val gatt = bluetoothGatt
        if (gatt == null || _connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "[Sync Cancelled] Cannot request sync: No active BLE connection.")
            return
        }

        val service = gatt.getService(SERVICE_UUID)
        val charAppMode = service?.getCharacteristic(CHAR_APP_MODE_UUID)

        if (charAppMode != null) {
            val command = "SYNC"
            Log.i(TAG, "[Pull-to-Refresh] Sending '$command' command to ESP32...")
            writeCharacteristicCompat(gatt, charAppMode, command.toByteArray(Charsets.UTF_8))
        } else {
            Log.e(TAG, "[Pull-to-Refresh Error] Command characteristic not found.")
        }
    }
}
