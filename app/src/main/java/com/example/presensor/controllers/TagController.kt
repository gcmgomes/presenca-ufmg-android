package com.example.presensor.controllers

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Log
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.communication.core.AppMode
import com.example.presensor.controllers.providers.TagInteractionProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TagController(
    private val interactionProvider: TagInteractionProvider,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val readerOrchestrator: ReaderOrchestrator?,
    private val sessionController: SessionController,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val isDialogShowingCheck: () -> Boolean,
    private val disableRefreshSpinner: () -> Unit,
    private val resetSyncTimeout: () -> Unit,
    private val getCurrentState: () -> MainActivity.Companion.AppState
) : NfcAdapter.ReaderCallback {

    internal var readerCollectionJob: Job? = null

    fun pauseNfcScanning() {
        interactionProvider.toggleNfcScanning(false)
    }

    fun resumeNfcScanning() {
        interactionProvider.toggleNfcScanning(true, this)
    }

    fun resumeReader() {
        readerOrchestrator?.setAppMode(AppMode.ACTIVE, "TagController Resume")
    }

    override fun onTagDiscovered(tag: Tag) {
        val rfid = tag.id.joinToString(":") { "%02X".format(it) }
        val time = System.currentTimeMillis()

        handleTagDiscovered(rfid, time)
    }

    fun startReaderCollection() {
        // Get the identity hash code of the OLD job before cancelling it
        val oldJobId =
            readerCollectionJob?.let { System.identityHashCode(it).toString(16).uppercase() }
                ?: "NONE"

        Log.d("TagController", "[Lifecycle] ---> startReaderCollection called.")
        Log.d("TagController", "[Lifecycle] Cancelling previous Job: #$oldJobId")

        // Cancel the previous collection job
        readerCollectionJob?.cancel()

        // Start the new collection
        val newJob = scope.launch {
            val thisJobId = System.identityHashCode(coroutineContext[Job]).toString(16).uppercase()
            Log.d(
                "TagController",
                "[Collector #$thisJobId] STARTED. Now actively listening to flow."
            )

            try {
                readerOrchestrator?.rfidSwipeFlow?.collect { (rawRfid, espTime) ->
                    if (rawRfid == "SYNC_DONE") {
                        withContext(mainDispatcher) {
                            // Turn off the spinner on your layout!
                            disableRefreshSpinner()
                        }
                        return@collect
                    }

                    withContext(mainDispatcher) {
                        resetSyncTimeout()
                    }

                    val rfid = rawRfid.chunked(2).joinToString(":")
                    val time = espTime * 1000 // moving from unix epoch time to milliseconds.
                    val curTime = System.currentTimeMillis()
                    Log.d(
                        "TagController",
                        "[Collector #$thisJobId] Captured $rfid at $time. Current time is $curTime."
                    )
                    handleTagDiscovered(rfid, time)
                }
            } catch (e: Exception) {
                Log.d(
                    "TagController",
                    "[Collector #$thisJobId] Interrupted/Cancelled: ${e.message}"
                )
            } finally {
                Log.d("TagController", "[Collector #$thisJobId] CLOSED.")
            }
        }

        // Save reference to the new job
        readerCollectionJob = newJob

        val newJobId = System.identityHashCode(newJob).toString(16).uppercase()
        Log.d("TagController", "[Lifecycle] Registered new Job: #$newJobId")
    }

    /**
     * Entry-point for processing an incoming hardware tag discovery background pulse.
     */
    fun handleTagDiscovered(rfid: String, time: Long) {
        if (isDialogShowingCheck()) return
        
        val state = getCurrentState()
        if (state != MainActivity.Companion.AppState.COURSE && state != MainActivity.Companion.AppState.SESSION) {
            Log.d("TagController", "Ignoring tag discovery while in state $state")
            return
        }

        Log.d("TagController", "Processing $rfid for timestamp $time.")

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
        interactionProvider.showOverwriteConfirmation(
            existingStudent = existingStudent,
            newRfid = newRfid
        ) {
            scope.launch {
                db.bindTagToStudent(null, existingStudent.email)
                withContext(mainDispatcher) {
                    interactionProvider.showToast(R.string.toast_tag_unbound)
                    showBindingDialog(newRfid)
                }
            }
        }
    }

    private fun showBindingDialog(newRfid: String) {
        scope.launch {
            val allStudents = db.getAllStudents().sortedBy { it.name }

            withContext(mainDispatcher) {
                interactionProvider.showBindingDialog(
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
                interactionProvider.showToast(R.string.toast_tag_assigned_success)
            }
        }
    }

    private fun showRegistrationDialog(rfid: String) {
        interactionProvider.showManualRegistrationDialog(
            rfid = rfid,
            onStudentSaved = { name, email, _ ->
                scope.launch {
                    db.insertStudents(listOf(Student(email = email, name = name, rfid = rfid)))
                    withContext(mainDispatcher) {
                        interactionProvider.showToast(
                            interactionProvider.getString(
                                R.string.toast_student_registered_success,
                                name
                            )
                        )
                        interactionProvider.dismissActiveDialog()
                    }
                }
            }
        )
    }
}
