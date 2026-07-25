package com.example.presensor.communication

import android.annotation.SuppressLint
import android.util.Log
import com.example.presensor.communication.core.*
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.data.SecureStoreManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed class ReaderEvent {
    object ConnectionSuccessful : ReaderEvent()
    object AuthenticationFailed : ReaderEvent()
    data class Error(val message: String) : ReaderEvent()
}

@OptIn(FlowPreview::class)
class ReaderManager(
    private val secureStoreManager: SecureStoreManager,
    private val transport: ReaderTransport,
    private val protocol: ReaderProtocol = ReaderProtocol(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        private const val TAG = "ReaderManager"
        const val ERROR_TIMEOUT = "ERROR_TIMEOUT"
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
    val connectedAddress: StateFlow<String?> = transport.connectedAddress
    
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

    private val _eventFlow = MutableSharedFlow<ReaderEvent>(replay = 1)
    val eventFlow: SharedFlow<ReaderEvent> = _eventFlow

    private val _discoveredDevices = MutableStateFlow<Map<String, ReaderDevice>>(emptyMap())
    private val _activeDevice = MutableStateFlow<ReaderDevice?>(null)
    
    val discoveredDevices: StateFlow<List<ReaderDevice>> = combine(
        _discoveredDevices,
        _activeDevice
    ) { discovered, active ->
        val merged = discovered.toMutableMap()
        active?.let { 
            if (it.address.isNotBlank()) {
                merged[it.address] = it 
            }
        }
        merged.values.toList()
            .filter { it.address.isNotBlank() } // Final ghost filter
            .sortedByDescending { it.rssi }
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    // Tracks which devices have been "shown" to the UI in the current scan cycle
    private val _emittedInCurrentCycle = mutableSetOf<String>()

    /**
     * Snapshots the internal discovery map to prune stale devices.
     */
    private fun pruneDiscoveryMap() {
        val now = currentTimeMillis()
        val isScanning = transport.isScanning.value
        
        _discoveredDevices.update { current ->
            current.filter { (_, device) ->
                if (device.address == connectedDeviceAddress) return@filter true
                val timeout = if (isScanning) 3000L else 30000L
                now - device.lastSeen < timeout
            }
        }
    }
    
    private var currentMode = AppMode.IDLE
    
    private var connectionTimeoutJob: Job? = null

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

                @SuppressLint("MissingPermission")
                val advertisedName = result.scanRecord?.deviceName ?: result.device.name ?: "Unknown"
                val address = result.device.address.uppercase()
                val device = ReaderDevice(
                    name = advertisedName,
                    address = address,
                    rssi = result.rssi,
                    lastSeen = currentTimeMillis()
                )

                // --- DYNAMIC LOADING: Only update once per cycle to prevent UI jitter ---
                if (_emittedInCurrentCycle.add(address)) {
                    _discoveredDevices.update { it + (address to device) }
                    
                    // Also update the active device's persistent state once per cycle if it matches
                    if (address == _activeDevice.value?.address) {
                        _activeDevice.update { it?.copy(rssi = device.rssi, lastSeen = device.lastSeen) }
                    }
                }

                if (!isBroadDiscoveryMode) {
                    val targetName = secureStoreManager.deviceName
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
                
                if (!scanning) {
                    // End of cycle: Final prune and clear trackers
                    pruneDiscoveryMap()
                    _emittedInCurrentCycle.clear()
                }
            }
        }

        // Periodic maintenance (30s) when idle to clean up old devices
        scope.launch {
            while (isActive) {
                delay(30000)
                if (!transport.isScanning.value) {
                    pruneDiscoveryMap()
                }
                // Periodic Health Check for authenticated device
                if (isAuthenticated.value) {
                    requestStatus()
                }
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
                // Increased delay to 5 seconds to stay safely under OS "scanning too frequently" limits
                delay(5000)
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
            is ProtocolEvent.Metrics -> {
                _metricsFlow.emit(event.timestamp to event.batteryLevel)
                // Update the persistent active device with latest battery and epoch
                _activeDevice.update { current ->
                    if (current?.address == connectedDeviceAddress) {
                        current?.copy(
                            batteryLevel = event.batteryLevel,
                            deviceEpoch = event.timestamp
                        )
                    } else current
                }
            }
            is ProtocolEvent.SyncDone -> {
                _rfidSwipeFlow.emit("SYNC_DONE" to 0L)
                _inventoryFlow.emit("SYNC_DONE" to 0L)
            }
            is ProtocolEvent.DeletionSuccess -> _inventoryFlow.emit("DEL_OK" to 0L)
            is ProtocolEvent.DeletionError -> _inventoryFlow.emit("DEL_ERR" to 0L)
            is ProtocolEvent.AuthSuccess -> {
                connectionTimeoutJob?.cancel()
                _eventFlow.emit(ReaderEvent.ConnectionSuccessful)
                // --- GATT BREATHER (Fix for Status 201) ---
                // We wait 500ms after Auth success before sending commands.
                // This gives the radio time to settle after the handshake notification.
                scope.launch {
                    delay(500)
                    syncTime()
                    delay(300)
                    requestStatus() // Initial health check
                    delay(300)
                    setAppMode(currentMode, "Auth Success Restoration")
                }
            }
            is ProtocolEvent.AuthFailed -> {
                connectionTimeoutJob?.cancel()
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

    fun startConnecting(deviceName: String, password: String, address: String? = null, isManual: Boolean = false) {
        if (!_isReaderEnabled.value) {
            Log.e(TAG, "[Connect Flow] ABORTING connection to $deviceName. Reader is DISABLED.")
            return
        }
        
        // --- STABILIZATION: Strict MAC address validation (Task 4.2.3 Fix) ---
        val macRegex = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        val normalizedAddress = address?.uppercase()?.trim()?.takeIf { it.matches(macRegex) }
        
        Log.i(TAG, "[Orchestrator] Targeting $deviceName at ${normalizedAddress ?: "unknown address"}. Manual=$isManual")

        // --- STABILIZATION: Clear any previous auth status before starting new link ---
        protocol.resetAuth()

        // --- PERSISTENT IDENTITY: Only set active device if we have a valid address ---
        if (normalizedAddress != null) {
            val existing = _discoveredDevices.value[normalizedAddress]
            _activeDevice.value = existing ?: ReaderDevice(
                name = deviceName,
                address = normalizedAddress,
                rssi = transport.lastRssi.value ?: -100,
                lastSeen = currentTimeMillis()
            )
        } else {
            // If address is unknown, clear any stale active device to prevent ghost cards
            _activeDevice.value = null
        }

        _isAutoReconnectEnabled = true
        isBroadDiscoveryMode = false
        protocol.authPassword = password
        
        if (normalizedAddress != null) {
            transport.stopScan()
            transport.connect(normalizedAddress)
        } else {
            // If we don't have an address, we MUST scan to find it first.
            startScan()
        }

        startConnectionTimeout(isManual)
    }

    private fun startConnectionTimeout(isManual: Boolean) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            var activeTimeMs = 0L
            while (activeTimeMs < 5000L) {
                delay(500)
                
                // If a dialog is open, we "pause" the timer
                if (DialogFactory.isAnyDialogOpen()) {
                    Log.v(TAG, "[Timeout] Connection timer paused: Dialog is OPEN.")
                    continue
                }
                
                // Check if we are already connected (Success)
                if (isAuthenticated.value) {
                    Log.d(TAG, "[Timeout] Connection successful. Cancelling timer.")
                    return@launch
                }
                
                // Check if hardware disconnected (Failure)
                if (_connectionState.value == ConnectionState.DISCONNECTED && !transport.isScanning.value) {
                    Log.d(TAG, "[Timeout] Hardware disconnected. Cancelling timer.")
                    return@launch
                }

                activeTimeMs += 500
            }

            // If we get here, 5 seconds of non-dialog time have passed without success
            Log.e(TAG, "[Timeout] Connection attempt timed out after 5s of active wait. isManual=$isManual")
            withContext(Dispatchers.Main) {
                if (isManual) {
                    Log.i(TAG, "[Timeout] Emitting ERROR_TIMEOUT event...")
                    _eventFlow.emit(ReaderEvent.Error(ERROR_TIMEOUT))
                    disconnect(disableAutoReconnect = true)
                } else {
                    Log.d(TAG, "[Timeout] Background attempt timed out. Resetting for next retry.")
                    disconnect(disableAutoReconnect = false) // Keep auto-reconnect ALIVE
                }
            }
        }
    }

    fun startConnecting() {
        val name = secureStoreManager.deviceName
        val password = secureStoreManager.getAuthPasswordFor(name)
        
        // --- AUTO-CONNECT (Background): Try to get MAC from discovery map ---
        val address = _discoveredDevices.value.values.find { it.name == name }?.address
        
        if (password != null) {
            startConnecting(name, password, address, isManual = false)
        }
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
        connectionTimeoutJob?.cancel()
        if (disableAutoReconnect) _isAutoReconnectEnabled = false
        Log.i(TAG, "[Orchestrator] Full teardown requested. Stopping scan and closing transport.")
        
        // --- STABILIZATION: Force Protocol and UI state reset immediately ---
        protocol.resetAuth()
        _connectionState.value = ConnectionState.DISCONNECTED
        
        // --- PERSISTENCE: Ensure the device remains in the list as a non-active device ---
        _activeDevice.value?.let { device ->
            val updatedDevice = device.copy(lastSeen = currentTimeMillis())
            _discoveredDevices.update { it + (device.address to updatedDevice) }
        }
        _activeDevice.value = null // Release persistent identity

        pruneDiscoveryMap() // Force immediate sync of nearby states
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

    /**
     * Triggers a health check on the authenticated reader to fetch epoch and battery.
     */
    fun requestStatus() {
        if (!isAuthenticated.value) return
        Log.d(TAG, "[Health Check] Requesting current status (GET)...")
        val data = protocol.formatStatusGetCommand()
        transport.write(data, TransportChannel.STATUS)
    }
    
    fun isInManagementMode(): Boolean = currentMode == com.example.presensor.communication.core.AppMode.MANAGEMENT
}
