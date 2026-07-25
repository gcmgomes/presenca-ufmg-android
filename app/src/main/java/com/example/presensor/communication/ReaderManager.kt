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

    private val _connectingAddress = MutableStateFlow<String?>(null)
    val connectingAddress: StateFlow<String?> = _connectingAddress

    private val _isRebooting = MutableStateFlow(false)
    val isRebooting: StateFlow<Boolean> = _isRebooting

    private val _isIntentionalDisconnect = MutableStateFlow(false)
    // Exposed as public for debugging or UI state checks if needed
    val isIntentionalDisconnect: StateFlow<Boolean> = _isIntentionalDisconnect

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
        _activeDevice,
        _connectionState,
        isAuthenticated,
        connectedAddress,
        _connectingAddress
    ) { args ->
        val discovered = args[0] as Map<String, ReaderDevice>
        val active = args[1] as? ReaderDevice
        val state = args[2] as ConnectionState
        val auth = args[3] as Boolean
        val connectedAddr = args[4] as? String
        val connectingAddr = args[5] as? String

        val merged = discovered.toMutableMap()
        active?.let {
            if (it.address.isNotBlank()) {
                merged[it.address] = it
            }
        }

        merged.values.map { device ->
            val isTarget = device.address == connectedAddr || device.address == connectingAddr
            device.copy(
                isConnected = (device.address == connectedAddr && auth),
                isConnecting = (isTarget && !auth && state != ConnectionState.DISCONNECTED)
            )
        }
            .filter { it.address.isNotBlank() } // Final ghost filter
            .sortedByDescending { it.rssi }
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    // Tracks which devices have been "shown" to the UI in the current scan cycle
    private val _emittedInCurrentCycle = mutableSetOf<String>()

    /**
     * Snapshots the internal discovery map to prune stale devices and update nearby status.
     * @param isCycleEnd If true, re-evaluates 'isNearby' status based on current cycle hits.
     */
    private fun pruneDiscoveryMap(isCycleEnd: Boolean = false) {
        val connectedAddr = connectedAddress.value?.uppercase()
        val activeAddr = _activeDevice.value?.address?.uppercase()

        _discoveredDevices.update { current ->
            current.mapValues { (addr, device) ->
                val normalizedAddr = addr.uppercase()

                // --- STICKY NEARBY LOGIC (Task 4.5 Fix) ---
                // Only downgrade isNearby to false if we just finished an active scan cycle
                // and the device wasn't seen AND it's not the active/connected one.

                val seenInThisCycle = _emittedInCurrentCycle.contains(normalizedAddr)
                val isCurrentlyActive =
                    normalizedAddr == connectedAddr || normalizedAddr == activeAddr

                val updatedNearby = if (isCycleEnd) {
                    // At cycle end, we require a fresh hit or active connection
                    seenInThisCycle || isCurrentlyActive
                } else {
                    // In idle periods or during disconnect transitions, we PRESERVE 'Nearby' status
                    // unless it's currently connected/active (which forces true)
                    device.isNearby || isCurrentlyActive
                }

                device.copy(isNearby = updatedNearby)
            }.filter { (addr, device) ->
                val normalizedAddr = addr.uppercase()
                val isKnown = secureStoreManager.hasPasswordFor(device.name)
                // Retention: Keep if nearby, OR if it's a Known device (Offline persistence), OR if it's active
                device.isNearby || isKnown || normalizedAddr == activeAddr
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
                val advertisedName =
                    result.scanRecord?.deviceName ?: result.device.name ?: "Unknown"
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
                        _activeDevice.update {
                            it?.copy(
                                rssi = device.rssi,
                                lastSeen = device.lastSeen
                            )
                        }
                    }
                }

                if (!isBroadDiscoveryMode) {
                    val targetName = secureStoreManager.deviceName
                    if (advertisedName == targetName) {
                        // --- STABILIZATION: Ignore if currently rebooting ---
                        if (_isRebooting.value) {
                            Log.d(TAG, "[Discovery] Match found for $targetName but device is REBOOTING. Ignoring.")
                            return@collect
                        }
                        Log.i(TAG, "[Orchestrator] Match found for $targetName. Connecting...")
                        transport.stopScan()
                        transport.connect(result.device.address)
                    }
                }
            }
        }

        // UNIFIED STATE SYNC: Atomic high-level state calculation
        var lastScanningState = false
        scope.launch {
            combine(
                transport.connectionState,
                protocol.isAuthenticated,
                transport.isScanning,
                _isRebooting,
                _isIntentionalDisconnect
            ) { tState, auth, scanning, rebooting, intentional ->
                updateHighLevelConnectionState(tState, auth, scanning, rebooting, intentional)
                Triple(tState, auth, scanning)
            }.collect { (tState, auth, scanning) ->
                // --- CRITICAL STABILIZATION: If radio is down, FORCE auth reset ---
                if (tState == TransportConnectionState.DISCONNECTED) {
                    if (protocol.isAuthenticated.value) {
                        Log.w(
                            TAG,
                            "[Orchestrator] Radio in $tState but Auth is True. Forcing Reset."
                        )
                        protocol.resetAuth()
                    }
                }

                // --- TRIGGER AUTHENTICATION ---
                if (tState == TransportConnectionState.READY && !auth && !_isRebooting.value) {
                    val password = protocol.authPassword
                    if (password != null) {
                        Log.i(TAG, "[Orchestrator] Transport READY. Sending Auth Handshake...")
                        val data = protocol.formatAuthCommand(password)
                        transport.write(data, TransportChannel.AUTH)
                    }
                }

                Log.d(
                    TAG,
                    "[Orchestrator] State Change -> Transport: $tState, Auth: $auth, Scanning: $scanning"
                )

                if (lastScanningState && !scanning) {
                    // --- BURST COMPLETED ---
                    // This is the ONLY place where devices can be downgraded to 'Offline'
                    Log.i(TAG, "[Orchestrator] Scan burst finished. Re-evaluating Nearby status.")
                    pruneDiscoveryMap(isCycleEnd = true)
                    _emittedInCurrentCycle.clear()
                } else if (!scanning) {
                    // Periodic idle maintenance (e.g. RSSI cleanup) but keep Nearby status sticky
                    pruneDiscoveryMap(isCycleEnd = false)
                }

                lastScanningState = scanning
            }
        }

        // Periodic maintenance (30s) when idle to clean up old devices
        scope.launch {
            while (isActive) {
                delay(30000)
                if (!transport.isScanning.value) {
                    pruneDiscoveryMap(isCycleEnd = false)
                }
                // Periodic Health Check for authenticated device
                if (isAuthenticated.value) {
                    requestStatus()
                }
            }
        }
    }

    private fun updateHighLevelConnectionState(
        tState: TransportConnectionState,
        auth: Boolean,
        scanning: Boolean,
        isRebooting: Boolean,
        isIntentionalDisconnect: Boolean
    ) {
        val oldState = _connectionState.value
        val isIntentionalTransition = isRebooting || isIntentionalDisconnect

        val newState = when {
            // Priority 1: If authenticated, we are 100% CONNECTED
            tState == TransportConnectionState.READY && auth -> ConnectionState.CONNECTED

            // Priority 2: If radio is active but not auth, we are CONNECTING (limbo)
            // BUGFIX: If we are intentionally rebooting or disconnecting, transition directly to DISCONNECTED
            tState == TransportConnectionState.READY || tState == TransportConnectionState.CONNECTING -> {
                if (isIntentionalTransition) ConnectionState.DISCONNECTED else ConnectionState.CONNECTING
            }

            // Priority 3: If radio is idle but scanner is running, show SCANNING
            scanning -> ConnectionState.SCANNING

            // Priority 4: Otherwise, DISCONNECTED
            else -> ConnectionState.DISCONNECTED
        }

        if (oldState != newState) {
            Log.i(TAG, "[Orchestrator] UI State Transition: $oldState -> $newState")
            _connectionState.value = newState
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
                _connectingAddress.value = null // Success, no longer connecting
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
                _connectingAddress.value = null // Failure, no longer connecting
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

    fun startConnecting(
        deviceName: String,
        password: String,
        address: String? = null,
        isManual: Boolean = false
    ) {
        if (!_isReaderEnabled.value) {
            Log.e(TAG, "[Connect Flow] ABORTING connection to $deviceName. Reader is DISABLED.")
            return
        }

        // --- STABILIZATION: Strict MAC address validation (Task 4.2.3 Fix) ---
        val macRegex = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        val normalizedAddress = address?.uppercase()?.trim()?.takeIf { it.matches(macRegex) }
        _connectingAddress.value = normalizedAddress

        Log.i(
            TAG,
            "[Orchestrator] Targeting $deviceName at ${normalizedAddress ?: "unknown address"}. Manual=$isManual"
        )

        // --- STABILIZATION: Clear any previous auth status before starting new link ---
        protocol.resetAuth()
        _isIntentionalDisconnect.value = false // Reset intentional flag on new connection

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
            val limitMs = if (isManual) 10000L else 5000L // Manual attempts get 10s to account for reboots/discovery

            while (activeTimeMs < limitMs) {
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
                // BUGFIX: For manual reconnection attempts (especially after reboots),
                // we DO NOT cancel the timer if the link drops; we let the full timeout play out
                // or wait for auto-reconnect/scan logic to succeed.
                if (!isManual && _connectionState.value == ConnectionState.DISCONNECTED && !transport.isScanning.value) {
                    Log.d(TAG, "[Timeout] Background disconnect. Cancelling timer.")
                    return@launch
                }

                activeTimeMs += 500
            }

            // If we get here, the specified limit (2s for manual, 5s for auto) has passed
            Log.e(
                TAG,
                "[Timeout] Connection attempt timed out after ${limitMs / 1000}s of active wait. isManual=$isManual"
            )
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
        _connectingAddress.value = null // Cancelled
        _isIntentionalDisconnect.value = true // Set flag to suppress Orange blip during teardown
        if (disableAutoReconnect) _isAutoReconnectEnabled = false
        Log.i(TAG, "[Orchestrator] Full teardown requested. Stopping scan and closing transport.")

        // --- STABILIZATION: Force Protocol and UI state reset immediately ---
        protocol.resetAuth()
        _connectionState.value = ConnectionState.DISCONNECTED

        // --- PERSISTENCE: Ensure the device remains in the list as a non-active device ---
        _activeDevice.value?.let { device ->
            // Mark as NOT active but explicitly keep it 'nearby' (Sticky)
            // It will only become offline at the end of the NEXT scan cycle.
            val updatedDevice = device.copy(
                isNearby = true,
                lastSeen = currentTimeMillis()
            )
            _discoveredDevices.update { it + (device.address to updatedDevice) }
        }
        _activeDevice.value = null // Release persistent identity

        pruneDiscoveryMap(isCycleEnd = false) // Maintenance sync, don't kill Nearby status
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

    /**
     * Initiates a full reader reboot sequence. 
     * Handles disconnection, intentional delay for hardware restart, and reconnection.
     */
    fun rebootReader(newName: String, newPass: String, address: String) {
        Log.i(TAG, "[Orchestrator] Initiating Reboot & Reconnect sequence for $address")
        scope.launch {
            _isRebooting.value = true
            
            // 1. Force immediate disconnect
            disconnect(disableAutoReconnect = true)
            
            // 2. Wait for ESP32 to restart (Increased to 7s for safety)
            delay(7000)
            
            // 3. Clear rebooting flag and attempt reconnect
            _isRebooting.value = false
            Log.i(TAG, "[Orchestrator] Reboot delay finished. Attempting reconnection via scan...")
            
            // We pass null for address to force a targeted scan. 
            // This is more reliable than connectGatt() on a device that might still be initializing its BLE stack.
            startConnecting(newName, newPass, null, isManual = true)
        }
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

    fun isInManagementMode(): Boolean =
        currentMode == com.example.presensor.communication.core.AppMode.MANAGEMENT
}
