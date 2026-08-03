package com.example.presensor.controllers

import android.util.Log
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.controllers.providers.SessionInteractionProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionController(
    private val interactionProvider: SessionInteractionProvider,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val getColorForAccent: (String) -> Int,
    private val onSessionStateMutated: () -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onPulldown: (Session?) -> Unit,
    private val onSyncTimeout: (() -> Unit)? = null
) {
    var activeSession: Session? = null
        private set

    private var syncTimeoutJob: Job? = null

    fun clearActiveSession() {
        activeSession = null
    }

    private fun updateCardOnSessionView(name: String, date: Long) {
        interactionProvider.updateSessionCard(name, date, getColorForAccent(name))
    }

    fun resetSyncTimeout() {
        // 1. Cancel the current active timer
        syncTimeoutJob?.cancel()

        // 2. Start a fresh 5-second timer
        syncTimeoutJob = scope.launch {
            delay(5000) // 5 seconds of silence allowed

            withContext(mainDispatcher) {
                interactionProvider.showLayoutRefreshSpinner(false)
                onSyncTimeout?.invoke()
                interactionProvider.showToast("Sync timed out. Connection lost.")
                Log.w("SyncWatchdog", "Inactivity timeout: No tags received for 5 seconds.")
            }
        }
    }

    fun cancelSyncTimeout() {
        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
    }

    fun showLayoutRefreshSpinner(state: Boolean) {
        interactionProvider.showLayoutRefreshSpinner(state)
    }

    fun openSessionView(session: Session) {
        activeSession = session

        updateCardOnSessionView(session.name, session.date)
        interactionProvider.updateLockState(session.isLocked)

        interactionProvider.setOnRefreshListener {
            interactionProvider.showLayoutRefreshSpinner(true)
            onPulldown(activeSession)
            resetSyncTimeout()
        }

        interactionProvider.setupSessionListeners(
            onLockClicked = {
                activeSession?.let { handleLockToggleSequence(it) }
            },
            onEditClicked = {
                activeSession?.let {
                    showEditSessionDialog(it)
                    updateCardOnSessionView(it.name, it.date)
                }
            }
        )

        loadAttendanceList()
    }

    fun loadAttendanceList(shouldScrollToBottom: Boolean = false) {
        val currentSessionId = activeSession?.id ?: return
        scope.launch {
            val records = db.getAttendanceRecordsForSession(currentSessionId)
            val sortedRecords = records.sortedBy { it.timestamp } // Oldest to newest
            withContext(mainDispatcher) {
                interactionProvider.submitAttendanceList(
                    sortedRecords,
                    scrollToPosition = if (shouldScrollToBottom && sortedRecords.isNotEmpty()) sortedRecords.size - 1 else null
                )
            }
        }
    }

    fun handleLockToggleSequence(targetSession: Session) {
        if (targetSession.isLocked) {
            interactionProvider.showUnlockDialog(targetSession.name) {
                executeLockStateUpdate(targetSession, false)
            }
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
                        interactionProvider.updateLockState(shouldLock)
                    }
                }
                onSessionStateMutated()

                val msgId = if (shouldLock) {
                    R.string.msg_session_locked
                } else {
                    R.string.msg_session_unlocked
                }
                interactionProvider.showToast(interactionProvider.getString(msgId))
            }
        }
    }

    fun registerAttendance(student: Student?, time: Long, skipImmediateRefresh: Boolean = false) {
        val session = activeSession ?: return
        scope.launch {
            if (session.isLocked) {
                withContext(mainDispatcher) {
                    interactionProvider.showToast(R.string.msg_session_locked)
                }
                return@launch
            }

            if (student != null) {
                db.recordAttendance(student, session, time)
                if (!skipImmediateRefresh) {
                    loadAttendanceList(shouldScrollToBottom = true)
                }
            } else {
                withContext(mainDispatcher) {
                    interactionProvider.showToast(R.string.toast_tag_not_registered)
                }
            }
        }
    }

    fun showEditSessionDialog(session: Session) {
        if (session.isLocked) {
            interactionProvider.showToast(R.string.msg_session_locked)
            return
        }
        interactionProvider.showEditSessionDialog(
            session,
            onSessionUpdated = { updatedName, updatedTimestamp, start, end ->
                scope.launch {
                    val modifiedSession = session.copy(
                        name = updatedName,
                        date = updatedTimestamp,
                        startTime = start,
                        endTime = end
                    )

                    db.updateSession(modifiedSession)

                    withContext(mainDispatcher) {
                        if (activeSession != null) {
                            activeSession = modifiedSession
                        }

                        updateCardOnSessionView(updatedName, updatedTimestamp)
                        onSessionStateMutated()
                        interactionProvider.showToast(R.string.toast_session_properties_modified)
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
                interactionProvider.showStudentSearchDialog(
                    allStudents = absentStudents,
                    onStudentSelected = { student ->
                        registerAttendance(student, System.currentTimeMillis())
                        interactionProvider.dismissActiveDialog()
                    },
                    onManualRegistrationRequested = {
                        interactionProvider.dismissActiveDialog()
                        interactionProvider.showManualRegistrationDialog(rfid = "") { name, email, _ ->
                            scope.launch {
                                val newStudent = Student(email = email, name = name, rfid = null)
                                db.insertStudents(listOf(newStudent))
                                withContext(mainDispatcher) {
                                    registerAttendance(newStudent, System.currentTimeMillis())
                                    interactionProvider.dismissActiveDialog()
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
