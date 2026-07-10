package com.example.presensor.controllers

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.adapters.ImportPreviewAdapter
import com.example.presensor.tools.DataProcessor
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Session
import com.example.presensor.controllers.dialogs.DialogFactory
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImportSessionController {

    private fun handleTableIngested(
        activity: MainActivity,
        table: InternalDataTable,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        activity.lifecycleScope.launch(Dispatchers.Main) {
            DialogFactory.showMappingDialog(
                activity,
                fields = listOf("name", "date"),
                columns = table.headers,
                sampleRow = table.rows.firstOrNull(),
                onDismissed = { activity.toggleLoadingOverlay(false) },
                onConfirmed = { mapping ->
                    val result = DataProcessor.parseSessionsFromTable(activity, table, courseId, mapping)
                    if (result.items.isNotEmpty()) {
                        showSessionImportPreview(activity, result.items, onImportComplete)
                        if (result.errors.isNotEmpty()) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.msg_imported_with_errors, result.items.size, result.errors.size),
                                Toast.LENGTH_LONG
                            ).show()
                            result.errors.forEach { Log.w("ImportSession", it) }
                        }
                    } else {
                        val errorMessage = if (result.errors.isNotEmpty()) {
                            activity.getString(R.string.msg_failed_to_parse_any, result.errors.take(2).joinToString("\n"))
                        } else {
                            activity.getString(R.string.error_parsing_mapping_sessions)
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
        tabTitle: String,
        courseId: Long,
        onImportComplete: () -> Unit
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
                    caller = "ImportSessionController.importFromCloud"
                )
                handleTableIngested(activity, table, courseId, onImportComplete)
            } catch (e: Exception) {
                Log.e("ImportSession", "Failed to ingest schedule from Sheets", e)
                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, activity.getString(R.string.toast_cloud_schedule_import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun importFromLocal(
        activity: MainActivity,
        uri: Uri,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        activity.currentOverlayJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val table = DataProcessor.ingestFromCsv(activity.contentResolver, uri, caller = "ImportSessionController.importFromLocal")
                handleTableIngested(activity, table, courseId, onImportComplete)
            } catch (e: Exception) {
                Log.e("ImportSession", "CSV Import error", e)
                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, activity.getString(R.string.toast_csv_import_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSessionImportPreview(
        activity: MainActivity,
        sessions: List<Session>,
        onImportComplete: () -> Unit
    ) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.layout_import_session_preview, activity.findViewById(android.R.id.content), false)
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
            activity.toggleLoadingOverlay(true)

            activity.currentOverlayJob = activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    activity.getDb().insertSessions(sessions)
                }
                
                bottomSheet.dismiss()
                activity.toggleLoadingOverlay(false)
                
                onImportComplete()

                val toastMsg = activity.getString(R.string.toast_imported_sessions, sessions.size)
                Toast.makeText(activity, toastMsg, Toast.LENGTH_SHORT).show()
            }
        }

        bottomSheet.setOnDismissListener {
            if (!importConfirmed) {
                activity.toggleLoadingOverlay(false)
            }
        }

        bottomSheet.show()
    }
}
