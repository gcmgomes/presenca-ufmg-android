package com.example.presensor.controllers

import android.content.Context
import android.graphics.Color
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
import com.example.presensor.tools.TimeUtils
import com.example.presensor.tools.UiUtils
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.data.entities.Course
import java.time.LocalDate

class SessionController(
    private val activity: AppCompatActivity,
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase,
    private val layoutInflater: LayoutInflater,
    private val attendanceContainer: LinearLayout,
    private val txtSessionTitle: TextView,
    private val txtSessionSubtitle: TextView,
    private val viewSessionDetailAccent: View,
    private val imgMasterLock: ImageView,
    private val btnEditSession: ImageView,
    private val getColorForAccent: (String) -> Int,
    private val onSessionStateMutated: () -> Unit,
    private val dialogFactory: CourseControllerDialogFactory
) {
    var activeSession: Session? = null
        private set

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

    fun openSessionView(session: Session) {
        activeSession = session

        updateCardOnSessionView(session.name, session.date)

        UiUtils.updateLockIconUI(session.isLocked, imgMasterLock)
        UiUtils.updateEditIconUI(session.isLocked, btnEditSession)

        imgMasterLock.setOnClickListener {
            activeSession?.let { currentSession ->
                handleLockToggleSequence(currentSession)
            }
        }

        btnEditSession.setOnClickListener {
            activeSession?.let { currentSession ->
                showEditSessionDialog(currentSession)
            }
            updateCardOnSessionView(activeSession!!.name, activeSession!!.date)
        }

        loadAttendanceList()
    }

    fun loadAttendanceList() {
        val currentSessionId = activeSession?.id ?: return
        lifecycleOwner.lifecycleScope.launch {
            val records = db.getAttendanceRecordsForSession(currentSessionId)
            attendanceContainer.removeAllViews()
            val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

            records.forEach { record ->
                val rowView =
                    layoutInflater.inflate(R.layout.item_attendance_row, attendanceContainer, false)
                rowView.findViewById<TextView>(R.id.txtStudentInfo).text = record.studentName
                rowView.findViewById<TextView>(R.id.txtTimestamp).text =
                    TimeUtils.fromMillisToLocalDateTime(record.timestamp).format(timeFormat)
                attendanceContainer.addView(rowView)
            }
        }
    }

    fun handleLockToggleSequence(targetSession: Session) {
        if (targetSession.isLocked) {
            val input =
                EditText(context).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }

            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.dialog_unlock_title, targetSession.name))
                .setMessage(context.getString(R.string.dialog_unlock_message))
                .setView(input)
                .setPositiveButton(context.getString(R.string.action_unlock)) { _, _ ->
                    if (input.text.toString() == targetSession.name) {
                        executeLockStateUpdate(targetSession, false)
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.error_incorrect_password),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(context.getString(R.string.action_cancel), null)
                .show()
        } else {
            executeLockStateUpdate(targetSession, true)
        }
    }

    private fun executeLockStateUpdate(session: Session, shouldLock: Boolean) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            db.updateSessionLock(session.id, shouldLock)
            withContext(Dispatchers.Main) {
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
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun registerAttendance(student: Student?, time: Long) {
        val session = activeSession ?: return
        activity.lifecycleScope.launch {
            if (session.isLocked) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.msg_session_locked),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            if (student != null) {
                val identifier =
                    if (!student.rfid.isNullOrEmpty()) student.rfid else "MANUAL:${student.email}"

                db.recordAttendance(student, session, time)
                loadAttendanceList()
            } else {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_tag_not_registered),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun showEditSessionDialog(session: Session) {
        if (session.isLocked) {
            Toast.makeText(
                context,
                context.getString(R.string.msg_session_locked),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        dialogFactory.showEditSessionDialog(
            session,
            onSessionUpdated = { updatedName, updatedTimestamp ->
                lifecycleOwner.lifecycleScope.launch {
                    val modifiedSession = session.copy(
                        name = updatedName,
                        date = updatedTimestamp
                    )

                    db.updateSession(modifiedSession)

                    if (activeSession != null) {
                        activeSession = modifiedSession
                    }

                    txtSessionTitle.text = updatedName
                    txtSessionSubtitle.text =
                        TimeUtils.fromMillisToLocalDate(updatedTimestamp)
                            .format(TimeUtils.makeSessionTimeFormatter(context))
                    viewSessionDetailAccent.setBackgroundColor(getColorForAccent(updatedName))

                    onSessionStateMutated()

                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_session_properties_modified),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    fun showManualAttendanceDialog() {
        val currentSession = activeSession ?: return

        lifecycleOwner.lifecycleScope.launch {
            val allStudents = db.getAllStudents().sortedBy { it.name }
            val currentAttendance = db.getAttendanceRecordsForSession(currentSession.id)

            val presentEmails = currentAttendance.map { it.studentEmail }.toSet()
            val absentStudents = allStudents.filter { it.email !in presentEmails }

            withContext(Dispatchers.Main) {
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
                    .create()

                manualDialog.show()

                edtSearch.addTextChangedListener { refreshAbsenteeList(it.toString()) }
            }
        }
    }
}