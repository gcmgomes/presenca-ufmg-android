package com.example.presensor.controllers

import android.annotation.SuppressLint
import android.util.Log
import com.example.presensor.R
import com.example.presensor.communication.ReaderEvent
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.communication.core.AppMode
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.controllers.items.DeviceItem
import com.example.presensor.controllers.providers.ReaderInteractionProvider
import kotlinx.coroutines.*

@SuppressLint("MissingPermission")
class ReaderDiscoveryController(
    private val secureStoreManager: SecureStoreManager,
    private val interactionProvider: ReaderInteractionProvider,
    private val orchestrator: ReaderOrchestrator,
    private val scope: CoroutineScope
) {
    private var currentDevices: List<com.example.presensor.communication.core.ReaderDevice> =
        emptyList()

    private var statusJob: Job? = null
    private var refreshJob: Job? = null
    private var eventJob: Job? = null

    private var pendingPassword: String? = null
    private var pendingDeviceName: String? = null

    companion object {
        private const val TAG = "ReaderDiscoveryCtrl"
    }

    private fun logAndToast(msgResId: Int, isShort: Boolean = true) {
        interactionProvider.showToast(msgResId, isShort)
    }

    fun setupReaderList() {
        interactionProvider.setupReaderDiscoveryUI(
            onReaderEnabledChanged = { isChecked ->
                orchestrator.setReaderEnabled(isChecked)
                if (isChecked) {
                    startRefreshLoop()
                    val name = secureStoreManager.deviceName
                    val password = secureStoreManager.getAuthPasswordFor(name)
                    if (password != null) {
                        orchestrator.startConnecting(name, password)
                    } else {
                        startDiscovery()
                    }
                } else {
                    teardownDiscovery(fullDisconnect = true)
                }
            },
            onRefreshRequested = {
                startDiscovery()
                scope.launch {
                    delay(1000)
                    interactionProvider.setDiscoveryRefreshing(false)
                }
            }
        )

        interactionProvider.setReaderEnabledState(orchestrator.isReaderEnabled.value)

        updateDeviceList()

        statusJob?.cancel()
        statusJob = scope.launch(Dispatchers.Main) {
            orchestrator.discoveredDevices.collect { devices ->
                deviceUpdate(devices)
            }
        }

        eventJob?.cancel()
        eventJob = scope.launch(Dispatchers.Main) {
            launch {
                orchestrator.isReaderEnabled.collect { enabled ->
                    interactionProvider.setReaderEnabledState(enabled)
                    if (!enabled) {
                        updateDeviceList()
                    }
                }
            }
            orchestrator.eventFlow.collect { event ->
                handleReaderEvent(event)
            }
        }

        if (orchestrator.isReaderEnabled.value) {
            startDiscovery()
            startRefreshLoop()
        }
    }

    fun teardownDiscovery(fullDisconnect: Boolean = false) {
        orchestrator.isBroadDiscoveryMode = false
        if (fullDisconnect) {
            orchestrator.disconnect()
        } else {
            orchestrator.stopScanning()
            orchestrator.setAppMode(AppMode.IDLE, "Discovery Teardown")
        }
        if (fullDisconnect) {
            currentDevices = emptyList()
            updateDeviceList()
        }
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun startDiscovery() {
        orchestrator.isBroadDiscoveryMode = true
        orchestrator.startScan()
        scope.launch {
            delay(5000)
            orchestrator.stopScanning()
        }
        updateDeviceList()
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (orchestrator.isReaderEnabled.value) {
                    startDiscovery()
                    orchestrator.requestRssiUpdate()

                    // Always refresh status for authenticated device during refresh cycles
                    if (orchestrator.isAuthenticated.value) {
                        orchestrator.requestStatus()
                    }
                }
                delay(20000)
            }
        }
    }

    private fun deviceUpdate(devices: List<com.example.presensor.communication.core.ReaderDevice>) {
        this.currentDevices = devices
        updateDeviceList()
    }

    private fun updateDeviceList() {
        if (orchestrator.isReaderEnabled.value != true) {
            interactionProvider.updateDeviceList(emptyList(), emptyList(), emptyList(), { _, _ -> }, { _, _ -> })
            return
        }

        val connectedItems = mutableListOf<DeviceItem>()
        val knownItems = mutableListOf<DeviceItem>()
        val unknownItems = mutableListOf<DeviceItem>()

        currentDevices.forEach { device ->
            val item = DeviceItem(
                name = device.name,
                address = device.address,
                rssi = device.rssi,
                batteryLevel = device.batteryLevel,
                deviceEpoch = device.deviceEpoch,
                isConnected = device.isConnected,
                isConnecting = device.isConnecting,
                isNearby = device.isNearby,
                lastSeen = device.lastSeen
            )

            when {
                item.isConnected -> connectedItems.add(item)
                secureStoreManager.hasPasswordFor(device.name) -> knownItems.add(item)
                else -> unknownItems.add(item)
            }
        }
        interactionProvider.updateDeviceList(
            connectedItems,
            knownItems,
            unknownItems,
            onDeviceSelected = { name, address -> handleReaderSelection(name, address) },
            onDeviceLongClicked = { name, address -> handleReaderLongClick(name, address) }
        )
    }

    internal fun handleReaderSelection(name: String, address: String) {
        if (address == orchestrator.connectedDeviceAddress && orchestrator.isAuthenticated.value) {
            orchestrator.disconnect(disableAutoReconnect = true)
            logAndToast(R.string.status_disconnected)
            startDiscovery()
            updateDeviceList()
            return
        }

        secureStoreManager.deviceName = name
        val storedPassword = secureStoreManager.getAuthPasswordFor(name)
        if (storedPassword == null) {
            interactionProvider.showPasswordPromptDialog(
                readerName = name,
                onPasswordEntered = { password ->
                    pendingPassword = password
                    pendingDeviceName = name
                    orchestrator.isBroadDiscoveryMode = false
                    orchestrator.startConnecting(
                        name,
                        password,
                        address,
                        isManual = true
                    )
                    updateDeviceList()
                    logAndToast(R.string.status_connecting)
                },
                onDismissed = {
                    if (orchestrator.connectionState.value == ReaderOrchestrator.ConnectionState.DISCONNECTED ||
                        orchestrator.connectionState.value == ReaderOrchestrator.ConnectionState.SCANNING
                    ) {
                        updateDeviceList()
                    }
                }
            )
        } else {
            orchestrator.isBroadDiscoveryMode = false
            orchestrator.startConnecting(
                name,
                storedPassword,
                address,
                isManual = true
            )
            updateDeviceList()
            logAndToast(R.string.status_connecting)
        }
    }

    private fun handleReaderLongClick(name: String, address: String) {
        interactionProvider.openDeviceManager(name, address)
    }

    private fun handleReaderEvent(event: ReaderEvent) {
        when (event) {
            is ReaderEvent.ConnectionSuccessful -> {
                logAndToast(R.string.status_connected)
                if (pendingPassword != null && pendingDeviceName != null) {
                    secureStoreManager.saveReaderCredentials(pendingDeviceName!!, pendingPassword!!)
                    pendingPassword = null
                    pendingDeviceName = null
                }
                updateDeviceList()
            }

            is ReaderEvent.AuthenticationFailed -> {
                logAndToast(R.string.error_incorrect_password)
                pendingPassword = null
                pendingDeviceName = null
                updateDeviceList()
            }

            is ReaderEvent.Error -> {
                logAndToast(R.string.toast_connection_timed_out)
                pendingPassword = null
                pendingDeviceName = null
                updateDeviceList()
            }
        }
    }
}
