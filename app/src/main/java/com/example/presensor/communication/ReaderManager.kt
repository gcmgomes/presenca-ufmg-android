package com.example.presensor.communication

import android.util.Log
import com.example.presensor.communication.core.*
import com.example.presensor.data.SecureStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class ReaderEvent {
    object ConnectionSuccessful : ReaderEvent()
    object AuthenticationFailed : ReaderEvent()
    data class Error(val message: String) : ReaderEvent()
}

class ReaderManager(
    private val secureStoreManager: SecureStoreManager,
    private val transport: ReaderTransport,
    private val protocol: ReaderProtocol = ReaderProtocol(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    companion object {
        private const val TAG = "ReaderManager"
    }

    // High-level states for the UI
    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    private val _isReaderEnabled = MutableStateFlow(secureStoreManager.isReaderEnabled)
    val isReaderEnabled: StateFlow<Boolean> = _isReaderEnabled

    fun setReaderEnabled(enabled: Boolean) {
        if (_isReaderEnabled.value == enabled) return
        Log.i(TAG, "[Lifecycle] Reader Enabled toggled to: $enabled")
        _isReaderEnabled.value = enabled
        secureStoreManager.isReaderEnabled = enabled
        if (!enabled) {
            disconnect(disableAutoReconnect = true)
        }
    }

    private var _isAutoReconnectEnabled = true
    fun isAutoReconnectedEnabled() = _isAutoReconnectEnabled

    var isBroadDiscoveryMode: Boolean = false

    val isAuthenticated: StateFlow<Boolean> = protocol.isAuthenticated
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    val lastConnectedRssi: Int? get() = transport.lastRssi.value
    val connectedDeviceAddress: String? get() = transport.connectedAddress.value

    private val _rfidSwipeFlow = MutableSharedFlow<Pair<String, Long>>(replay = 0)
    val rfidSwipeFlow: SharedFlow<Pair<String, Long>> = _rfidSwipeFlow

    private val _inventoryFlow = MutableSharedFlow<Pair<String, Long>>(replay = 0)
    val inventoryFlow: SharedFlow<Pair<String, Long>> = _inventoryFlow

    private val _metricsFlow = MutableSharedFlow<Pair<Long, Int>>(replay = 0)
    val metricsFlow: SharedFlow<Pair<Long, Int>> = _metricsFlow

    private val _eventFlow = MutableSharedFlow<ReaderEvent>(replay = 0)
    val eventFlow: SharedFlow<ReaderEvent> = _eventFlow

    var onDeviceFoundListener: ((android.bluetooth.le.ScanResult) -> Unit)? = null
    
    private var currentMode = AppMode.IDLE

    init {
        // Wire Transport -> Protocol
        scope.launch {
            transport.incomingData.collect { (data, channel) ->
                protocol.processData(data, channel)
            }
        }

        // Wire Protocol -> Manager Flows
        scope.launch {
            protocol.domainEvents.collect { event ->
                handleProtocolEvent(event)
            }
        }

        // Wire Transport Discovery -> Listener
        scope.launch {
            transport.discoveredDevices.collect { result ->
                // --- BUGFIX: If reader is globally disabled, ABORT ALL background actions ---
                if (!_isReaderEnabled.value) {
                    Log.w(TAG, "[Discovery] Discarding result because Reader is DISABLED.")
                    return@collect
                }

                // --- UI FIX: Always notify the listener so the card appears/updates in the list ---
                // Even if we are in targeted mode, the UI needs to know the device was found 
                // to show RSSI or the 'Connecting' state.
                onDeviceFoundListener?.invoke(result)

                if (!isBroadDiscoveryMode) {
                    val targetName = secureStoreManager.deviceName
                    val advertisedName = result.scanRecord?.deviceName ?: result.device.name ?: "Unknown"
                    if (advertisedName == targetName) {
                        Log.i(TAG, "[Orchestrator] Match found for $targetName. Connecting...")
                        transport.stopScan()
                        transport.connect(result.device.address)
                    }
                }
            }
        }

        // UNIFIED STATE SYNC: Atomic high-level state calculation
        scope.launch {
            combine(
                transport.connectionState,
                protocol.isAuthenticated,
                transport.isScanning
            ) { tState, auth, scanning ->
                Triple(tState, auth, scanning)
            }.collect { (tState, auth, scanning) ->
                // --- CRITICAL STABILIZATION: If radio is down, FORCE auth reset ---
                if (tState == TransportConnectionState.DISCONNECTED) {
                    if (protocol.isAuthenticated.value) {
                        Log.w(TAG, "[Orchestrator] Radio in $tState but Auth is True. Forcing Reset.")
                        protocol.resetAuth()
                    }
                }
                
                Log.d(TAG, "[Orchestrator] State Change -> Transport: $tState, Auth: $auth, Scanning: $scanning")
                updateHighLevelConnectionState(tState, auth, scanning)
            }
        }
    }

    private fun updateHighLevelConnectionState(tState: TransportConnectionState, auth: Boolean, scanning: Boolean) {
        val oldState = _connectionState.value
        val newState = when {
            // Priority 1: If authenticated, we are 100% CONNECTED regardless of background scanning
            tState == TransportConnectionState.READY && auth -> ConnectionState.CONNECTED
            
            // Priority 2: If radio is active but not auth, we are CONNECTING (limbo)
            tState == TransportConnectionState.READY -> ConnectionState.CONNECTING
            tState == TransportConnectionState.CONNECTING -> ConnectionState.CONNECTING
            
            // Priority 3: If radio is idle but scanner is running, show SCANNING
            scanning -> ConnectionState.SCANNING
            
            // Priority 4: Otherwise, DISCONNECTED
            else -> ConnectionState.DISCONNECTED
        }

        if (oldState != newState) {
            Log.i(TAG, "[Orchestrator] UI State Transition: $oldState -> $newState")
            _connectionState.value = newState
        }
        
        // Handle trigger for auth when transport is ready
        if (tState == TransportConnectionState.READY && !auth) {
            val pass = protocol.authPassword
            if (pass != null) {
                scope.launch {
                    delay(300)
                    Log.i(TAG, "[Orchestrator] Transport READY. Submitting Auth Challenge...")
                    val data = protocol.formatAuthCommand(pass)
                    transport.write(data, TransportChannel.AUTH)
                }
            }
        }
        
        // Handle auto-reconnect
        if (tState == TransportConnectionState.DISCONNECTED && _isAutoReconnectEnabled && !scanning && _isReaderEnabled.value) {
            scope.launch {
                delay(3000)
                if (_isAutoReconnectEnabled && transport.connectionState.value == TransportConnectionState.DISCONNECTED && _isReaderEnabled.value) {
                    Log.i(TAG, "[Orchestrator] Connection lost. Triggering auto-reconnect...")
                    startConnecting()
                }
            }
        }
    }

    private suspend fun handleProtocolEvent(event: ProtocolEvent) {
        when (event) {
            is ProtocolEvent.RfidSwipe -> _rfidSwipeFlow.emit(event.tagId to event.timestamp)
            is ProtocolEvent.InventoryItem -> _inventoryFlow.emit(event.tagId to event.timestamp)
            is ProtocolEvent.Metrics -> _metricsFlow.emit(event.timestamp to event.batteryLevel)
            is ProtocolEvent.SyncDone -> {
                _rfidSwipeFlow.emit("SYNC_DONE" to 0L)
                _inventoryFlow.emit("SYNC_DONE" to 0L)
            }
            is ProtocolEvent.DeletionSuccess -> _inventoryFlow.emit("DEL_OK" to 0L)
            is ProtocolEvent.DeletionError -> _inventoryFlow.emit("DEL_ERR" to 0L)
            is ProtocolEvent.AuthSuccess -> {
                _eventFlow.emit(ReaderEvent.ConnectionSuccessful)
                // --- GATT BREATHER (Fix for Status 201) ---
                // We wait 500ms after Auth success before sending commands.
                // This gives the radio time to settle after the handshake notification.
                scope.launch {
                    delay(500)
                    syncTime()
                    delay(300)
                    setAppMode(currentMode, "Auth Success Restoration")
                }
            }
            is ProtocolEvent.AuthFailed -> {
                _eventFlow.emit(ReaderEvent.AuthenticationFailed)
                _isAutoReconnectEnabled = false
                secureStoreManager.clearCredentialsFor(secureStoreManager.deviceName)
                transport.disconnect()
            }
            is ProtocolEvent.AckRequired -> {
                val ackData = protocol.formatAckCommand(event.tagId, event.timestamp)
                transport.write(ackData, TransportChannel.ACK)
            }
        }
    }

    fun startConnecting(deviceName: String, password: String) {
        if (!_isReaderEnabled.value) {
            Log.e(TAG, "[Connect Flow] ABORTING connection to $deviceName. Reader is DISABLED.")
            return
        }
        Log.i(TAG, "[Orchestrator] Targeting $deviceName")
        _isAutoReconnectEnabled = true
        isBroadDiscoveryMode = false
        protocol.authPassword = password
        startScan()
    }

    fun startConnecting() {
        val name = secureStoreManager.deviceName
        val password = secureStoreManager.getAuthPasswordFor(name)
        if (password != null) startConnecting(name, password)
    }

    fun startScan() {
        if (!_isReaderEnabled.value) {
            Log.e(TAG, "[Scan Flow] ABORTING scan. Reader is DISABLED.")
            return
        }
        transport.startScan(isBroadDiscoveryMode)
    }

    fun stopScanning() {
        transport.stopScan()
    }

    fun disconnect(disableAutoReconnect: Boolean = false) {
        if (disableAutoReconnect) _isAutoReconnectEnabled = false
        Log.i(TAG, "[Orchestrator] Full teardown requested. Stopping scan and closing transport.")
        
        // --- STABILIZATION: Force Protocol and UI state reset immediately ---
        protocol.resetAuth()
        _connectionState.value = ConnectionState.DISCONNECTED

        transport.stopScan()
        transport.disconnect()
        // Reset to broad mode so we can find other devices after disconnect
        isBroadDiscoveryMode = true
    }

    fun setAppMode(mode: AppMode, caller: String) {
        Log.i(TAG, "[Orchestrator] Setting App Mode: $mode (Caller: $caller)")
        currentMode = mode
        val data = protocol.formatAppModeCommand(mode)
        transport.write(data, TransportChannel.MODE)
    }

    fun syncTime() {
        val epoch = System.currentTimeMillis() / 1000
        val data = protocol.formatTimeSyncCommand(epoch)
        transport.write(data, TransportChannel.TIME)
    }

    fun updateReaderConfig(newName: String, newPassword: String) {
        val data = protocol.formatConfigUpdateCommand(newName, newPassword)
        transport.write(data, TransportChannel.CONFIG)
    }

    fun requestInventory() {
        val data = protocol.formatInventoryGetCommand()
        transport.write(data, TransportChannel.INVENTORY)
    }

    fun deleteBacklogItem(tagId: String, timestamp: Long) {
        val data = protocol.formatInventoryDeleteCommand(tagId, timestamp)
        transport.write(data, TransportChannel.INVENTORY)
    }

    fun requestRssiUpdate() {
        transport.requestRssi()
    }

    fun requestBacklogSync() {
        val data = protocol.formatSyncCommand()
        transport.write(data, TransportChannel.MODE)
    }
    
    fun isInManagementMode(): Boolean = currentMode == com.example.presensor.communication.core.AppMode.MANAGEMENT
}
