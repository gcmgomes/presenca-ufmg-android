package com.example.presensor.controllers

import android.net.Uri
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.adapters.ImportStudentAdapter
import com.example.presensor.tools.DataProcessor
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Student
import com.example.presensor.controllers.dialogs.DialogFactory
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImportStudentController {

    private fun handleTableIngested(
        activity: MainActivity,
        table: InternalDataTable
    ) {
        activity.lifecycleScope.launch(Dispatchers.Main) {
            DialogFactory.showMappingDialog(
                activity,
                fields = listOf("name", "email"),
                columns = table.headers,
                sampleRow = table.rows.firstOrNull(),
                onDismissed = { activity.toggleLoadingOverlay(false) },
                onConfirmed = { mapping ->
                    val result = DataProcessor.parseStudentsFromTable(activity, table, mapping)
                    if (result.items.isNotEmpty()) {
                        showStudentImportPreview(activity, result.items)
                        if (result.errors.isNotEmpty()) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.msg_imported_with_errors, result.items.size, result.errors.size),
                                Toast.LENGTH_LONG
                            ).show()
                            // Log specific errors for the developer/user to see in logcat if needed
                            result.errors.forEach { Log.w("ImportStudent", it) }
                        }
                    } else {
                        val errorMessage = if (result.errors.isNotEmpty()) {
                            activity.getString(R.string.msg_failed_to_parse_any, result.errors.take(3).joinToString("\n"))
                        } else {
                            activity.getString(R.string.toast_cloud_student_import_empty)
                        }
                        Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show()
                        activity.toggleLoadingOverlay(false)
                    }
                }
            )
        }
    }

    fun importFromCloud(
        activity: MainActivity,
        sheetsService: com.google.api.services.sheets.v4.Sheets?,
        spreadsheetId: String,
        tabTitle: String
    ) {
        if (sheetsService == null) {
            activity.toggleLoadingOverlay(false)
            return
        }
        activity.currentOverlayJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val table = DataProcessor.ingestFromGoogleSheets(
                    activity,
                    sheetsService,
                    spreadsheetId,
                    "'$tabTitle'",
                    caller = "ImportStudentController.importFromCloud"
                )
                handleTableIngested(activity, table)
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to read rows within selected sheet layout bounds", e)
                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_cloud_student_import_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun importFromCsv(activity: MainActivity, uri: Uri) {
        activity.currentOverlayJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val table = DataProcessor.ingestFromCsv(activity.contentResolver, uri, caller = "ImportStudentController.importFromCsv")
                handleTableIngested(activity, table)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun importFromLocal(activity: MainActivity, uri: Uri) {
        importFromCsv(activity, uri)
    }

    private fun showStudentImportPreview(activity: MainActivity, students: List<Student>) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.layout_import_student_preview, null)
        bottomSheet.setContentView(view)

        // Flag to keep track of whether the user actually confirmed the action
        var importConfirmed = false

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvImportStudentPreview)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirmStudentImport)

        view.findViewById<TextView>(R.id.txtImportStudentCount).text =
            activity.getString(R.string.dialog_import_students_hint, students.size)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = ImportStudentAdapter(students)

        btnConfirm.setOnClickListener {
            importConfirmed = true
            // Ensure the loading wheel stays visible while the database is working
            activity.toggleLoadingOverlay(true)

            activity.currentOverlayJob = activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    activity.getDb().insertStudents(students)
                }

                // Once DB work completes on the background thread, dismiss dialog and overlay on Main
                bottomSheet.dismiss()
                activity.toggleLoadingOverlay(false)

                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_cloud_student_import_success, students.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // This catches ALL dismiss events (sliding away, tapping outside, back button)
        bottomSheet.setOnDismissListener {
            if (!importConfirmed) {
                // The user canceled the preview, hide the loading wheel immediately
                activity.toggleLoadingOverlay(false)
            }
        }

        bottomSheet.show()
    }
}
