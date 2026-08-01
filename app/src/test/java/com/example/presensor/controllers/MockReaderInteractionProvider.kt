package com.example.presensor.controllers

import android.content.ContentResolver
import android.content.Context
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.items.DeviceItem
import com.example.presensor.controllers.providers.ReaderInteractionProvider
import kotlinx.coroutines.Job
import org.mockito.kotlin.mock

/**
 * A test-only implementation of ReaderInteractionProvider.
 * Captures calls to UI methods for verification in unit tests.
 */
class MockReaderInteractionProvider : ReaderInteractionProvider {

    var lastToastResId: Int? = null
    var lastToastMessage: String? = null
    var lastToastIsShort: Boolean? = null

    var onPasswordEntered: ((String) -> Unit)? = null
    var onPasswordDismissed: (() -> Unit)? = null
    var lastPasswordReaderName: String? = null

    var onConfigSaved: ((String, String) -> Unit)? = null
    var lastEditReaderName: String? = null

    var onDestructiveConfirmed: (() -> Unit)? = null
    var lastDestructiveTitle: String? = null
    var lastDestructiveMessage: String? = null

    override fun showToast(message: String, isShort: Boolean) {
        lastToastMessage = message
        lastToastIsShort = isShort
    }

    override fun showToast(resId: Int, isShort: Boolean) {
        lastToastResId = resId
        lastToastIsShort = isShort
    }

    override fun toggleLoading(show: Boolean) {}
    override fun getString(resId: Int): String = "Mock String"
    override fun getString(resId: Int, vararg formatArgs: Any): String = "Mock String"
    override fun getContext(): Context = mock()
    override fun getContentResolver(): ContentResolver = mock()
    override fun dismissActiveDialog() {}
    override fun isAnyDialogOpen(): Boolean = false
    override fun setLoadingJob(job: Job?) {}
    override fun showMappingDialog(
        fields: List<String>,
        columns: List<String>,
        sampleRow: List<String>?,
        onDismissed: () -> Unit,
        onConfirmed: (Map<String, String>) -> Unit
    ) {
    }

    override fun showManualRegistrationDialog(
        rfid: String,
        onStudentSaved: (name: String, email: String, dialog: Any) -> Unit
    ) {
    }

    override fun showPasswordPromptDialog(
        readerName: String,
        onPasswordEntered: (String) -> Unit,
        onDismissed: () -> Unit
    ) {
        lastPasswordReaderName = readerName
        this.onPasswordEntered = onPasswordEntered
        this.onPasswordDismissed = onDismissed
    }

    override fun showEditReaderDialog(
        readerName: String,
        onConfigSaved: (newName: String, newPass: String) -> Unit
    ) {
        lastEditReaderName = readerName
        this.onConfigSaved = onConfigSaved
    }

    override fun showDestructiveDeleteDialog(
        title: String,
        message: String,
        onConfirmed: () -> Unit
    ) {
        lastDestructiveTitle = title
        lastDestructiveMessage = message
        this.onDestructiveConfirmed = onConfirmed
    }

    override fun showBacklogImportPreview(
        onConfirm: (List<BacklogItem>) -> Unit,
        onDismiss: () -> Unit
    ) {
    }

    override fun addBacklogItem(item: BacklogItem) {}
    override fun removeBacklogItem(item: BacklogItem) {}
    override fun updateBacklogCount(count: Int) {}
    override fun toggleBacklogImportLoading(show: Boolean) {}
    override fun getBacklogItemCount(): Int = 0

    override fun setupReaderDiscoveryUI(
        onReaderEnabledChanged: (Boolean) -> Unit,
        onRefreshRequested: () -> Unit
    ) {
    }

    override fun updateDeviceList(
        connected: List<DeviceItem>,
        known: List<DeviceItem>,
        unknown: List<DeviceItem>,
        onDeviceSelected: (String, String) -> Unit,
        onDeviceLongClicked: (String, String) -> Unit
    ) {
    }

    override fun setReaderEnabledState(enabled: Boolean) {}
    override fun setDiscoveryRefreshing(isRefreshing: Boolean) {}
    override fun openDeviceManager(name: String, address: String) {}

    override fun setupReaderManagementUI(
        onEditDeviceRequested: () -> Unit,
        onSyncTimeRequested: () -> Unit,
        onForgetDeviceRequested: () -> Unit,
        onRefreshRequested: () -> Unit,
        onDisconnectRequested: () -> Unit,
        onConnectRequested: () -> Unit,
        onBacklogItemLongClicked: (BacklogItem) -> Unit
    ) {
    }

    override fun updateReaderManagementHeader(
        deviceName: String,
        deviceMac: String,
        batteryLevel: String?,
        deviceTime: String?,
        backlogCount: String
    ) {
    }

    override fun updateReaderManagementBacklog(items: List<BacklogItem>) {}

    override fun updateReaderManagementStatus(isReady: Boolean, isConnecting: Boolean) {}

    override fun setManagementRefreshing(isRefreshing: Boolean) {}
}
