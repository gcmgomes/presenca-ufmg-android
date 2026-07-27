package com.example.presensor.tools.providers

import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.adapters.ImportPreviewAdapter
import com.example.presensor.adapters.ImportStudentAdapter
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

interface PreviewProvider {
    fun showSessionImportPreview(
        activity: AppCompatActivity,
        sessions: List<Session>,
        onConfirm: (List<Session>) -> Unit,
        onDismiss: () -> Unit
    )

    fun showStudentImportPreview(
        activity: AppCompatActivity,
        students: List<Student>,
        onConfirm: (List<Student>) -> Unit,
        onDismiss: () -> Unit
    )
}

/**
 * Android implementation of PreviewProvider using BottomSheetDialog.
 */
class AndroidPreviewProvider : PreviewProvider {

    override fun showSessionImportPreview(
        activity: AppCompatActivity,
        sessions: List<Session>,
        onConfirm: (List<Session>) -> Unit,
        onDismiss: () -> Unit
    ) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_list_preview, null)
        bottomSheet.setContentView(view)

        var importConfirmed = false

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvPreviewList)
        val txtTitle = view.findViewById<TextView>(R.id.txtPreviewTitle)
        val txtHint = view.findViewById<TextView>(R.id.txtPreviewHint)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirmAction)

        txtTitle.text = activity.getString(R.string.dialog_import_sessions)
        txtHint.text = activity.getString(R.string.dialog_import_sessions_hint, sessions.size)
        btnConfirm.text = activity.getString(R.string.dialog_import_sessions_button_text)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        val adapter = ImportPreviewAdapter()
        recyclerView.adapter = adapter
        adapter.submitList(sessions)

        btnConfirm.setOnClickListener {
            importConfirmed = true
            bottomSheet.dismiss()
            onConfirm(adapter.getSelectedItems())
        }

        bottomSheet.setOnDismissListener {
            if (!importConfirmed) {
                onDismiss()
            }
        }

        with(com.example.presensor.controllers.dialogs.DialogFactory) {
            bottomSheet.showWithSmartNfcReading()
        }
    }

    override fun showStudentImportPreview(
        activity: AppCompatActivity,
        students: List<Student>,
        onConfirm: (List<Student>) -> Unit,
        onDismiss: () -> Unit
    ) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_list_preview, null)
        bottomSheet.setContentView(view)

        var importConfirmed = false

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvPreviewList)
        val txtTitle = view.findViewById<TextView>(R.id.txtPreviewTitle)
        val txtHint = view.findViewById<TextView>(R.id.txtPreviewHint)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirmAction)

        txtTitle.text = activity.getString(R.string.dialog_import_students)
        txtHint.text = activity.getString(R.string.dialog_import_students_hint, students.size)
        btnConfirm.text = activity.getString(R.string.dialog_import_students_button_text)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        val adapter = ImportStudentAdapter()
        recyclerView.adapter = adapter
        adapter.submitList(students)

        btnConfirm.setOnClickListener {
            importConfirmed = true
            bottomSheet.dismiss()
            onConfirm(adapter.getSelectedItems())
        }

        bottomSheet.setOnDismissListener {
            if (!importConfirmed) {
                onDismiss()
            }
        }

        with(com.example.presensor.controllers.dialogs.DialogFactory) {
            bottomSheet.showWithSmartNfcReading()
        }
    }
}
