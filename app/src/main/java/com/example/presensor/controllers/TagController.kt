package com.example.presensor.controllers

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.presensor.R
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.TagControllerDialogFactory
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.providers.ToastProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TagController(
    private val activity: AppCompatActivity,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val sessionController: SessionController,
    private val sessionDialogFactory: SessionControllerDialogFactory,
    private val tagControllerDialogFactory: TagControllerDialogFactory,
    private val toastProvider: ToastProvider,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity),
    private val isDialogShowingCheck: () -> Boolean,
) : NfcAdapter.ReaderCallback {

    fun getNfcAdapter(): NfcAdapter? = nfcAdapter

    fun pauseNfcScanning() {
        nfcAdapter?.disableReaderMode(activity)
    }

    fun resumeNfcScanning() {
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
        }

        var readerFlags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

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
        if (isDialogShowingCheck() || DialogFactory.isAnyDialogOpen()) return

        scope.launch {
            val student = db.getStudentByRfid(rfid)

            withContext(mainDispatcher) {
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
        tagControllerDialogFactory.showOverwriteConfirmation(
            existingStudent = existingStudent,
            newRfid = newRfid
        ) {
            scope.launch {
                db.bindTagToStudent(null, existingStudent.email)
                withContext(mainDispatcher) {
                    toastProvider.showToast(activity.getString(R.string.toast_tag_unbound))
                    showBindingDialog(newRfid)
                }
            }
        }
    }

    private fun showBindingDialog(newRfid: String) {
        scope.launch {
            val allStudents = db.getAllStudents().sortedBy { it.name }

            withContext(mainDispatcher) {
                tagControllerDialogFactory.showBindingDialog(
                    newRfid = newRfid,
                    allStudents = allStudents,
                    onStudentSelected = { student ->
                        bindTag(newRfid, student.email)
                    },
                    onManualAttendance = {
                        showRegistrationDialog(newRfid)
                    },
                    onReassignConfirmed = { student ->
                        bindTag(newRfid, student.email)
                    }
                )
            }
        }
    }

    private fun bindTag(rfid: String, email: String) {
        scope.launch {
            db.clearAndBind(rfid, email)
            withContext(mainDispatcher) {
                toastProvider.showToast(activity.getString(R.string.toast_tag_assigned_success))
            }
        }
    }

    private fun showRegistrationDialog(rfid: String) {
        sessionDialogFactory.showManualRegistrationDialog(
            rfid = rfid,
            onStudentSaved = { name, email, dialog ->
                scope.launch {
                    db.insertStudents(listOf(Student(email = email, name = name, rfid = rfid)))
                    withContext(mainDispatcher) {
                        toastProvider.showToast(activity.getString(R.string.toast_student_registered_success, name))
                        dialog.dismiss()
                    }
                }
            }
        )
    }
}
