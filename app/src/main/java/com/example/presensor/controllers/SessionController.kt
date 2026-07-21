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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.tools.TimeUtils
import com.example.presensor.tools.UiUtils
import com.example.presensor.R
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
    private val attendanceContainer: LinearLayout,
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
) {
    var activeSession: Session? = null
        private set

    private var syncTimeoutJob: Job? = null

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
            delay(10000) // 10 seconds of silence allowed

            if (swipeRefreshLayout.isRefreshing) {
                swipeRefreshLayout.isRefreshing = false
                toastProvider.showToast("Sync timed out. Connection lost.")
                Log.w("SyncWatchdog", "Inactivity timeout: No tags received for 10 seconds.")
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
                attendanceContainer.removeAllViews()
                val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

                records.forEach { record ->
                    val rowView =
                        layoutInflater.inflate(
                            R.layout.item_attendance_row,
                            attendanceContainer,
                            false
                        )
                    rowView.findViewById<TextView>(R.id.txtStudentInfo).text = record.studentName
                    rowView.findViewById<TextView>(R.id.txtTimestamp).text =
                        TimeUtils.fromMillisToLocalDateTime(record.timestamp).format(timeFormat)
                    attendanceContainer.addView(rowView)
                }
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
                val container = dialogView.findViewById<LinearLayout>(R.id.studentListContainer)
                var manualDialog: AlertDialog? = null

                fun refreshAbsenteeList(query: String) {
                    container.removeAllViews()
                    val filtered = absentStudents.filter {
                        it.name.contains(query, true) || it.email.contains(query, true)
                    }

                    if (filtered.isEmpty()) {
                        val emptyRow = TextView(context).apply {
                            text = context.getString(R.string.msg_no_students_found)
                            textSize = 14f
                            setPadding(30, 40, 30, 40)
                            gravity = android.view.Gravity.CENTER
                            setTextColor(Color.GRAY)
                        }
                        container.addView(emptyRow)
                        return
                    }

                    filtered.forEach { student ->
                        val row = TextView(context).apply {
                            text = "${student.name}\n${student.email}"
                            textSize = 16f
                            setPadding(30, 24, 30, 24)

                            setOnClickListener {
                                registerAttendance(student, System.currentTimeMillis())
                                manualDialog?.dismiss()
                            }
                        }
                        container.addView(row)

                        val divider = View(context).apply {
                            layoutParams =
                                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                            setBackgroundColor(Color.LTGRAY)
                        }
                        container.addView(divider)
                    }
                }

                refreshAbsenteeList("")

                manualDialog = AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.title_manual_attendance))
                    .setView(dialogView)
                    .setNegativeButton(context.getString(R.string.action_cancel), null)
                    .showWithSmartNfcReading()

                edtSearch.addTextChangedListener { refreshAbsenteeList(it.toString()) }
            }
        }
    }
}