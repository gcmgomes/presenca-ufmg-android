package com.example.presensor.controllers

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.presensor.tools.TimeUtils
import com.example.presensor.tools.UiUtils
import com.example.presensor.R
import com.example.presensor.controllers.adapters.AttendanceAdapter
import com.example.presensor.controllers.adapters.StudentSearchAdapter
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory.showWithSmartNfcReading
import com.example.presensor.data.entities.Course
import com.example.presensor.tools.providers.ToastProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.LocalDate

class SessionController(
    private val activity: AppCompatActivity,
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val layoutInflater: LayoutInflater,
    private val rvAttendance: RecyclerView,
    private val swipeRefreshLayout: SwipeRefreshLayout,
    private val txtSessionTitle: TextView,
    private val txtSessionSubtitle: TextView,
    private val viewSessionDetailAccent: View,
    private val imgMasterLock: ImageView,
    private val btnEditSession: ImageView,
    private val getColorForAccent: (String) -> Int,
    private val onSessionStateMutated: () -> Unit,
    private val dialogFactory: SessionControllerDialogFactory,
    private val toastProvider: ToastProvider,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onPulldown: () -> Unit,
    private val onSyncTimeout: (() -> Unit)? = null
) {
    var activeSession: Session? = null
        private set

    private var syncTimeoutJob: Job? = null
    internal val attendanceAdapter = AttendanceAdapter()

    init {
        rvAttendance.layoutManager = LinearLayoutManager(context)
        rvAttendance.adapter = attendanceAdapter
    }

    fun clearActiveSession() {
        activeSession = null
    }

    private fun updateCardOnSessionView(name: String, date: Long) {
        txtSessionTitle.text = name
        val dateFormat = TimeUtils.makeSessionTimeFormatter(context)
        txtSessionSubtitle.text =
            TimeUtils.fromMillisToLocalDate(date).format(dateFormat)
        viewSessionDetailAccent.setBackgroundColor(getColorForAccent(name))
    }

    fun resetSyncTimeout() {
        // 1. Cancel the current active timer
        syncTimeoutJob?.cancel()

        // 2. Start a fresh 10-second timer
        syncTimeoutJob = scope.launch {
            delay(5000) // 5 seconds of silence allowed

            if (swipeRefreshLayout.isRefreshing) {
                swipeRefreshLayout.isRefreshing = false
                onSyncTimeout?.invoke()
                toastProvider.showToast("Sync timed out. Connection lost.")
                Log.w("SyncWatchdog", "Inactivity timeout: No tags received for 5 seconds.")
            }
        }
    }

    fun cancelSyncTimeout() {
        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
    }

    fun showLayoutRefreshSpinner(state: Boolean) {
        swipeRefreshLayout.isRefreshing = state
    }

    fun openSessionView(session: Session) {
        activeSession = session

        updateCardOnSessionView(session.name, session.date)

        UiUtils.updateLockIconUI(session.isLocked, imgMasterLock)
        UiUtils.updateEditIconUI(session.isLocked, btnEditSession)

        swipeRefreshLayout.setOnRefreshListener {
            showLayoutRefreshSpinner(true)

            onPulldown()

            resetSyncTimeout()
        }

        imgMasterLock.setOnClickListener {
            activeSession?.let { currentSession ->
                handleLockToggleSequence(currentSession)
            }
        }

        btnEditSession.setOnClickListener {
            activeSession?.let { currentSession ->
                showEditSessionDialog(currentSession)
                updateCardOnSessionView(currentSession.name, currentSession.date)
            }
        }

        loadAttendanceList()
    }

    fun loadAttendanceList() {
        val currentSessionId = activeSession?.id ?: return
        scope.launch {
            val records = db.getAttendanceRecordsForSession(currentSessionId)
            withContext(mainDispatcher) {
                attendanceAdapter.submitList(records)
            }
        }
    }

    fun handleLockToggleSequence(targetSession: Session) {
        if (targetSession.isLocked) {
            val input =
                EditText(context).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    tag = "unlock_input"
                }

            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.dialog_unlock_title, targetSession.name))
                .setMessage(context.getString(R.string.dialog_unlock_message))
                .setView(input)
                .setPositiveButton(context.getString(R.string.action_unlock)) { _, _ ->
                    if (input.text.toString() == targetSession.name) {
                        executeLockStateUpdate(targetSession, false)
                    } else {
                        toastProvider.showToast(context.getString(R.string.error_incorrect_password))
                    }
                }
                .setNegativeButton(context.getString(R.string.action_cancel), null)
                .show()
        } else {
            executeLockStateUpdate(targetSession, true)
        }
    }

    private fun executeLockStateUpdate(session: Session, shouldLock: Boolean) {
        scope.launch(ioDispatcher) {
            db.updateSessionLock(session.id, shouldLock)
            withContext(mainDispatcher) {
                activeSession?.let {
                    if (it.id == session.id) {
                        val updated = it.copy(isLocked = shouldLock)
                        activeSession = updated
                        UiUtils.updateLockIconUI(shouldLock, imgMasterLock)
                        UiUtils.updateEditIconUI(shouldLock, btnEditSession)
                    }
                }
                onSessionStateMutated()

                val msg = if (shouldLock) {
                    context.getString(R.string.msg_session_locked)
                } else {
                    context.getString(R.string.msg_session_unlocked)
                }
                toastProvider.showToast(msg)
            }
        }
    }

    fun registerAttendance(student: Student?, time: Long) {
        val session = activeSession ?: return
        scope.launch {
            if (session.isLocked) {
                withContext(mainDispatcher) {
                    toastProvider.showToast(activity.getString(R.string.msg_session_locked))
                }
                return@launch
            }

            if (student != null) {
                db.recordAttendance(student, session, time)
                loadAttendanceList()
            } else {
                withContext(mainDispatcher) {
                    toastProvider.showToast(activity.getString(R.string.toast_tag_not_registered))
                }
            }
        }
    }

    fun showEditSessionDialog(session: Session) {
        if (session.isLocked) {
            toastProvider.showToast(context.getString(R.string.msg_session_locked))
            return
        }
        dialogFactory.showEditSessionDialog(
            session,
            onSessionUpdated = { updatedName, updatedTimestamp ->
                scope.launch {
                    val modifiedSession = session.copy(
                        name = updatedName,
                        date = updatedTimestamp
                    )

                    db.updateSession(modifiedSession)

                    withContext(mainDispatcher) {
                        if (activeSession != null) {
                            activeSession = modifiedSession
                        }

                        txtSessionTitle.text = updatedName
                        txtSessionSubtitle.text =
                            TimeUtils.fromMillisToLocalDate(updatedTimestamp)
                                .format(TimeUtils.makeSessionTimeFormatter(context))
                        viewSessionDetailAccent.setBackgroundColor(getColorForAccent(updatedName))

                        onSessionStateMutated()

                        toastProvider.showToast(context.getString(R.string.toast_session_properties_modified))
                    }
                }
            })
    }

    fun showManualAttendanceDialog() {
        val currentSession = activeSession ?: return

        scope.launch {
            val allStudents = db.getAllStudents().sortedBy { it.name }
            val currentAttendance = db.getAttendanceRecordsForSession(currentSession.id)

            val presentEmails = currentAttendance.map { it.studentEmail }.toSet()
            val absentStudents = allStudents.filter { it.email !in presentEmails }

            withContext(mainDispatcher) {
                val dialogView = layoutInflater.inflate(R.layout.dialog_search_student, null)
                val edtSearch = dialogView.findViewById<EditText>(R.id.edtStudentSearch)
                val rvSearch = dialogView.findViewById<RecyclerView>(R.id.rvStudentSearch)
                val btnCreate = dialogView.findViewById<View>(R.id.btnCreateNewStudent)
                val txtHint = dialogView.findViewById<TextView>(R.id.txtSearchStudentHint)
                btnCreate.visibility = View.VISIBLE
                var manualDialog: BottomSheetDialog? = null

                val adapter = StudentSearchAdapter { student ->
                    registerAttendance(student, System.currentTimeMillis())
                    manualDialog?.dismiss()
                }
                rvSearch.adapter = adapter
                rvSearch.layoutManager = LinearLayoutManager(context)

                fun refreshAbsenteeList(query: String) {
                    val filtered = absentStudents.filter {
                        it.name.contains(query, true) || it.email.contains(query, true)
                    }
                    adapter.submitList(filtered)

                    if (filtered.isEmpty()) {
                        txtHint.text = context.getString(R.string.msg_no_students_found)
                        txtHint.setTextColor(Color.RED)
                    } else {
                        txtHint.text = context.getString(R.string.hint_search_student)
                        txtHint.setTextColor(context.getColor(R.color.text_secondary))
                    }
                }

                refreshAbsenteeList("")

                btnCreate.setOnClickListener {
                    manualDialog?.dismiss()
                    dialogFactory.showManualRegistrationDialog("") { name, email, regDialog ->
                        scope.launch {
                            val newStudent = Student(email = email, name = name, rfid = null)
                            db.insertStudents(listOf(newStudent))
                            withContext(mainDispatcher) {
                                registerAttendance(newStudent, System.currentTimeMillis())
                                regDialog.dismiss()
                            }
                        }
                    }
                }

                manualDialog = BottomSheetDialog(context)
                manualDialog?.setContentView(dialogView)

                with(DialogFactory) {
                    manualDialog?.showWithSmartNfcReading()
                }

                edtSearch.addTextChangedListener { refreshAbsenteeList(it.toString()) }
            }
        }
    }
}