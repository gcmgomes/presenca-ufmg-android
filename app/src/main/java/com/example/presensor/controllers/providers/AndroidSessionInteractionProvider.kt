package com.example.presensor.controllers.providers

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.adapters.AttendanceAdapter
import com.example.presensor.controllers.adapters.ImportPreviewAdapter
import com.example.presensor.controllers.adapters.StudentSearchAdapter
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory.showWithSmartNfcReading
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.TimeUtils
import com.example.presensor.tools.UiUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

class AndroidSessionInteractionProvider(
    activity: MainActivity,
    private val sessionDialogFactory: SessionControllerDialogFactory
) : BaseAndroidInteractionProvider(activity), SessionInteractionProvider {

    private val attendanceAdapter = AttendanceAdapter()

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
        onSessionUpdated: (newName: String, newDateMillis: Long, start: Long?, end: Long?) -> Unit
    ) {
        activity.runOnUiThread {
            activeAlertDialog =
                sessionDialogFactory.showEditSessionDialog(session, onSessionUpdated)
        }
    }

    override fun showCreateSessionDialog(
        courseId: Long,
        onSessionCreated: (Long, String, Long, Long?, Long?) -> Unit
    ) {
        activity.runOnUiThread {
            sessionDialogFactory.showCreateSessionDialog(courseId, onSessionCreated)
        }
    }

    override fun showDeleteSessionDialog(session: Session) {
        activity.runOnUiThread {
            activeAlertDialog = sessionDialogFactory.showDeleteSessionDialog(session)
        }
    }

    override fun showMassDateChangeDialog(courseId: Long) {
        activity.runOnUiThread {
            activeAlertDialog = sessionDialogFactory.showMassDateChangeDialog(courseId)
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
}
