package com.example.presensor.controllers.dialogs

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.controllers.adapters.StudentSearchAdapter
import com.example.presensor.data.entities.Student
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

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
        if (DialogFactory.isAnyDialogOpen()) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_search_student, null)
        val edtSearch = dialogView.findViewById<EditText>(R.id.edtStudentSearch)
        val rvSearch = dialogView.findViewById<RecyclerView>(R.id.rvStudentSearch)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtSearchStudentTitle)
        val txtHint = dialogView.findViewById<TextView>(R.id.txtSearchStudentHint)
        val btnManual = dialogView.findViewById<MaterialButton>(R.id.btnSecondaryAction)

        txtTitle.text = activity.getString(R.string.title_assign_tag, newRfid)
        txtHint.text = activity.getString(R.string.label_tag_id, newRfid)
        btnManual.visibility = View.VISIBLE

        var bindingDialog: BottomSheetDialog? = null

        val adapter = StudentSearchAdapter { student ->
            if (!student.rfid.isNullOrEmpty()) {
                showReassignConfirmation(student, newRfid) {
                    onReassignConfirmed(student)
                    bindingDialog?.dismiss()
                }
            } else {
                onStudentSelected(student)
                bindingDialog?.dismiss()
            }
        }
        rvSearch.adapter = adapter
        rvSearch.layoutManager = LinearLayoutManager(activity)

        fun refreshList(query: String) {
            val filtered = allStudents.filter {
                it.name.contains(query, true) || it.email.contains(query, true)
            }
            adapter.submitList(filtered)

            if (filtered.isEmpty()) {
                txtHint.text = activity.getString(R.string.msg_no_students_found)
            } else {
                txtHint.text = activity.getString(R.string.label_tag_id, newRfid)
            }
        }

        refreshList("")

        bindingDialog = BottomSheetDialog(activity)
        bindingDialog?.setContentView(dialogView)

        btnManual.setOnClickListener {
            bindingDialog?.dismiss()
            onManualAttendance()
        }

        with(DialogFactory) {
            bindingDialog?.showWithSmartNfcReading()
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
