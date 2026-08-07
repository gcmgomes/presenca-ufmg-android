package com.example.presensor.controllers

import android.annotation.SuppressLint
import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.communication.core.AppMode
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.providers.ReaderInteractionProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class ReaderManagementController(
    private val db: AppDatabase,
    private val secureStoreManager: SecureStoreManager,
    private val interactionProvider: ReaderInteractionProvider,
    private val orchestrator: ReaderOrchestrator,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var metricsJob: Job? = null
    private var inventoryJob: Job? = null
    private var dashboardUIJob: Job? = null

    private var backlogItems = mutableListOf<BacklogItem>()
    private val receivedInSync = mutableSetOf<BacklogItem>()
    private var isSyncInProgress = false
    private var currentDashboardAddress: String? = null

    companion object {
        private const val TAG = "ReaderManagementCtrl"
    }

    private fun logAndToast(msgResId: Int, isShort: Boolean = true) {
        interactionProvider.showToast(msgResId, isShort)
    }

    fun setupReaderManagementView(address: String? = null) {
        currentDashboardAddress = address ?: orchestrator.connectedDeviceAddress

        interactionProvider.setupReaderManagementUI(
            onEditDeviceRequested = { handleEditDevice() },
            onSyncTimeRequested = { handleSyncTime() },
            onForgetDeviceRequested = { handleForgetDevice() },
            onRefreshRequested = { handleRefreshRequested() },
            onDisconnectRequested = { handleDisconnect() },
            onConnectRequested = { handleConnect() },
            onBacklogItemLongClicked = { item -> handleBacklogItemLongClick(item) }
        )

        updateHeader()

        dashboardUIJob?.cancel()
        dashboardUIJob = scope.launch(mainDispatcher) {
            kotlinx.coroutines.flow.combine(
                orchestrator.connectionState,
                orchestrator.isAuthenticated
            ) { state, auth -> state to auth }
                .distinctUntilChanged()
                .collect { (state, auth) ->
                    val isReady = state == ReaderOrchestrator.ConnectionState.CONNECTED && auth
                    val isConnecting = state == ReaderOrchestrator.ConnectionState.CONNECTING ||
                            (state == ReaderOrchestrator.ConnectionState.CONNECTED && !auth)

                    interactionProvider.updateReaderManagementStatus(isReady, isConnecting)

                    if (isReady) {
                        orchestrator.setAppMode(
                            AppMode.MANAGEMENT,
                            "Dashboard Reactivation"
                        )
                        interactionProvider.setManagementRefreshing(true)
                        refreshManagementData("State Transition")
                    } else if (!isConnecting) {
                        updateHeader() // Reset to "--"
                    }
                }
        }

        metricsJob?.cancel()
        metricsJob = scope.launch(mainDispatcher) {
            orchestrator.metricsFlow.collect { (epoch, battery) ->
                val timeStr =
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epoch * 1000L))
                val batteryStr = "$battery%"
                interactionProvider.updateReaderManagementHeader(
                    deviceName = secureStoreManager.deviceName,
                    deviceMac = orchestrator.connectedDeviceAddress ?: "XX:XX:XX:XX:XX:XX",
                    batteryLevel = batteryStr,
                    deviceTime = timeStr,
                    backlogCount = backlogItems.size.toString()
                )
            }
        }

        inventoryJob?.cancel()
        inventoryJob = scope.launch(mainDispatcher) {
            orchestrator.inventoryFlow.collect { (rawTagId, timestamp) ->
                if (rawTagId == "SYNC_DONE") {
                    if (isSyncInProgress) {
                        backlogItems.removeAll { it !in receivedInSync }
                        interactionProvider.updateReaderManagementBacklog(backlogItems.toList())
                        isSyncInProgress = false
                    }
                    interactionProvider.setManagementRefreshing(false)
                    updateHeader()
                } else if (rawTagId == "DEL_OK") {
                    logAndToast(R.string.toast_backlog_deleted_success)
                    refreshManagementData("Post-Deletion Refresh")
                } else {
                    val tagId = rawTagId.chunked(2).joinToString(":")
                    val student = withContext(ioDispatcher) {
                        db.getStudentByRfid(tagId)
                    }
                    val item = BacklogItem(tagId, student, timestamp)
                    if (isSyncInProgress) receivedInSync.add(item)
                    if (!backlogItems.contains(item)) {
                        backlogItems.add(item)
                        backlogItems.sortByDescending { it.timestamp }
                        interactionProvider.updateReaderManagementBacklog(backlogItems.toList())
                    }
                    updateHeader()
                }
            }
        }
    }

    private fun handleEditDevice() {
        val addr = orchestrator.connectedDeviceAddress
        if (addr != null) {
            interactionProvider.showEditReaderDialog(secureStoreManager.deviceName) { newName, newPass ->
                orchestrator.updateReaderConfig(newName, newPass)
                secureStoreManager.clearCredentialsFor(secureStoreManager.deviceName)
                secureStoreManager.saveReaderCredentials(newName, newPass)
                secureStoreManager.deviceName = newName
                logAndToast(R.string.toast_config_update_sent)
                rebootAndReconnect(newName, newPass, addr)
                updateHeader()
            }
        }
    }

    private fun handleSyncTime() {
        if (orchestrator.isAuthenticated.value) {
            orchestrator.syncTime()
            logAndToast(R.string.action_sync_time)
        }
    }

    private fun handleForgetDevice() {
        val name = secureStoreManager.deviceName
        orchestrator.disconnect(disableAutoReconnect = true)
        secureStoreManager.clearCredentialsFor(name)
        resetDashboardUI()
    }

    private fun handleRefreshRequested() {
        if (orchestrator.isAuthenticated.value) {
            refreshManagementData("Manual Pull-to-Refresh")
        } else {
            interactionProvider.setManagementRefreshing(false)
            logAndToast(R.string.status_not_found)
        }
    }

    private fun handleDisconnect() {
        orchestrator.disconnect(disableAutoReconnect = true)
        resetDashboardUI()
    }

    private fun handleConnect() {
        val targetAddr = currentDashboardAddress
            ?: orchestrator.connectedDeviceAddress ?: ""
        val name = secureStoreManager.deviceName
        val pass = secureStoreManager.getAuthPasswordFor(name)
        if (pass != null) orchestrator.startConnecting(name, pass, targetAddr, true)
    }

    private fun refreshManagementData(caller: String) {
        receivedInSync.clear()
        isSyncInProgress = true
        scope.launch {
            delay(500)
            orchestrator.requestStatus()
            delay(1000)
            orchestrator.requestInventory()
            delay(5000)
            withContext(mainDispatcher) {
                // Check if still refreshing
                // We don't have a direct "isRefreshing" getter in the provider anymore, 
                // but we can just force it false and toast.
                interactionProvider.setManagementRefreshing(false)
                if (isSyncInProgress) {
                    isSyncInProgress = false
                    logAndToast(R.string.toast_device_communication_time_out)
                }
            }
        }
    }

    private fun resetDashboardUI() {
        backlogItems.clear()
        interactionProvider.updateReaderManagementBacklog(emptyList())
        interactionProvider.setManagementRefreshing(false)
        updateHeader()
        orchestrator.setAppMode(AppMode.IDLE, "Dashboard Teardown")
    }

    private fun updateHeader() {
        val mac = orchestrator.connectedDeviceAddress ?: "XX:XX:XX:XX:XX:XX"
        val activeDevice = orchestrator.discoveredDevices.value.find { it.address == mac }

        val batteryStr = activeDevice?.batteryLevel?.let { "$it%" } ?: "--%"
        val timeStr = activeDevice?.deviceEpoch?.let { epoch ->
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epoch * 1000L))
        } ?: "--"

        interactionProvider.updateReaderManagementHeader(
            deviceName = secureStoreManager.deviceName,
            deviceMac = interactionProvider.getString(R.string.label_device_mac, mac),
            batteryLevel = batteryStr,
            deviceTime = timeStr,
            backlogCount = backlogItems.size.toString()
        )
    }

    fun teardownView() {
        orchestrator.setAppMode(AppMode.IDLE, "Management Dashboard Exit")
        metricsJob?.cancel()
        inventoryJob?.cancel()
        dashboardUIJob?.cancel()
        currentDashboardAddress = null
        backlogItems.clear()
        receivedInSync.clear()
        isSyncInProgress = false
    }

    internal fun handleBacklogItemLongClick(item: BacklogItem) {
        val studentName =
            item.student?.name ?: interactionProvider.getString(R.string.label_unknown_student)
        interactionProvider.showDestructiveDeleteDialog(
            title = interactionProvider.getString(R.string.delete_action_text),
            message = interactionProvider.getString(
                R.string.dialog_delete_session_message,
                studentName
            ),
            onConfirmed = {
                orchestrator.deleteBacklogItem(item.tagId, item.timestamp)
            }
        )
    }

    private fun rebootAndReconnect(newName: String, newPass: String, address: String) {
        interactionProvider.setManagementRefreshing(true)
        orchestrator.rebootReader(newName, newPass, address)
    }
}
