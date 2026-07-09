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
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ImportSessionController {

    private fun importSessionsFromTable(
        activity: MainActivity,
        table: InternalDataTable,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        val sessions = DataProcessor.parseSessionsFromTable(table, courseId)

        activity.lifecycleScope.launch(Dispatchers.Main) {
            if (sessions.isNotEmpty()) {
                showSessionImportPreview(activity, sessions, onImportComplete)
            } else {
                Toast.makeText(
                    activity,
                    "No sessions found in the provided data",
                    Toast.LENGTH_SHORT
                ).show()
                activity.toggleLoadingOverlay(false)
            }
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
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val table = DataProcessor.ingestFromGoogleSheets(
                    sheetsService,
                    spreadsheetId,
                    "'$tabTitle'!A1:B100"
                )
                importSessionsFromTable(activity, table, courseId, onImportComplete)
            } catch (e: Exception) {
                Log.e("ImportSession", "Failed to ingest schedule from Sheets", e)
                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, "Cloud schedule import failed.", Toast.LENGTH_SHORT).show()
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
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val table = DataProcessor.ingestFromCsv(activity.contentResolver, uri)
                importSessionsFromTable(activity, table, courseId, onImportComplete)
            } catch (e: Exception) {
                Log.e("ImportSession", "CSV Import error", e)
                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, "CSV Import error", Toast.LENGTH_SHORT).show()
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

            activity.lifecycleScope.launch {
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
