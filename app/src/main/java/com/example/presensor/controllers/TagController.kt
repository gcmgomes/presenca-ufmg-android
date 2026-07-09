package com.example.presensor.controllers

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.R
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TagController(
    private val activity: AppCompatActivity,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val layoutInflater: LayoutInflater,
    private val sessionController: SessionController,
    private val sessionDialogFactory: SessionControllerDialogFactory,
    private val isDialogShowingCheck: () -> Boolean,
) : NfcAdapter.ReaderCallback{

    private var nfcAdapter = NfcAdapter.getDefaultAdapter(activity)

    fun getNfcAdapter(): NfcAdapter = nfcAdapter

    fun pauseNfcScanning() {
        nfcAdapter?.disableReaderMode(activity)
    }

    fun resumeNfcScanning() {
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
        }

        var readerFlags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        // Check your DialogFactory value here
        if (DialogFactory.isAnyDialogOpen()) {
            readerFlags = readerFlags or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        }

        nfcAdapter?.enableReaderMode(activity, this, readerFlags, options)
    }


    override fun onTagDiscovered(tag: Tag) {

        Log.d("onTagDiscovered", "Is a dialog open ${DialogFactory.isAnyDialogOpen()}")
        val rfid = tag.id.joinToString(":") { "%02X".format(it) }
        val time = System.currentTimeMillis()

        handleTagDiscovered(rfid, time)
    }

    /**
     * Entry-point for processing an incoming hardware tag discovery background pulse.
     */
    fun handleTagDiscovered(rfid: String, time: Long) {
        // Prevent background polling from overlapping ongoing alert workflow sessions
        if (isDialogShowingCheck() || DialogFactory.isAnyDialogOpen()) return

        scope.launch {
            val student = db.getStudentByRfid(rfid)

            // Context switch to the foreground thread to safely manifest UI workflows
            withContext(Dispatchers.Main) {
                val currentActiveSession = sessionController.activeSession
                if (currentActiveSession != null) {
                    sessionController.registerAttendance(student, time)
                } else {
                    if (student != null) {
                        showOverwriteConfirmation(student, rfid)
                    } else {
                        showBindingDialog(rfid)
                    }
                }
            }
        }
    }

    private fun showOverwriteConfirmation(existingStudent: Student, newRfid: String) {
        with(DialogFactory) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.dialog_tag_registered_title))
                .setMessage(
                    activity.getString(
                        R.string.dialog_tag_registered_message,
                        existingStudent.name,
                        existingStudent.email
                    )
                )
                .setPositiveButton(activity.getString(R.string.action_yes)) { _, _ ->
                    scope.launch {
                        db.bindTagToStudent(null, existingStudent.email)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.toast_tag_unbound),
                                Toast.LENGTH_SHORT
                            ).show()
                            showBindingDialog(newRfid)
                        }
                    }
                }
                .setNegativeButton(activity.getString(R.string.action_no), null)
                .showWithSmartNfcReading()
                .create()
        }
    }

    private fun showBindingDialog(newRfid: String) {
        scope.launch {
            val allStudents = db.getAllStudents().sortedBy { it.name }

            withContext(Dispatchers.Main) {
                val dialogView = layoutInflater.inflate(R.layout.dialog_search_student, null)
                val edtSearch = dialogView.findViewById<EditText>(R.id.edtStudentSearch)
                val container = dialogView.findViewById<LinearLayout>(R.id.studentListContainer)
                var bindingDialog: AlertDialog? = null

                fun refreshList(query: String) {
                    container.removeAllViews()
                    val filtered = allStudents.filter {
                        it.name.contains(query, true) || it.email.contains(query, true)
                    }

                    filtered.forEach { student ->
                        val hasTag = !student.rfid.isNullOrEmpty()
                        val row = TextView(activity).apply {
                            text =
                                if (hasTag) activity.getString(
                                    R.string.label_student_row_with_tag,
                                    student.name,
                                    student.email,
                                    student.rfid
                                )
                                else activity.getString(
                                    R.string.label_student_row_no_tag,
                                    student.name,
                                    student.email
                                )
                            textSize = 16f
                            setPadding(30, 30, 30, 30)
                            alpha = if (hasTag) 0.6f else 1.0f

                            setOnClickListener {
                                if (hasTag) {
                                    showReassignConfirmation(
                                        student,
                                        newRfid
                                    ) { bindingDialog?.dismiss() }
                                } else {
                                    bindTag(newRfid, student.email)
                                    bindingDialog?.dismiss()
                                }
                            }
                        }
                        container.addView(row)

                        val line = View(activity).apply {
                            layoutParams =
                                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                            setBackgroundColor(Color.LTGRAY)
                        }
                        container.addView(line)
                    }
                }

                refreshList("")

                with(DialogFactory) {
                    bindingDialog = AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.title_assign_tag, newRfid))
                        .setView(dialogView)
                        .setNegativeButton(activity.getString(R.string.action_cancel), null)
                        .setNeutralButton(activity.getString(R.string.title_manual_attendance)) { _, _ ->
                            showRegistrationDialog(newRfid)
                        }
                        .showWithSmartNfcReading()

                }

                edtSearch.addTextChangedListener { refreshList(it.toString()) }
            }
        }
    }

    private fun showReassignConfirmation(student: Student, rfid: String, onComplete: () -> Unit) {
        with(DialogFactory) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.dialog_overwrite_tag_title))
                .setMessage(
                    activity.getString(
                        R.string.dialog_overwrite_tag_message,
                        student.name,
                        student.rfid,
                        rfid
                    )
                )
                .setPositiveButton(activity.getString(R.string.action_replace)) { _, _ ->
                    bindTag(rfid, student.email)
                    onComplete()
                }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()
        }
    }

    private fun bindTag(rfid: String, email: String) {
        scope.launch {
            withContext(Dispatchers.IO) { db.clearAndBind(rfid, email) }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_tag_assigned_success),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showRegistrationDialog(rfid: String) {
        sessionDialogFactory.showManualRegistrationDialog(
            rfid = rfid,
            onStudentSaved = { name, email, dialog ->
                scope.launch {
                    db.insertStudents(listOf(Student(email = email, name = name, rfid = rfid)))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.toast_student_registered_success, name),
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }
                }
            }
        )
    }
}