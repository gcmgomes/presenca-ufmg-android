package com.example.presensor.controllers

import android.net.Uri
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.CourseUtilities
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.adapters.ImportStudentAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.presensor.data.entities.Student

object ImportStudentController {

    private fun importStudentsFromSheet(
        activity: MainActivity,
        sheetsService: com.google.api.services.sheets.v4.Sheets,
        spreadsheetId: String,
        tabTitle: String
    ) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Query columns A to D to pull structural content data arrays
                val range = "'$tabTitle'!A1:D500"
                val response =
                    sheetsService.spreadsheets().values().get(spreadsheetId, range).execute()
                val rows = response.getValues() ?: emptyList()

                val students: MutableList<Student> = mutableListOf()
                if (rows.isNotEmpty()) {
                    for (i in 1 until rows.size) {
                        val row = rows[i]
                        if (row.size >= 2) {
                            val studentName = row[0].toString().trim()
                            val studentEmail = row[1].toString().trim()
                            val studentRfid: String? = if (row.size >= 3) {
                                row[2].toString().trim()
                            } else {
                                null
                            }

                            // Break check loop sequence rule safely
                            if (studentName.isEmpty() || studentEmail.isEmpty()) {
                                break
                            }

                            students += Student(studentEmail, studentName, studentRfid)
                        }
                    }
                }

                // CRITICAL: Switch back to the UI thread to update your layouts
                withContext(Dispatchers.Main) {
                    // Turn off the loading screen overlay if you have access to it via your activity
                    // (Adjust this line to match how your toggleLoadingOverlay is exposed to the controller)
                    // activity.toggleLoadingOverlay(false)

                    showStudentImportPreview(activity, students)
                }

            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to read rows within selected sheet layout bounds", e)
                withContext(Dispatchers.Main) {
                    // Make sure to hide the overlay on network error transitions too
                    // activity.toggleLoadingOverlay(false)
                    Toast.makeText(
                        activity,
                        "Failed to import rows from cloud sheet",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
        importStudentsFromSheet(activity, sheetsService, spreadsheetId, tabTitle)
    }

    private fun importFromCsv(activity: MainActivity, uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val students = CourseUtilities.parseStudentsFromCsv(activity.contentResolver, uri)
                withContext(Dispatchers.Main) {
                    if (students.isNotEmpty()) {
                        showStudentImportPreview(activity, students)
                    } else {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.msg_no_students_found),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
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

            activity.lifecycleScope.launch {
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