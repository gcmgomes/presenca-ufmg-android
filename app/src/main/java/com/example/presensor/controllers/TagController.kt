package com.example.presensor.controllers

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import com.example.presensor.DialogFactory
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TagController(
    private val context: Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val layoutInflater: LayoutInflater,
    private val sessionController: SessionController,
    private val isDialogShowingCheck: () -> Boolean,
    private val onDialogStateChanged: (isOpen: Boolean) -> Unit
) {

    /**
     * Entry-point for processing an incoming hardware tag discovery background pulse.
     */
    fun handleTagDiscovered(rfid: String, time: Long) {
        // Prevent background polling from overlapping ongoing alert workflow sessions
        if (isDialogShowingCheck() || DialogFactory.isAnyDialogOpen()) return

        scope.launch {
            val student = db.dao().getStudentByRfid(rfid)

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
        AlertDialog.Builder(context)
            .setTitle("Tag Already Registered")
            .setMessage("This tag is currently assigned to ${existingStudent.name} (${existingStudent.email}).\n\nDo you want to unbind this tag so it can be reassigned?")
            .setPositiveButton("Yes") { _, _ ->
                scope.launch {
                    db.dao().bindTagToStudent(null, existingStudent.email)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Tag unbound", Toast.LENGTH_SHORT).show()
                        showBindingDialog(newRfid)
                    }
                }
            }
            .setNegativeButton("No", null)
            .create()
            .apply {
                setOnShowListener { onDialogStateChanged(true) }
                setOnDismissListener { onDialogStateChanged(false) }
            }
            .show()
    }

    private fun showBindingDialog(newRfid: String) {
        scope.launch {
            val allStudents = db.dao().getAllStudents().sortedBy { it.name }

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
                        val row = TextView(context).apply {
                            text =
                                if (hasTag) "${student.name} - ${student.email} - [${student.rfid}]"
                                else "${student.name} - ${student.email} - [No tag]"
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

                        val line = View(context).apply {
                            layoutParams =
                                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                            setBackgroundColor(Color.LTGRAY)
                        }
                        container.addView(line)
                    }
                }

                refreshList("")

                bindingDialog = AlertDialog.Builder(context)
                    .setTitle("Assign Tag: $newRfid")
                    .setView(dialogView)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Manual Entry") { _, _ ->
                        showRegistrationDialog(newRfid)
                    }
                    .create()

                bindingDialog.setOnShowListener { onDialogStateChanged(true) }
                bindingDialog.setOnDismissListener { onDialogStateChanged(false) }
                bindingDialog.show()

                edtSearch.addTextChangedListener { refreshList(it.toString()) }
            }
        }
    }

    private fun showReassignConfirmation(student: Student, rfid: String, onComplete: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Overwrite Tag?")
            .setMessage("${student.name} is already linked to tag ${student.rfid}.\n\nDo you want to replace it with the new tag $rfid?")
            .setPositiveButton("Replace") { _, _ ->
                bindTag(rfid, student.email)
                onComplete()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener { onDialogStateChanged(true) }
                setOnDismissListener { onDialogStateChanged(false) }
            }
            .show()
    }

    private fun bindTag(rfid: String, email: String) {
        scope.launch {
            withContext(Dispatchers.IO) { db.dao().clearAndBind(rfid, email) }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Tag successfully assigned!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRegistrationDialog(rfid: String) {
        DialogFactory.showManualRegistrationDialog(
            context = context,
            rfid = rfid,
            onStudentSaved = { name, email, dialog ->
                scope.launch {
                    db.dao()
                        .insertStudents(listOf(Student(email = email, name = name, rfid = rfid)))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Registered $name", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
        )
    }
}