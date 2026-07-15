package com.example.presensor.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*

@SuppressLint("MissingPermission")
class ReaderManager(private val context: Context) {

    companion object {
        private const val TAG = "ReaderManager"

        // --- UUIDs matching the ESP32 configuration exactly ---
        private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val CHAR_TIME_SYNC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private val CHAR_RFID_DATA_UUID = UUID.fromString("e3223119-9445-4e7d-be6d-2308c02c011e")
        private val CHAR_RFID_ACK_UUID = UUID.fromString("c485602d-1eb8-422f-981f-e053d71249b6")
        private val CHAR_APP_MODE_UUID = UUID.fromString("a29a0912-32b0-4dbf-9b16-43e936526131")

        // Client Characteristic Configuration Descriptor (CCCD)
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var lastProcessedTag: String? = null
    private var lastProcessedTime: Long = 0

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var isAppActiveState = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    // --- Exposed Reactive States ---
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _rfidSwipeFlow = MutableSharedFlow<Pair<String, Long>>(replay = 0)
    val rfidSwipeFlow: SharedFlow<Pair<String, Long>> = _rfidSwipeFlow

    // --- Public API ---
    fun startConnecting() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "[Init Error] Bluetooth is disabled or not supported.")
            return
        }

        // CRITICAL: Clean up any lingering connections before scanning!
        if (bluetoothGatt != null) {
            Log.d(TAG, "[Scan Prep] Cleaning up old GATT reference.")
            disconnect()
        }

        if (_connectionState.value != ConnectionState.DISCONNECTED) {
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "[Scan Error] BluetoothLeScanner is unavailable.")
            return
        }

        Log.d(TAG, "[Scan] Starting targeted scanning for Presensor Service: $SERVICE_UUID")
        _connectionState.value = ConnectionState.SCANNING

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }
    fun setAppActive(active: Boolean) {
        isAppActiveState = active
        Log.d(TAG, "[App Mode Change] Updating app readiness state to: $active")
        writeAppModeState(active)
    }

    fun disconnect() {
        Log.d(TAG, "[Disconnect] Explicitly requested. Tearing down connection.")
        handler.removeCallbacksAndMessages(null)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "[Cleanup] Failed to stop scan safely: ${e.message}")
        }

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

    // --- Modern, Safe Write Helper ---
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ modern API
            gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            // Deprecated fallback for older Android versions
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
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

        val payload = "$tagId,$timestamp"
        Log.d(TAG, "[BLE Write-ACK] Acknowledging receipt of: $payload")
        writeCharacteristicCompat(gatt, charAck, payload.toByteArray(Charsets.UTF_8))
    }

    // --- BLE Scan Callback ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // --- THE SHIELD: Prevent duplicate connection triggers ---
            if (_connectionState.value == ConnectionState.CONNECTING ||
                _connectionState.value == ConnectionState.CONNECTED) {
                return // We are already connecting/connected. Drop this duplicate scan result!
            }

            val device = result.device
            Log.i(TAG, "[Scan Found] Target device: ${device.name ?: "Unknown"} [${device.address}]")

            // Mark state immediately to block subsequent scan result triggers
            _connectionState.value = ConnectionState.CONNECTING

            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
            } catch (e: SecurityException) {
                Log.e(TAG, "[Security Exception] Scan permission missing during stop scan.", e)
            }

            Log.d(TAG, "[Connection] Initiating single GATT connection to ${device.address}")
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "[Scan Failed] BLE scanner failed with code: $errorCode")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // --- BLE GATT Callback ---

    private val gattCallback = object : BluetoothGattCallback() {
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

                // Use a local copy to close this specific gatt instance safely
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security permission missing during callback close", e)
                }

                // If this callback belonged to our current active gatt, null it out
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }

                _connectionState.value = ConnectionState.DISCONNECTED

                // Clear pending reconnect tasks before scheduling a new one
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ startConnecting() }, 3000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            Log.i(TAG, "[GATT Callback] Services discovered.")
            val service = gatt.getService(SERVICE_UUID) ?: return

            // Enable Notifications on RFID Data
            val rfidDataChar = service.getCharacteristic(CHAR_RFID_DATA_UUID)
            if (rfidDataChar != null) {
                gatt.setCharacteristicNotification(rfidDataChar, true)
                val descriptor = rfidDataChar.getDescriptor(CCC_DESCRIPTOR_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }

            // Sync sequence delay
            handler.postDelayed({
                syncSystemTime()
                handler.postDelayed({
                    writeAppModeState(isAppActiveState)
                }, 300)
            }, 600)
        }

        // 1. Deprecated callback (for Android 12 and below)
        @Deprecated("Used for older SDK compatibility")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Only process here if we are on Android 12 or below
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                processIncomingData(characteristic.value)
            }
        }

        // 2. Modern callback (for Android 13+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                processIncomingData(value)
            }
        }

        private fun processIncomingData(value: ByteArray?) {
            val data = value?.toString(Charsets.UTF_8) ?: return
            val instanceId = System.identityHashCode(this).toString(16).uppercase()
            Log.i(TAG, "[BLE Notification from $instanceId] Raw Payload: '$data'")

            if (data == "DONE") {
                Log.d(TAG, "ESP32 sent DONE signal. Backlog sync finished.")
                scope.launch {
                    _rfidSwipeFlow.emit(Pair("SYNC_DONE", 0L))
                }
                return
            }

            val parts = data.split(",")
            if (parts.size == 2) {
                val tagId = parts[0]
                val timestampStr = parts[1]
                try {
                    val epochSec = timestampStr.toLong()

                    // --- DEDUPLICATION CHECK ---
                    val currentTime = System.currentTimeMillis()
                    if (tagId == lastProcessedTag && currentTime == lastProcessedTime) {
                        Log.d(TAG, "[Deduplication] Ignored duplicate tag notification for: $tagId")
                        return // Drop this duplicate entry
                    }

                    // Update tracking variables
                    lastProcessedTag = tagId
                    lastProcessedTime = currentTime

                    scope.launch {
                        _rfidSwipeFlow.emit(Pair(tagId, epochSec))
                        writeAckToEsp32(tagId, timestampStr)
                    }
                } catch (e: NumberFormatException) {
                    Log.e(TAG, "[Parser Error] Failed parsing timestamp: $timestampStr", e)
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