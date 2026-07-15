package com.example.presensor.tools.providers

import android.view.LayoutInflater
import android.widget.Button
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

/**
 * Encapsulates the complex UI logic for showing import preview bottom sheets.
 */
class PreviewProvider(private val activity: AppCompatActivity) {

    fun showSessionImportPreview(
        sessions: List<Session>,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(
            R.layout.layout_import_session_preview,
            activity.findViewById(android.R.id.content),
            false
        )
        bottomSheet.setContentView(view)

        var importConfirmed = false

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvImportPreview)
        val txtImportCount = view.findViewById<TextView>(R.id.txtImportCount)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmImport)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = ImportPreviewAdapter(sessions)

        txtImportCount.text = activity.getString(R.string.dialog_import_sessions_hint, sessions.size)
        btnConfirm.text = activity.getString(R.string.dialog_import_sessions_button_text)

        btnConfirm.setOnClickListener {
            importConfirmed = true
            bottomSheet.dismiss()
            onConfirm()
        }

        bottomSheet.setOnDismissListener {
            if (!importConfirmed) {
                onDismiss()
            }
        }
        bottomSheet.show()
    }

    fun showStudentImportPreview(
        students: List<Student>,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.layout_import_student_preview, null)
        bottomSheet.setContentView(view)

        var importConfirmed = false

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvImportStudentPreview)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirmStudentImport)

        view.findViewById<TextView>(R.id.txtImportStudentCount).text =
            activity.getString(R.string.dialog_import_students_hint, students.size)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = ImportStudentAdapter(students)

        btnConfirm.setOnClickListener {
            importConfirmed = true
            bottomSheet.dismiss()
            onConfirm()
        }

        bottomSheet.setOnDismissListener {
            if (!importConfirmed) {
                onDismiss()
            }
        }
        bottomSheet.show()
    }
}
