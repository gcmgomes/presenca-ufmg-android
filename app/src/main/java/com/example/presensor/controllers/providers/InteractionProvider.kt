package com.example.presensor.controllers.providers

import android.content.ContentResolver
import android.content.Context
import com.example.presensor.controllers.items.ActionItem
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.items.DeviceItem
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.AttendanceRecord

/**
 * Universal UI actions available to all controllers.
 */
interface InteractionProvider {
    fun showToast(message: String, isShort: Boolean = true)
    fun showToast(resId: Int, isShort: Boolean = true)
    fun toggleLoading(show: Boolean)
    fun getString(resId: Int): String
    fun getString(resId: Int, vararg formatArgs: Any): String
    fun getContext(): Context
    fun getContentResolver(): ContentResolver
    fun dismissActiveDialog()
    fun isAnyDialogOpen(): Boolean
    fun setLoadingJob(job: kotlinx.coroutines.Job?)
    fun showMappingDialog(
        fields: List<String>,
        columns: List<String>,
        sampleRow: List<String>?,
        onDismissed: () -> Unit,
        onConfirmed: (Map<String, String>) -> Unit
    )

    fun showManualRegistrationDialog(
        rfid: String,
        onStudentSaved: (name: String, email: String, dialog: Any) -> Unit
    )
}

/**
 * Specialized provider for Tag discovery and registration logic.
 */
interface TagInteractionProvider : InteractionProvider {
    fun toggleNfcScanning(enabled: Boolean, callback: Any? = null)
    fun showOverwriteConfirmation(
        existingStudent: Student,
        newRfid: String,
        onConfirm: () -> Unit
    )

    fun showBindingDialog(
        newRfid: String,
        allStudents: List<Student>,
        onStudentSelected: (Student) -> Unit,
        onManualAttendance: () -> Unit,
        onReassignConfirmed: (Student) -> Unit
    )
}

/**
 * Specialized provider for Student-related import and mapping.
 */
interface StudentInteractionProvider : InteractionProvider {
    fun showStudentImportPreview(
        students: List<Student>,
        onConfirm: (List<Student>) -> Unit,
        onDismiss: () -> Unit
    )
}

/**
 * Specialized provider for Session/Attendance management.
 */
interface SessionInteractionProvider : InteractionProvider {
    fun showSessionImportPreview(
        sessions: List<Session>,
        onConfirm: (List<Session>) -> Unit,
        onDismiss: () -> Unit
    )

    fun showEditSessionDialog(
        session: Session,
        onSessionUpdated: (newName: String, newDateMillis: Long) -> Unit
    )

    fun showCreateSessionDialog(
        courseId: Long,
        onSessionCreated: (Long, String, Long) -> Unit
    )

    fun showDeleteSessionDialog(session: Session)
    fun showMassDateChangeDialog(courseId: Long)
    fun showUnlockDialog(
        sessionName: String,
        onUnlocked: () -> Unit
    )

    fun updateSessionCard(name: String, date: Long, accentColor: Int)
    fun updateLockState(isLocked: Boolean)
    fun submitAttendanceList(records: List<AttendanceRecord>, scrollToPosition: Int? = null)
    fun showLayoutRefreshSpinner(show: Boolean)
    fun setOnRefreshListener(listener: () -> Unit)
    fun setupSessionListeners(onLockClicked: () -> Unit, onEditClicked: () -> Unit)
    fun showStudentSearchDialog(
        allStudents: List<Student>,
        onStudentSelected: (Student) -> Unit,
        onManualRegistrationRequested: () -> Unit
    )
}

/**
 * Specialized provider for Reader connectivity and management.
 */
interface ReaderInteractionProvider : InteractionProvider {
    fun showPasswordPromptDialog(
        readerName: String,
        onPasswordEntered: (String) -> Unit,
        onDismissed: () -> Unit
    )

    fun showEditReaderDialog(
        readerName: String,
        onConfigSaved: (newName: String, newPass: String) -> Unit
    )

    fun showDestructiveDeleteDialog(
        title: String,
        message: String,
        onConfirmed: () -> Unit
    )

    fun showBacklogImportPreview(
        onConfirm: (List<BacklogItem>) -> Unit,
        onDismiss: () -> Unit
    )

    fun addBacklogItem(item: BacklogItem)
    fun removeBacklogItem(item: BacklogItem)
    fun updateBacklogCount(count: Int)
    fun toggleBacklogImportLoading(show: Boolean)
    fun getBacklogItemCount(): Int

    fun setupReaderDiscoveryUI(
        onReaderEnabledChanged: (Boolean) -> Unit,
        onRefreshRequested: () -> Unit
    )

    fun updateDeviceList(
        connected: List<DeviceItem>,
        known: List<DeviceItem>,
        unknown: List<DeviceItem>,
        onDeviceSelected: (String, String) -> Unit,
        onDeviceLongClicked: (String, String) -> Unit
    )

    fun setReaderEnabledState(enabled: Boolean)
    fun setDiscoveryRefreshing(isRefreshing: Boolean)
    fun openDeviceManager(name: String, address: String)

    fun setupReaderManagementUI(
        onEditDeviceRequested: () -> Unit,
        onSyncTimeRequested: () -> Unit,
        onForgetDeviceRequested: () -> Unit,
        onRefreshRequested: () -> Unit,
        onDisconnectRequested: () -> Unit,
        onConnectRequested: () -> Unit,
        onBacklogItemLongClicked: (BacklogItem) -> Unit
    )

    fun updateReaderManagementHeader(
        deviceName: String,
        deviceMac: String,
        batteryLevel: String?,
        deviceTime: String?,
        backlogCount: String
    )

    fun updateReaderManagementBacklog(items: List<BacklogItem>)

    fun updateReaderManagementStatus(
        isReady: Boolean,
        isConnecting: Boolean
    )

    fun setManagementRefreshing(isRefreshing: Boolean)
}

/**
 * Specialized provider for Course management.
 */
interface CourseInteractionProvider : InteractionProvider {
    fun setupCourseUtilsAccordion(
        onHeaderClicked: (isExpanded: Boolean) -> Unit
    )

    fun setUtilsExpandIconRotation(rotation: Float)
    fun setUtilsContentVisibility(visible: Boolean)
    fun refreshSessionsList(
        sessions: List<Session>,
        onSessionSelected: (Session) -> Unit,
        onToggleLockRequested: (Session) -> Unit,
        onEditSessionRequested: (Session) -> Unit,
        onDeleteSessionRequested: (Session) -> Unit
    )

    fun updateCourseHeader(
        course: Course,
        sessionIds: Set<Long>,
        studentEmails: Set<String>,
        attendance: List<AttendanceRecord>
    )

    fun setupQuickActions(
        actions: List<ActionItem>,
        titles: List<String>
    )

    fun launchExportPicker(fileName: String)
    fun launchImportPicker()
    fun registerImportSessionLauncher(callback: (android.net.Uri) -> Unit)
    fun registerExportLauncher(callback: (android.net.Uri) -> Unit)

    fun triggerCloudScheduleImport(onImportComplete: () -> Unit)
    fun triggerCloudAttendanceExport()

    fun openOutputStream(uri: android.net.Uri): java.io.OutputStream?
    fun showEditCourseDialog(course: Course, onCourseEdited: () -> Unit)
    fun showCreateCourseDialog(onCourseCreated: () -> Unit)
    fun showCreateSessionDialog(courseId: Long, onSessionCreated: (Long, String, Long) -> Unit)
    fun showMassDateChangeDialog(courseId: Long)
    fun showDeleteSessionDialog(session: Session)

    fun importSessionsFromCsv(uri: android.net.Uri, courseId: Long, onImportComplete: () -> Unit)
}

/**
 * Specialized provider for Course Statistics/Detailed Roster.
 */
interface DetailedCourseInteractionProvider : InteractionProvider {
    fun openDetailedCourseView(
        onEditCourseRequested: () -> Unit,
        onSearchQueryChanged: (String) -> Unit
    )

    fun updateDetailedCourseHeader(
        course: Course,
        sessionIds: Set<Long>,
        studentEmails: Set<String>,
        attendance: List<AttendanceRecord>
    )

    fun updateStudentStatsList(
        students: List<Student>,
        allSessions: List<Session>,
        allAttendance: List<AttendanceRecord>,
        getColorFromAttr: (Int) -> Int
    )
}

/**
 * Specialized provider for Cloud Sync operations.
 */
interface CloudInteractionProvider : InteractionProvider {
    fun runWithCloudAuthentication(
        onAuthSuccess: (accessToken: String) -> Unit
    )

    fun <T> showCloudFileDialog(
        title: String,
        subtitle: String,
        driveItems: List<T>,
        getName: (T) -> String,
        onItemSelected: (T) -> Unit
    )
}
