package com.example.presensor.controllers.providers

import android.nfc.NfcAdapter
import android.os.Bundle
import com.example.presensor.MainActivity
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.TagControllerDialogFactory
import com.example.presensor.data.entities.Student

class AndroidTagInteractionProvider(
    activity: MainActivity,
    private val tagDialogFactory: TagControllerDialogFactory,
    private val sessionDialogFactory: SessionControllerDialogFactory
) : BaseAndroidInteractionProvider(activity), TagInteractionProvider {

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
}
