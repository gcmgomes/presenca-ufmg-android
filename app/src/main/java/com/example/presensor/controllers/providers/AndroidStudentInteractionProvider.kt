package com.example.presensor.controllers.providers

import android.view.LayoutInflater
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.adapters.ImportStudentAdapter
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory.showWithSmartNfcReading
import com.example.presensor.data.entities.Student
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

class AndroidStudentInteractionProvider(
    activity: MainActivity
) : BaseAndroidInteractionProvider(activity), StudentInteractionProvider {

    override fun showStudentImportPreview(
        students: List<Student>,
        onConfirm: (List<Student>) -> Unit,
        onDismiss: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_list_preview, null)
            val rvPreview = dialogView.findViewById<RecyclerView>(R.id.rvPreviewList)
            val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmAction)
            val txtTitle = dialogView.findViewById<TextView>(R.id.txtPreviewTitle)
            val txtHint = dialogView.findViewById<TextView>(R.id.txtPreviewHint)

            txtTitle.text = activity.getString(R.string.dialog_import_students)
            txtHint.text = activity.getString(R.string.dialog_import_students_hint, students.size)
            btnConfirm.text = activity.getString(R.string.dialog_import_students_button_text)

            val adapter = ImportStudentAdapter()
            rvPreview.layoutManager = LinearLayoutManager(activity)
            rvPreview.adapter = adapter
            adapter.submitList(students)

            val dialog = BottomSheetDialog(activity)
            activeBottomSheet = dialog
            dialog.setContentView(dialogView)

            btnConfirm.setOnClickListener {
                onConfirm(adapter.getSelectedItems())
                dialog.dismiss()
            }

            dialog.setOnDismissListener {
                activeBottomSheet = null
                onDismiss()
            }

            with(DialogFactory) {
                dialog.showWithSmartNfcReading()
            }
        }
    }
}
