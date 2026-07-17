package com.example.presensor.controllers.dialogs

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.example.presensor.R
import com.example.presensor.data.entities.Student

interface TagControllerDialogFactory {
    fun showOverwriteConfirmation(
        existingStudent: Student,
        newRfid: String,
        onConfirm: () -> Unit
    )

    fun showBindingDialog(
        newRfid: String,
        allStudents: List<Student>,
        onStudentSelected: (Student) -> Unit,
        onManualAttendance: () -> Unit,
        onReassignConfirmed: (Student) -> Unit
    )
}

class AndroidTagControllerDialogFactory(
    private val activity: AppCompatActivity,
    private val layoutInflater: LayoutInflater
) : TagControllerDialogFactory {

    override fun showOverwriteConfirmation(
        existingStudent: Student,
        newRfid: String,
        onConfirm: () -> Unit
    ) {
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
                    onConfirm()
                }
                .setNegativeButton(activity.getString(R.string.action_no), null)
                .showWithSmartNfcReading()
        }
    }

    override fun showBindingDialog(
        newRfid: String,
        allStudents: List<Student>,
        onStudentSelected: (Student) -> Unit,
        onManualAttendance: () -> Unit,
        onReassignConfirmed: (Student) -> Unit
    ) {
        if(DialogFactory.isAnyDialogOpen()) return
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
                    text = if (hasTag) activity.getString(
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
                            showReassignConfirmation(student, newRfid) {
                                onReassignConfirmed(student)
                                bindingDialog?.dismiss()
                            }
                        } else {
                            onStudentSelected(student)
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
                    onManualAttendance()
                }
                .showWithSmartNfcReading()
        }

        edtSearch.addTextChangedListener { refreshList(it.toString()) }
    }

    private fun showReassignConfirmation(student: Student, rfid: String, onConfirm: () -> Unit) {
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
                    onConfirm()
                }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()
        }
    }
}
