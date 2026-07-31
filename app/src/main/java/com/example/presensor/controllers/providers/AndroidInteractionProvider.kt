package com.example.presensor.controllers.providers

import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    ReaderInteractionProvider, CloudInteractionProvider, CourseInteractionProvider {

    private var activeBottomSheet: BottomSheetDialog? = null
    private var backlogAdapter: ImportBacklogAdapter? = null
    private var backlogCountText: TextView? = null

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
            DialogFactory.showMappingDialog(
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
        }
    }

    override fun isAnyDialogOpen(): Boolean = DialogFactory.isAnyDialogOpen()

    override fun setLoadingJob(job: kotlinx.coroutines.Job?) {
        activity.setCurrentOverlayJob(job)
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
            tagDialogFactory.showBindingDialog(
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

            val dialog = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.dialog_unlock_title, sessionName))
                .setMessage(activity.getString(R.string.dialog_unlock_message))
                .setView(input)
                .setPositiveButton(activity.getString(R.string.action_unlock), null)
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString() == sessionName) {
                    onUnlocked()
                    dialog.dismiss()
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

    override fun submitAttendanceList(records: List<AttendanceRecord>) {
        activity.runOnUiThread {
            val rv = activity.findViewById<RecyclerView>(R.id.rvAttendance)
            (rv?.adapter as? AttendanceAdapter)?.submitList(records)
        }
    }

    override fun showLayoutRefreshSpinner(show: Boolean) {
        activity.runOnUiThread {
            activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefreshLayout)?.isRefreshing =
                show
        }
    }

    override fun setOnRefreshListener(listener: () -> Unit) {
        activity.runOnUiThread {
            activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefreshLayout)
                ?.setOnRefreshListener {
                    listener()
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

    override fun showDestructiveDeleteDialog(
        title: String,
        message: String,
        onConfirmed: () -> Unit
    ) {
        activity.runOnUiThread {
            DialogFactory.showDestructiveDeleteDialog(activity, title, message, onConfirmed)
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

    override fun setupCourseUtilsAccordion(onHeaderClicked: (isExpanded: Boolean) -> Unit) {
        activity.runOnUiThread {
            val headerClickArea =
                activity.findViewById<View>(R.id.layoutUtilsHeader) ?: return@runOnUiThread
            val expandableLayout = activity.findViewById<View>(R.id.layoutUtilsContent)
            headerClickArea.setOnClickListener {
                onHeaderClicked(expandableLayout.visibility == View.VISIBLE)
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
        onDeleteSessionRequested: (Session) -> Unit,
        getColorForAccent: (String) -> Int
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
                            .setBackgroundColor(getColorForAccent(session.name))
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

    override fun launchExportPicker(fileName: String) {
    }

    override fun launchImportPicker() {
    }

    override fun openOutputStream(uri: android.net.Uri): java.io.OutputStream? =
        activity.contentResolver.openOutputStream(uri)

    override fun showEditCourseDialog(course: Course, onCourseEdited: () -> Unit) {
        activity.runOnUiThread {
            courseDialogFactory.showEditCourseDialog(course, { _ -> }, onCourseEdited)
        }
    }

    override fun showCreateCourseDialog(onCourseCreated: () -> Unit) {
        activity.runOnUiThread {
            courseDialogFactory.showCreateCourseDialog(onCourseCreated)
        }
    }

    override fun showMassDateChangeDialog(courseId: Long) {
        activity.runOnUiThread {
            sessionDialogFactory.showMassDateChangeDialog(courseId)
        }
    }

    override fun showDeleteSessionDialog(session: Session) {
        activity.runOnUiThread {
            sessionDialogFactory.showDeleteSessionDialog(session)
        }
    }
}
