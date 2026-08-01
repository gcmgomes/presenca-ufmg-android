package com.example.presensor.controllers.providers

import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.adapters.*
import com.example.presensor.controllers.items.*
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory.showWithSmartNfcReading
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.TagControllerDialogFactory
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.tools.TimeUtils
import com.example.presensor.tools.UiUtils
import com.example.presensor.cloud.CourseCloudActions
import com.example.presensor.data.InternalDataTable
import com.example.presensor.tools.DataProcessor
import com.example.presensor.tools.ImportResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class AndroidInteractionProvider(
    private val activity: MainActivity,
    private val secureStoreManager: SecureStoreManager,
    private val tagDialogFactory: TagControllerDialogFactory,
    private val sessionDialogFactory: SessionControllerDialogFactory,
    private val courseDialogFactory: CourseControllerDialogFactory
) : TagInteractionProvider, StudentInteractionProvider, SessionInteractionProvider,
    ReaderInteractionProvider, CloudInteractionProvider, CourseInteractionProvider,
    DetailedCourseInteractionProvider {

    private var activeBottomSheet: BottomSheetDialog? = null
    private var activeAlertDialog: AlertDialog? = null
    private var backlogAdapter: ImportBacklogAdapter? = null
    private var backlogCountText: TextView? = null
    private val attendanceAdapter = AttendanceAdapter()
    private var studentStatsAdapter: StudentStatsAdapter? = null

    private var onDisconnectRequested: (() -> Unit)? = null
    private var onConnectRequested: (() -> Unit)? = null

    private var onImportSessionCallback: ((android.net.Uri) -> Unit)? = null
    private var onExportCallback: ((android.net.Uri) -> Unit)? = null

    private val importSessionLauncher: ActivityResultLauncher<android.content.Intent> =
        activity.activityResultRegistry.register("import_session", activity, ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri -> onImportSessionCallback?.invoke(uri) }
            }
        }

    private val exportLauncher: ActivityResultLauncher<String> =
        activity.activityResultRegistry.register("export_document", activity, ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { onExportCallback?.invoke(it) }
        }

    private var onCloudAuthSuccessCallback: ((String) -> Unit)? = null
    private val cloudSignInLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.activityResultRegistry.register("cloud_sign_in", activity, ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                try {
                    val authorizationClient = Identity.getAuthorizationClient(activity)
                    val authResult = authorizationClient.getAuthorizationResultFromIntent(result.data)
                    authResult.accessToken?.let { token ->
                        onCloudAuthSuccessCallback?.invoke(token)
                    }
                } catch (e: Exception) {
                    showToast(R.string.toast_cloud_auth_failed)
                    Log.e("CloudAuth", "Authorization result processing failed", e)
                }
            } else {
                toggleLoading(false)
            }
        }

    private var courseCloudActions: CourseCloudActions? = null

    fun initializeCourseCloudActions(getSelectedCourse: () -> Course?, onImportComplete: () -> Unit) {
        courseCloudActions = CourseCloudActions(
            activity = activity,
            lifecycleOwner = activity,
            db = activity.getDb(),
            getSelectedCourse = getSelectedCourse,
            onImportComplete = onImportComplete
        )
    }

    // --- Base Interaction ---

    override fun showToast(message: String, isShort: Boolean) {
        activity.runOnUiThread {
            Toast.makeText(
                activity,
                message,
                if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun showToast(resId: Int, isShort: Boolean) {
        activity.runOnUiThread {
            Toast.makeText(activity, resId, if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun toggleLoading(show: Boolean) {
        activity.runOnUiThread {
            activity.toggleLoadingOverlay(show)
        }
    }

    override fun getString(resId: Int): String = activity.getString(resId)

    override fun getString(resId: Int, vararg formatArgs: Any): String =
        activity.getString(resId, *formatArgs)

    override fun getContext(): android.content.Context = activity

    override fun getContentResolver(): android.content.ContentResolver = activity.contentResolver

    override fun showMappingDialog(
        fields: List<String>,
        columns: List<String>,
        sampleRow: List<String>?,
        onDismissed: () -> Unit,
        onConfirmed: (Map<String, String>) -> Unit
    ) {
        activity.runOnUiThread {
            activeAlertDialog = DialogFactory.showMappingDialog(
                activity,
                fields,
                columns,
                sampleRow,
                onDismissed,
                onConfirmed
            )
        }
    }

    override fun dismissActiveDialog() {
        activity.runOnUiThread {
            activeBottomSheet?.dismiss()
            activeBottomSheet = null
            activeAlertDialog?.dismiss()
            activeAlertDialog = null
        }
    }

    override fun isAnyDialogOpen(): Boolean = DialogFactory.isAnyDialogOpen()

    override fun setLoadingJob(job: kotlinx.coroutines.Job?) {
        activity.setCurrentOverlayJob(job)
    }

    override suspend fun ingestFromGoogleSheets(
        sheetsService: com.google.api.services.sheets.v4.Sheets,
        spreadsheetId: String,
        range: String,
        caller: String
    ): InternalDataTable {
        return DataProcessor.ingestFromGoogleSheets(activity, sheetsService, spreadsheetId, range, caller)
    }

    override suspend fun ingestFromCsv(
        uri: android.net.Uri,
        caller: String
    ): InternalDataTable {
        return DataProcessor.ingestFromCsv(activity.contentResolver, uri, caller)
    }

    override fun parseSessionsFromTable(
        table: InternalDataTable,
        courseId: Long,
        mapping: Map<String, String>?
    ): ImportResult<Session> {
        return DataProcessor.parseSessionsFromTable(activity, table, courseId, mapping)
    }

    override fun parseStudentsFromTable(
        table: InternalDataTable,
        mapping: Map<String, String>?
    ): ImportResult<Student> {
        return DataProcessor.parseStudentsFromTable(activity, table, mapping)
    }

    // --- Tag Interaction ---

    override fun toggleNfcScanning(enabled: Boolean, callback: Any?) {
        activity.runOnUiThread {
            val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return@runOnUiThread
            if (enabled) {
                val options = Bundle().apply {
                    putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
                }

                var readerFlags =
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

                if (DialogFactory.isAnyDialogOpen()) {
                    readerFlags = readerFlags or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                }

                if (callback is NfcAdapter.ReaderCallback) {
                    adapter.enableReaderMode(activity, callback, readerFlags, options)
                }
            } else {
                adapter.disableReaderMode(activity)
            }
        }
    }

    override fun showOverwriteConfirmation(
        existingStudent: Student,
        newRfid: String,
        onConfirm: () -> Unit
    ) {
        activity.runOnUiThread {
            activeAlertDialog =
                tagDialogFactory.showOverwriteConfirmation(existingStudent, newRfid, onConfirm)
        }
    }

    override fun showBindingDialog(
        newRfid: String,
        allStudents: List<Student>,
        onStudentSelected: (Student) -> Unit,
        onManualAttendance: () -> Unit,
        onReassignConfirmed: (Student) -> Unit
    ) {
        activity.runOnUiThread {
            activeBottomSheet = tagDialogFactory.showBindingDialog(
                newRfid,
                allStudents,
                onStudentSelected,
                onManualAttendance,
                onReassignConfirmed
            )
        }
    }

    override fun showManualRegistrationDialog(
        rfid: String,
        onStudentSaved: (name: String, email: String, dialog: Any) -> Unit
    ) {
        activity.runOnUiThread {
            activeAlertDialog =
                sessionDialogFactory.showManualRegistrationDialog(rfid) { name, email, dialog ->
                    onStudentSaved(name, email, dialog)
                }
        }
    }

    // --- Student Interaction ---

    override fun showStudentImportPreview(
        students: List<Student>,
        onConfirm: (List<Student>) -> Unit,
        onDismiss: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_list_preview, null)
            val rvPreview = dialogView.findViewById<RecyclerView>(R.id.rvPreviewList)
            val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmAction)
            val txtTitle = dialogView.findViewById<TextView>(R.id.txtPreviewTitle)
            val txtHint = dialogView.findViewById<TextView>(R.id.txtPreviewHint)

            txtTitle.text = activity.getString(R.string.dialog_import_students)
            txtHint.text = activity.getString(R.string.dialog_import_students_hint, students.size)
            btnConfirm.text = activity.getString(R.string.dialog_import_students_button_text)

            val adapter = ImportStudentAdapter()
            rvPreview.layoutManager = LinearLayoutManager(activity)
            rvPreview.adapter = adapter
            adapter.submitList(students)

            val dialog = BottomSheetDialog(activity)
            activeBottomSheet = dialog
            dialog.setContentView(dialogView)

            btnConfirm.setOnClickListener {
                onConfirm(adapter.getSelectedItems())
                dialog.dismiss()
            }

            dialog.setOnDismissListener {
                activeBottomSheet = null
                onDismiss()
            }

            with(DialogFactory) {
                dialog.showWithSmartNfcReading()
            }
        }
    }

    // --- Session Interaction ---

    override fun showSessionImportPreview(
        sessions: List<Session>,
        onConfirm: (List<Session>) -> Unit,
        onDismiss: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_list_preview, null)
            val rvPreview = dialogView.findViewById<RecyclerView>(R.id.rvPreviewList)
            val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmAction)
            val txtTitle = dialogView.findViewById<TextView>(R.id.txtPreviewTitle)
            val txtHint = dialogView.findViewById<TextView>(R.id.txtPreviewHint)

            txtTitle.text = activity.getString(R.string.dialog_import_sessions)
            txtHint.text = activity.getString(R.string.dialog_import_sessions_hint, sessions.size)
            btnConfirm.text = activity.getString(R.string.dialog_import_sessions_button_text)

            val adapter = ImportPreviewAdapter()
            rvPreview.layoutManager = LinearLayoutManager(activity)
            rvPreview.adapter = adapter
            adapter.submitList(sessions)

            val dialog = BottomSheetDialog(activity)
            activeBottomSheet = dialog
            dialog.setContentView(dialogView)

            btnConfirm.setOnClickListener {
                onConfirm(adapter.getSelectedItems())
                dialog.dismiss()
            }

            dialog.setOnDismissListener {
                activeBottomSheet = null
                onDismiss()
            }

            with(DialogFactory) {
                dialog.showWithSmartNfcReading()
            }
        }
    }

    override fun showEditSessionDialog(
        session: Session,
        onSessionUpdated: (newName: String, newDateMillis: Long) -> Unit
    ) {
        activity.runOnUiThread {
            activeAlertDialog =
                sessionDialogFactory.showEditSessionDialog(session, onSessionUpdated)
        }
    }

    override fun showCreateSessionDialog(
        courseId: Long,
        onSessionCreated: (Long, String, Long) -> Unit
    ) {
        activity.runOnUiThread {
            sessionDialogFactory.showCreateSessionDialog(courseId, onSessionCreated)
        }
    }

    override fun showUnlockDialog(sessionName: String, onUnlocked: () -> Unit) {
        activity.runOnUiThread {
            val input = EditText(activity).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }

            activeAlertDialog = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.dialog_unlock_title, sessionName))
                .setMessage(activity.getString(R.string.dialog_unlock_message))
                .setView(input)
                .setPositiveButton(activity.getString(R.string.action_unlock), null)
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()

            activeAlertDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (input.text.toString() == sessionName) {
                    onUnlocked()
                    activeAlertDialog?.dismiss()
                } else {
                    showToast(R.string.error_incorrect_password)
                }
            }
        }
    }

    override fun updateSessionCard(name: String, date: Long, accentColor: Int) {
        activity.runOnUiThread {
            activity.findViewById<TextView>(R.id.txtSessionTitle)?.text = name
            val dateFormat = TimeUtils.makeSessionTimeFormatter(activity)
            activity.findViewById<TextView>(R.id.txtSessionSubtitle)?.text =
                TimeUtils.fromMillisToLocalDate(date).format(dateFormat)
            activity.findViewById<View>(R.id.viewSessionDetailAccent)
                ?.setBackgroundColor(accentColor)
        }
    }

    override fun updateLockState(isLocked: Boolean) {
        activity.runOnUiThread {
            val imgMasterLock = activity.findViewById<ImageView>(R.id.imgMasterLock)
            val btnEditSession = activity.findViewById<ImageView>(R.id.btnEditSessionInternal)
            if (imgMasterLock != null) UiUtils.updateLockIconUI(isLocked, imgMasterLock)
            if (btnEditSession != null) UiUtils.updateEditIconUI(isLocked, btnEditSession)
        }
    }

    override fun submitAttendanceList(records: List<AttendanceRecord>, scrollToPosition: Int?) {
        activity.runOnUiThread {
            val rv = activity.findViewById<RecyclerView>(R.id.rvAttendance) ?: return@runOnUiThread
            if (rv.adapter != attendanceAdapter) {
                rv.layoutManager = LinearLayoutManager(activity)
                rv.adapter = attendanceAdapter
            }
            attendanceAdapter.submitList(records) {
                if (scrollToPosition != null) {
                    rv.smoothScrollToPosition(scrollToPosition)
                }
            }
        }
    }

    override fun showLayoutRefreshSpinner(show: Boolean) {
        activity.runOnUiThread {
            activity.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshLayout)?.isRefreshing =
                show
        }
    }

    override fun setOnRefreshListener(listener: () -> Unit) {
        activity.runOnUiThread {
            activity.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshLayout)
                ?.setOnRefreshListener {
                    listener()
                }
        }
    }

    override fun setupSessionListeners(onLockClicked: () -> Unit, onEditClicked: () -> Unit) {
        activity.runOnUiThread {
            activity.findViewById<View>(R.id.imgMasterLock)?.setOnClickListener { onLockClicked() }
            activity.findViewById<View>(R.id.btnEditSessionInternal)
                ?.setOnClickListener { onEditClicked() }

            val rv = activity.findViewById<RecyclerView>(R.id.rvAttendance)
            if (rv != null && (rv.adapter != attendanceAdapter)) {
                rv.layoutManager = LinearLayoutManager(activity)
                rv.adapter = attendanceAdapter
            }
        }
    }

    override fun showStudentSearchDialog(
        allStudents: List<Student>,
        onStudentSelected: (Student) -> Unit,
        onManualRegistrationRequested: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_search_student, null)
            val edtSearch = dialogView.findViewById<EditText>(R.id.edtStudentSearch)
            val rvSearch = dialogView.findViewById<RecyclerView>(R.id.rvStudentSearch)
            val btnCreate = dialogView.findViewById<View>(R.id.btnCreateNewStudent)
            val txtHint = dialogView.findViewById<TextView>(R.id.txtSearchStudentHint)

            btnCreate.visibility = View.VISIBLE

            val adapter = StudentSearchAdapter { student ->
                onStudentSelected(student)
            }
            rvSearch.adapter = adapter
            rvSearch.layoutManager = LinearLayoutManager(activity)

            fun refreshList(query: String) {
                val filtered = allStudents.filter {
                    it.name.contains(query, true) || it.email.contains(query, true)
                }
                adapter.submitList(filtered)
                if (filtered.isEmpty()) {
                    txtHint.text = activity.getString(R.string.msg_no_students_found)
                    txtHint.setTextColor(android.graphics.Color.RED)
                } else {
                    txtHint.text = activity.getString(R.string.hint_search_student)
                    txtHint.setTextColor(activity.getColor(R.color.text_secondary))
                }
            }

            refreshList("")

            val dialog = BottomSheetDialog(activity)
            activeBottomSheet = dialog
            dialog.setContentView(dialogView)

            btnCreate.setOnClickListener {
                dialog.dismiss()
                onManualRegistrationRequested()
            }

            dialog.setOnDismissListener {
                activeBottomSheet = null
            }

            with(DialogFactory) {
                dialog.showWithSmartNfcReading()
            }

            edtSearch.addTextChangedListener { refreshList(it.toString()) }
        }
    }

    // --- Reader Interaction ---

    override fun showPasswordPromptDialog(
        readerName: String,
        onPasswordEntered: (String) -> Unit,
        onDismissed: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_reader_password, null)
            val inputField = dialogView.findViewById<TextInputEditText>(R.id.editReaderPassword)

            val dialog = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_assign_tag, readerName))
                .setView(dialogView)
                .setPositiveButton("Connect", null)
                .setNegativeButton(R.string.action_cancel, null)
                .setOnDismissListener { onDismissed() }
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typedPassword = inputField.text.toString().trim()
                if (typedPassword.isNotEmpty()) {
                    onPasswordEntered(typedPassword)
                    dialog.dismiss()
                } else {
                    inputField.error = "Password cannot be blank"
                }
            }
        }
    }

    override fun showEditReaderDialog(
        readerName: String,
        onConfigSaved: (newName: String, newPass: String) -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_edit_reader, null)
            val inputName = dialogView.findViewById<TextInputEditText>(R.id.editReaderName)
            val inputOldPass = dialogView.findViewById<TextInputEditText>(R.id.editOldPassword)
            val inputNewPass = dialogView.findViewById<TextInputEditText>(R.id.editNewPassword)
            val inputConfirmPass =
                dialogView.findViewById<TextInputEditText>(R.id.editConfirmNewPassword)
            inputName.setText(readerName)

            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.action_edit)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, null)
                .setNegativeButton(R.string.action_cancel, null)
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = inputName.text.toString().trim()
                val oldPass = inputOldPass.text.toString()
                val newPass = inputNewPass.text.toString()
                val confirmPass = inputConfirmPass.text.toString()
                val storedPass = secureStoreManager.getAuthPasswordFor(readerName) ?: ""

                when {
                    newName.isEmpty() -> inputName.error =
                        activity.getString(R.string.error_empty_name)

                    oldPass != storedPass -> inputOldPass.error =
                        activity.getString(R.string.error_incorrect_old_password)

                    newPass.isEmpty() -> inputNewPass.error =
                        activity.getString(R.string.error_empty_password)

                    newPass != confirmPass -> inputConfirmPass.error =
                        activity.getString(R.string.error_passwords_mismatch)

                    else -> {
                        onConfigSaved(newName, newPass)
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    override fun showBacklogImportPreview(
        onConfirm: (List<BacklogItem>) -> Unit,
        onDismiss: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_list_preview, null)
            val rvPreview = dialogView.findViewById<RecyclerView>(R.id.rvPreviewList)
            val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmAction)
            backlogCountText = dialogView.findViewById<TextView>(R.id.txtPreviewHint)
            val txtTitle = dialogView.findViewById<TextView>(R.id.txtPreviewTitle)
            val progressBar = dialogView.findViewById<ProgressBar>(R.id.pbPreviewLoading)

            txtTitle.text = activity.getString(R.string.dialog_import_backlog_title)
            btnConfirm.text = activity.getString(R.string.dialog_import_backlog_button_text)
            progressBar.visibility = View.VISIBLE

            backlogAdapter = ImportBacklogAdapter()
            rvPreview.layoutManager = LinearLayoutManager(activity)
            rvPreview.adapter = backlogAdapter

            val dialog = BottomSheetDialog(activity)
            activeBottomSheet = dialog
            dialog.setContentView(dialogView)

            dialog.setOnDismissListener {
                activeBottomSheet = null
                backlogAdapter = null
                backlogCountText = null
                onDismiss()
            }

            btnConfirm.setOnClickListener {
                onConfirm(backlogAdapter?.getSelectedItems() ?: emptyList())
                dialog.dismiss()
            }

            with(DialogFactory) {
                dialog.showWithSmartNfcReading()
            }
        }
    }

    override fun addBacklogItem(item: BacklogItem) {
        activity.runOnUiThread {
            backlogAdapter?.addItem(item)
        }
    }

    override fun removeBacklogItem(item: BacklogItem) {
        activity.runOnUiThread {
            backlogAdapter?.removeItem(item)
        }
    }

    override fun updateBacklogCount(count: Int) {
        activity.runOnUiThread {
            backlogCountText?.text = activity.getString(R.string.dialog_import_backlog_hint, count)
        }
    }

    override fun toggleBacklogImportLoading(show: Boolean) {
        activity.runOnUiThread {
            val dialog = activeBottomSheet ?: return@runOnUiThread
            val progressBar = dialog.findViewById<ProgressBar>(R.id.pbPreviewLoading)
            val btnConfirm = dialog.findViewById<MaterialButton>(R.id.btnConfirmAction)

            progressBar?.visibility = if (show) View.VISIBLE else View.GONE
            btnConfirm?.isEnabled = !show
        }
    }

    override fun getBacklogItemCount(): Int = backlogAdapter?.itemCount ?: 0

    override fun setupReaderDiscoveryUI(
        onReaderEnabledChanged: (Boolean) -> Unit,
        onRefreshRequested: () -> Unit
    ) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutReaderManagementView) ?: return@runOnUiThread
            val switchUseReader =
                rootView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchUseReader)
            val recyclerView = rootView.findViewById<RecyclerView>(R.id.readerRecyclerView)
            val listRefresh =
                rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshReader)

            switchUseReader?.setOnCheckedChangeListener { _, isChecked ->
                onReaderEnabledChanged(isChecked)
            }

            listRefresh?.setOnRefreshListener {
                onRefreshRequested()
            }

            recyclerView?.layoutManager = LinearLayoutManager(activity)
            // Adapter will be set in updateDeviceList
        }
    }

    override fun updateDeviceList(
        connected: List<DeviceItem>,
        known: List<DeviceItem>,
        unknown: List<DeviceItem>,
        onDeviceSelected: (String, String) -> Unit,
        onDeviceLongClicked: (String, String) -> Unit
    ) {
        activity.runOnUiThread {
            val recyclerView =
                activity.findViewById<RecyclerView>(R.id.readerRecyclerView) ?: return@runOnUiThread
            val adapter = if (recyclerView.adapter !is DeviceListAdapter) {
                val newAdapter = DeviceListAdapter(onDeviceSelected, onDeviceLongClicked)
                recyclerView.adapter = newAdapter
                newAdapter
            } else {
                val existingAdapter = recyclerView.adapter as DeviceListAdapter
                existingAdapter.updateCallbacks(onDeviceSelected, onDeviceLongClicked)
                existingAdapter
            }
            adapter.submitList(connected, known, unknown)
        }
    }

    override fun setReaderEnabledState(enabled: Boolean) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutReaderManagementView) ?: return@runOnUiThread
            val switchUseReader =
                rootView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchUseReader)
            val listRefresh =
                rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshReader)

            switchUseReader?.isChecked = enabled
            listRefresh?.isEnabled = enabled
        }
    }

    override fun setDiscoveryRefreshing(isRefreshing: Boolean) {
        activity.runOnUiThread {
            activity.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshReader)?.isRefreshing =
                isRefreshing
        }
    }

    override fun openDeviceManager(name: String, address: String) {
        activity.runOnUiThread {
            secureStoreManager.deviceName = name
            activity.openDeviceManager(address)
        }
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
        this.onDisconnectRequested = onDisconnectRequested
        this.onConnectRequested = onConnectRequested

        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutDeviceManagerView) ?: return@runOnUiThread

            rootView.findViewById<View>(R.id.btnEditDevice)
                ?.setOnClickListener { onEditDeviceRequested() }
            rootView.findViewById<View>(R.id.btnSyncTime)
                ?.setOnClickListener { onSyncTimeRequested() }
            rootView.findViewById<View>(R.id.btnForget)
                ?.setOnClickListener { onForgetDeviceRequested() }

            val swipeRefresh = rootView as? SwipeRefreshLayout
                ?: rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshDeviceManager)
            swipeRefresh?.setOnRefreshListener { onRefreshRequested() }

            val rvBacklog = rootView.findViewById<RecyclerView>(R.id.rvDeviceBacklog)
            rvBacklog?.layoutManager = LinearLayoutManager(activity)
            if (rvBacklog?.adapter !is BacklogAdapter) {
                rvBacklog?.adapter = BacklogAdapter(onBacklogItemLongClicked)
            }
        }
    }

    override fun updateReaderManagementHeader(
        deviceName: String,
        deviceMac: String,
        batteryLevel: String?,
        deviceTime: String?,
        backlogCount: String
    ) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutDeviceManagerView) ?: return@runOnUiThread
            rootView.findViewById<TextView>(R.id.txtDeviceName)?.text = deviceName
            rootView.findViewById<TextView>(R.id.txtDeviceMac)?.text = deviceMac
            rootView.findViewById<TextView>(R.id.txtStatFilesCount)?.text = backlogCount

            if (batteryLevel != null) {
                rootView.findViewById<TextView>(R.id.txtStatBattery)?.text = batteryLevel
            }
            if (deviceTime != null) {
                rootView.findViewById<TextView>(R.id.txtStatDeviceTime)?.text = deviceTime
            }
        }
    }

    override fun updateReaderManagementBacklog(items: List<BacklogItem>) {
        activity.runOnUiThread {
            val rv = activity.findViewById<RecyclerView>(R.id.rvDeviceBacklog)
            (rv?.adapter as? BacklogAdapter)?.submitList(items)
        }
    }

    override fun updateReaderManagementStatus(isReady: Boolean, isConnecting: Boolean) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutDeviceManagerView) ?: return@runOnUiThread
            val viewAccent = rootView.findViewById<View>(R.id.viewDeviceDetailAccent)

            val accentColor = when {
                isReady -> activity.getColor(R.color.chalk_green)
                isConnecting -> activity.getColor(R.color.chalk_orange)
                else -> android.graphics.Color.TRANSPARENT
            }
            viewAccent?.setBackgroundColor(accentColor)

            val btnDisconnect =
                rootView.findViewById<LinearLayout>(R.id.btnDisconnect) ?: return@runOnUiThread
            val imgDisconnect = btnDisconnect.getChildAt(0) as? ImageView
            val txtDisconnect = btnDisconnect.getChildAt(1) as? TextView

            if (isReady || isConnecting) {
                txtDisconnect?.text = activity.getString(R.string.action_disconnect)
                imgDisconnect?.setImageResource(R.drawable.ic_reader_disconnected)
            } else {
                txtDisconnect?.text = activity.getString(R.string.action_connect)
                imgDisconnect?.setImageResource(R.drawable.ic_reader_connected)
            }

            btnDisconnect.setOnClickListener {
                if (isReady || isConnecting) {
                    onDisconnectRequested?.invoke()
                } else {
                    onConnectRequested?.invoke()
                }
            }
        }
    }

    override fun setManagementRefreshing(isRefreshing: Boolean) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutDeviceManagerView) ?: return@runOnUiThread
            val swipeRefresh = rootView as? SwipeRefreshLayout
                ?: rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshDeviceManager)
            swipeRefresh?.isRefreshing = isRefreshing
        }
    }

    override fun showDestructiveDeleteDialog(
        title: String,
        message: String,
        onConfirmed: () -> Unit
    ) {
        activity.runOnUiThread {
            activeAlertDialog =
                DialogFactory.showDestructiveDeleteDialog(activity, title, message, onConfirmed)
        }
    }

    override fun runWithCloudAuthentication(onAuthSuccess: (String) -> Unit) {
        this.onCloudAuthSuccessCallback = onAuthSuccess
        activity.runOnUiThread {
            val authorizationClient = Identity.getAuthorizationClient(activity)
            val requestedScopes = listOf(
                com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.metadata.readonly"),
                com.google.android.gms.common.api.Scope(com.google.api.services.drive.DriveScopes.DRIVE_FILE),
                com.google.android.gms.common.api.Scope(com.google.api.services.sheets.v4.SheetsScopes.SPREADSHEETS)
            )
            val authorizationRequest = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
                .setRequestedScopes(requestedScopes)
                .build()

            authorizationClient.authorize(authorizationRequest)
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        val pendingIntent = result.pendingIntent!!
                        cloudSignInLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                    } else {
                        result.accessToken?.let { token ->
                            onAuthSuccess(token)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    showToast(R.string.toast_cloud_auth_failed)
                }
        }
    }

    override fun <T> showCloudFileDialog(
        title: String,
        subtitle: String,
        driveItems: List<T>,
        getName: (T) -> String,
        onItemSelected: (T) -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_cloud_import, null)
            val txtSubtitle = dialogView.findViewById<TextView>(R.id.dialogSubtitle)
            val listV = dialogView.findViewById<android.widget.ListView>(R.id.backupListView)
            val searchView =
                dialogView.findViewById<androidx.appcompat.widget.SearchView>(R.id.dialogSearchView)

            txtSubtitle.text = subtitle
            val itemMap = driveItems.associateBy { getName(it) }
            val itemNames = driveItems.map { getName(it) }
            val adapter = android.widget.ArrayAdapter(
                activity,
                android.R.layout.simple_list_item_1,
                itemNames
            )
            listV.adapter = adapter

            searchView.setOnQueryTextListener(object :
                androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    adapter.filter.filter(newText)
                    return true
                }
            })

            val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(dialogView)
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()

            listV.setOnItemClickListener { _, _, position, _ ->
                val selectedName = adapter.getItem(position) ?: return@setOnItemClickListener
                val selectedItem = itemMap[selectedName] ?: return@setOnItemClickListener
                dialog.dismiss()
                onItemSelected(selectedItem)
            }
        }
    }

    // --- Course Interaction ---

    override fun registerImportSessionLauncher(callback: (android.net.Uri) -> Unit) {
        this.onImportSessionCallback = callback
    }

    override fun registerExportLauncher(callback: (android.net.Uri) -> Unit) {
        this.onExportCallback = callback
    }

    override fun launchImportPicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "text/comma-separated-values"
            putExtra(
                android.content.Intent.EXTRA_MIME_TYPES,
                arrayOf("text/csv", "text/comma-separated-values", "text/plain")
            )
        }
        importSessionLauncher.launch(intent)
    }

    override fun launchExportPicker(fileName: String) {
        exportLauncher.launch(fileName)
    }

    override fun triggerCloudScheduleImport(onImportComplete: () -> Unit) {
        courseCloudActions?.triggerCloudScheduleImport()
    }

    override fun triggerCloudAttendanceExport() {
        courseCloudActions?.triggerCloudAttendanceExport()
    }

    override fun importSessionsFromCsv(
        uri: android.net.Uri,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        activity.importSessionController.importFromLocal(
            uri = uri,
            courseId = courseId,
            onImportComplete = onImportComplete
        )
    }

    override fun setupCourseUtilsAccordion(onHeaderClicked: (isExpanded: Boolean) -> Unit) {
        activity.runOnUiThread {
            val headerClickArea =
                activity.findViewById<View>(R.id.layoutUtilsHeader) ?: return@runOnUiThread
            val expandableLayout = activity.findViewById<View>(R.id.layoutUtilsContent)
            headerClickArea.setOnClickListener {
                onHeaderClicked(expandableLayout.isVisible)
            }
        }
    }

    override fun setUtilsExpandIconRotation(rotation: Float) {
        activity.runOnUiThread {
            activity.findViewById<View>(R.id.imgUtilsExpandIcon)?.animate()?.rotation(rotation)
                ?.setDuration(300L)?.start()
        }
    }

    override fun setUtilsContentVisibility(visible: Boolean) {
        activity.runOnUiThread {
            val cardRoot = activity.findViewById<View>(R.id.layoutInnerCourseView)
            val expandableLayout = activity.findViewById<View>(R.id.layoutUtilsContent)

            if (cardRoot is android.view.ViewGroup) {
                val transition = androidx.transition.TransitionSet().apply {
                    addTransition(androidx.transition.ChangeBounds())
                    addTransition(androidx.transition.Fade())
                    ordering = androidx.transition.TransitionSet.ORDERING_TOGETHER
                    duration = 300L
                    interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                }
                androidx.transition.TransitionManager.beginDelayedTransition(cardRoot, transition)
            }
            expandableLayout.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    override fun refreshSessionsList(
        sessions: List<Session>,
        onSessionSelected: (Session) -> Unit,
        onToggleLockRequested: (Session) -> Unit,
        onEditSessionRequested: (Session) -> Unit,
        onDeleteSessionRequested: (Session) -> Unit
    ) {
        activity.runOnUiThread {
            val sessionContainer =
                activity.findViewById<LinearLayout>(R.id.sessionContainer) ?: return@runOnUiThread
            sessionContainer.removeAllViews()

            val thisWeekSessions =
                sessions.filter { TimeUtils.isDateInCurrentWeek(TimeUtils.fromMillisToLocalDate(it.date)) }
            val upcomingSessions = sessions.filter {
                !TimeUtils.isDateInCurrentWeek(TimeUtils.fromMillisToLocalDate(it.date)) && TimeUtils.fromMillisToLocalDate(
                    it.date
                ).isAfter(java.time.LocalDate.now())
            }
            val pastSessions = sessions.filter {
                !TimeUtils.isDateInCurrentWeek(TimeUtils.fromMillisToLocalDate(it.date)) && TimeUtils.fromMillisToLocalDate(
                    it.date
                ).isBefore(java.time.LocalDate.now())
            }.sortedBy { it.date }

            fun addSessions(title: String, list: List<Session>) {
                if (list.isNotEmpty()) {
                    com.example.presensor.MainUiBinder.addSectionHeader(sessionContainer, title)
                    list.forEach { session ->
                        val itemView = activity.layoutInflater.inflate(
                            R.layout.item_session_card,
                            sessionContainer,
                            false
                        )
                        itemView.findViewById<View>(R.id.viewSessionAccent)
                            .setBackgroundColor(
                                UiUtils.getColorForAccent(
                                    session.name,
                                    activity.resources.obtainTypedArray(R.array.chalk_colors_list)
                                )
                            )
                        itemView.findViewById<TextView>(R.id.txtSessionName).text = session.name
                        itemView.findViewById<TextView>(R.id.txtSessionDetails).text =
                            TimeUtils.fromMillisToLocalDate(session.date)
                                .format(TimeUtils.makeSessionTimeFormatter(activity))

                        val imgLock =
                            itemView.findViewById<ImageView>(R.id.imgSessionLockOnSessionView)
                        UiUtils.updateLockIconUI(session.isLocked, imgLock)
                        imgLock.setOnClickListener { onToggleLockRequested(session) }

                        val editBtn = itemView.findViewById<ImageView>(R.id.btnEditSession)
                        UiUtils.updateEditIconUI(session.isLocked, editBtn)
                        editBtn.setOnClickListener { onEditSessionRequested(session) }

                        itemView.setOnClickListener { onSessionSelected(session) }
                        itemView.setOnLongClickListener { onDeleteSessionRequested(session); true }
                        sessionContainer.addView(itemView)
                    }
                }
            }

            addSessions(
                activity.getString(R.string.current_week_session_head_text),
                thisWeekSessions
            )
            addSessions(activity.getString(R.string.upcoming_sessions_head_text), upcomingSessions)
            addSessions(activity.getString(R.string.previous_sessions_head_text), pastSessions)
        }
    }

    override fun updateCourseHeader(
        course: Course,
        sessionIds: Set<Long>,
        studentEmails: Set<String>,
        attendance: List<AttendanceRecord>
    ) {
        activity.runOnUiThread {
            val layoutCourseView =
                activity.findViewById<View>(R.id.layoutCourseView) ?: return@runOnUiThread
            UiUtils.fillCourseDetailedCardStatistics(
                activity,
                layoutCourseView,
                course,
                sessionIds,
                studentEmails,
                attendance
            )
        }
    }

    override fun setupQuickActions(actions: List<ActionItem>, titles: List<String>) {
        activity.runOnUiThread {
            val utilsViewPager =
                activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.utilsViewPager)
                    ?: return@runOnUiThread
            val utilsPageIndicator =
                activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.utilsPageIndicator)

            utilsViewPager.adapter = ActionsPageAdapter(
                actionItems = actions,
                pageTitles = titles,
                itemsPerPage = 2,
                layoutResId = R.layout.item_course_utils_page,
                buttonIds = listOf(R.id.btnRow1, R.id.btnRow2)
            )
            com.google.android.material.tabs.TabLayoutMediator(
                utilsPageIndicator,
                utilsViewPager
            ) { _, _ -> }.attach()
        }
    }

    override fun openOutputStream(uri: android.net.Uri): java.io.OutputStream? =
        activity.contentResolver.openOutputStream(uri)

    override fun showEditCourseDialog(course: Course, onCourseEdited: () -> Unit) {
        activity.runOnUiThread {
            activeAlertDialog =
                courseDialogFactory.showEditCourseDialog(course, { _ -> }, onCourseEdited)
        }
    }

    override fun showCreateCourseDialog(onCourseCreated: () -> Unit) {
        activity.runOnUiThread {
            activeAlertDialog = courseDialogFactory.showCreateCourseDialog(onCourseCreated)
        }
    }

    override fun showMassDateChangeDialog(courseId: Long) {
        activity.runOnUiThread {
            activeAlertDialog = sessionDialogFactory.showMassDateChangeDialog(courseId)
        }
    }

    override fun showDeleteSessionDialog(session: Session) {
        activity.runOnUiThread {
            activeAlertDialog = sessionDialogFactory.showDeleteSessionDialog(session)
        }
    }

    override fun openDetailedCourseView(
        onEditCourseRequested: () -> Unit,
        onSearchQueryChanged: (String) -> Unit
    ) {
        activity.runOnUiThread {
            val container = activity.findViewById<LinearLayout>(R.id.layoutCourseStatisticsView) ?: return@runOnUiThread
            container.removeAllViews()
            
            val statsView = activity.layoutInflater.inflate(R.layout.layout_course_statistics, container, false)
            container.addView(statsView)

            statsView.findViewById<View>(R.id.btnEditCourse)?.setOnClickListener { onEditCourseRequested() }

            val searchView = statsView.findViewById<androidx.appcompat.widget.SearchView>(R.id.searchStudentsAttendance)
            searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    onSearchQueryChanged(newText ?: "")
                    return true
                }
            })
            
            studentStatsAdapter = null // Reset for new view
        }
    }

    override fun updateDetailedCourseHeader(
        course: Course,
        sessionIds: Set<Long>,
        studentEmails: Set<String>,
        attendance: List<AttendanceRecord>
    ) {
        activity.runOnUiThread {
            val statsView = activity.findViewById<View>(R.id.layoutCourseStatisticsView) ?: return@runOnUiThread
            UiUtils.fillCourseDetailedCardStatistics(
                activity,
                statsView,
                course,
                sessionIds,
                studentEmails,
                attendance
            )
        }
    }

    override fun updateStudentStatsList(
        students: List<Student>,
        allSessions: List<Session>,
        allAttendance: List<AttendanceRecord>,
        getColorFromAttr: (Int) -> Int
    ) {
        activity.runOnUiThread {
            val statsView = activity.findViewById<View>(R.id.layoutCourseStatisticsView) ?: return@runOnUiThread
            val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats) ?: return@runOnUiThread

            if (studentStatsAdapter == null) {
                studentStatsAdapter = StudentStatsAdapter(
                    students,
                    allSessions,
                    allAttendance,
                    allSessions.map { it.id }.toSet(),
                    getColorFromAttr = getColorFromAttr,
                    makeSessionTimeFormatter = { TimeUtils.makeSessionTimeFormatter(activity) },
                    fromMillisToLocalDate = { ms -> TimeUtils.fromMillisToLocalDate(ms) }
                )
                rv.layoutManager = LinearLayoutManager(activity)
                rv.adapter = studentStatsAdapter
            } else {
                studentStatsAdapter?.updateData(students)
            }
        }
    }
}
