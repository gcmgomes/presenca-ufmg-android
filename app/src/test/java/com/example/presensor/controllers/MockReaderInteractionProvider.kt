package com.example.presensor.controllers

import android.content.ContentResolver
import android.content.Context
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.items.DeviceItem
import com.example.presensor.controllers.providers.ReaderInteractionProvider
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.ImportResult
import kotlinx.coroutines.Job
import org.mockito.kotlin.mock

class MockReaderInteractionProvider : ReaderInteractionProvider {

    var lastToastResId: Int? = null
    var lastToastMessage: String? = null
    var lastToastIsShort: Boolean? = null

    var onPasswordEntered: ((String) -> Unit)? = null
    var onPasswordDismissed: (() -> Unit)? = null
    var lastPasswordReaderName: String? = null

    var onConfigSaved: ((String, String) -> Unit)? = null
    var lastEditReaderName: String? = null

    var onReaderEnabledChanged: ((Boolean) -> Unit)? = null
    var onRefreshRequestedReader: (() -> Unit)? = null

    var lastConnectedList: List<DeviceItem>? = null
    var lastKnownList: List<DeviceItem>? = null
    var lastUnknownList: List<DeviceItem>? = null
    var onDeviceSelected: ((String, String) -> Unit)? = null
    var onDeviceLongClicked: ((String, String) -> Unit)? = null

    var lastReaderEnabledState: Boolean? = null
    var lastDiscoveryRefreshing: Boolean? = null
    var lastOpenDeviceManagerName: String? = null
    var lastOpenDeviceManagerAddress: String? = null

    var onDestructiveConfirmed: (() -> Unit)? = null
    var lastDestructiveTitle: String? = null
    var lastDestructiveMessage: String? = null

    var onEditDeviceRequested: (() -> Unit)? = null
    var onSyncTimeRequested: (() -> Unit)? = null
    var onForgetDeviceRequested: (() -> Unit)? = null
    var onRefreshRequested: (() -> Unit)? = null
    var onDisconnectRequested: (() -> Unit)? = null
    var onConnectRequested: (() -> Unit)? = null
    var onBacklogItemLongClicked: ((BacklogItem) -> Unit)? = null

    var lastHeaderDeviceName: String? = null
    var lastHeaderDeviceMac: String? = null
    var lastHeaderBatteryLevel: String? = null
    var lastHeaderDeviceTime: String? = null
    var lastHeaderBacklogCount: String? = null

    var lastBacklogItems: List<BacklogItem>? = null
    var lastIsReady: Boolean? = null
    var lastIsConnecting: Boolean? = null
    var lastIsManagementRefreshing: Boolean? = null

    override fun showToast(message: String, isShort: Boolean) {
        lastToastMessage = message
        lastToastIsShort = isShort
    }

    override fun showToast(resId: Int, isShort: Boolean) {
        lastToastResId = resId
        lastToastIsShort = isShort
    }

    override fun toggleLoading(show: Boolean) {}
    var lastStringResId: Int? = null
    var lastStringFormatArgs: Array<out Any>? = null

    override fun getString(resId: Int): String {
        lastStringResId = resId
        return "MockString($resId)"
    }
    override fun getString(resId: Int, vararg formatArgs: Any): String {
        lastStringResId = resId
        lastStringFormatArgs = formatArgs
        return "MockString($resId, ${formatArgs.joinToString()})"
    }
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
    ) {}

    override fun showManualRegistrationDialog(
        rfid: String,
        onStudentSaved: (name: String, email: String, dialog: Any) -> Unit
    ) {}

    override suspend fun ingestFromGoogleSheets(
        sheetsService: com.google.api.services.sheets.v4.Sheets,
        spreadsheetId: String,
        range: String,
        caller: String
    ): InternalDataTable = InternalDataTable(emptyList(), emptyList())

    override suspend fun ingestFromCsv(
        uri: android.net.Uri,
        caller: String
    ): InternalDataTable = InternalDataTable(emptyList(), emptyList())

    override fun parseSessionsFromTable(
        table: InternalDataTable,
        course: Course,
        mapping: Map<String, String>?
    ): ImportResult<Session> = ImportResult(emptyList(), emptyList())

    override fun parseStudentsFromTable(
        table: InternalDataTable,
        mapping: Map<String, String>?
    ): ImportResult<Student> = ImportResult(emptyList(), emptyList())

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
    ) {}

    override fun addBacklogItem(item: BacklogItem, shouldAutoSelect: Boolean) {}
    override fun removeBacklogItem(item: BacklogItem) {}
    override fun updateBacklogCount(count: Int) {}
    override fun toggleBacklogImportLoading(show: Boolean) {}
    override fun getBacklogItemCount(): Int = 0

    override fun setupReaderDiscoveryUI(
        onReaderEnabledChanged: (Boolean) -> Unit,
        onRefreshRequested: () -> Unit
    ) {
        this.onReaderEnabledChanged = onReaderEnabledChanged
        this.onRefreshRequestedReader = onRefreshRequested
    }

    override fun updateDeviceList(
        connected: List<DeviceItem>,
        known: List<DeviceItem>,
        unknown: List<DeviceItem>,
        onDeviceSelected: (String, String) -> Unit,
        onDeviceLongClicked: (String, String) -> Unit
    ) {
        lastConnectedList = connected
        lastKnownList = known
        lastUnknownList = unknown
        this.onDeviceSelected = onDeviceSelected
        this.onDeviceLongClicked = onDeviceLongClicked
    }

    override fun setReaderEnabledState(enabled: Boolean) {
        lastReaderEnabledState = enabled
    }

    override fun setDiscoveryRefreshing(isRefreshing: Boolean) {
        lastDiscoveryRefreshing = isRefreshing
    }

    override fun openDeviceManager(name: String, address: String) {
        lastOpenDeviceManagerName = name
        lastOpenDeviceManagerAddress = address
    }

    override fun setupReaderManagementUI(
        onEditDeviceRequested: () -> Unit,
        onSyncTimeRequested: () -> Unit,
        onForgetDeviceRequested: () -> Unit,
        onRefreshRequested: () -> Unit,
        onDisconnectRequested: () -> Unit,
        onConnectRequested: () -> Unit,
        onBacklogItemLongClicked: (BacklogItem) -> Unit
    ) {
        this.onEditDeviceRequested = onEditDeviceRequested
        this.onSyncTimeRequested = onSyncTimeRequested
        this.onForgetDeviceRequested = onForgetDeviceRequested
        this.onRefreshRequested = onRefreshRequested
        this.onDisconnectRequested = onDisconnectRequested
        this.onConnectRequested = onConnectRequested
        this.onBacklogItemLongClicked = onBacklogItemLongClicked
    }

    override fun updateReaderManagementHeader(
        deviceName: String,
        deviceMac: String,
        batteryLevel: String?,
        deviceTime: String?,
        backlogCount: String
    ) {
        lastHeaderDeviceName = deviceName
        lastHeaderDeviceMac = deviceMac
        lastHeaderBatteryLevel = batteryLevel
        lastHeaderDeviceTime = deviceTime
        lastHeaderBacklogCount = backlogCount
    }

    override fun updateReaderManagementBacklog(items: List<BacklogItem>) {
        lastBacklogItems = items
    }

    override fun updateReaderManagementStatus(isReady: Boolean, isConnecting: Boolean) {
        lastIsReady = isReady
        lastIsConnecting = isConnecting
    }

    override fun setManagementRefreshing(isRefreshing: Boolean) {
        lastIsManagementRefreshing = isRefreshing
    }
}
