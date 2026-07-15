package com.example.presensor.controllers

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.presensor.R
import com.example.presensor.tools.providers.DataProcessorProvider
import com.example.presensor.tools.providers.DialogProvider
import com.example.presensor.tools.providers.LoadingOverlayProvider
import com.example.presensor.tools.providers.ToastProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportSessionController(
    private val activity: AppCompatActivity,
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val dataProcessorProvider: DataProcessorProvider,
    private val dialogProvider: DialogProvider,
    private val loadingOverlayProvider: LoadingOverlayProvider,
    private val toastProvider: ToastProvider,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private fun handleTableIngested(
        table: InternalDataTable,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        scope.launch(mainDispatcher) {
            dialogProvider.showMappingDialog(
                context,
                fields = listOf("name", "date"),
                columns = table.headers,
                sampleRow = table.rows.firstOrNull(),
                onDismissed = { loadingOverlayProvider.toggleLoadingOverlay(false) },
                onConfirmed = { mapping ->
                    val result = dataProcessorProvider.parseSessionsFromTable(context, table, courseId, mapping)
                    if (result.items.isNotEmpty()) {
                        dialogProvider.showSessionImportPreview(
                            activity,
                            result.items,
                            onConfirm = { executeImport(result.items, onImportComplete) },
                            onDismiss = { loadingOverlayProvider.toggleLoadingOverlay(false) }
                        )
                        if (result.errors.isNotEmpty()) {
                            toastProvider.showToast(
                                context.getString(R.string.msg_imported_with_errors, result.items.size, result.errors.size),
                                Toast.LENGTH_LONG
                            )
                            result.errors.forEach { Log.w("ImportSession", it) }
                        }
                    } else {
                        val errorMessage = if (result.errors.isNotEmpty()) {
                            context.getString(R.string.msg_failed_to_parse_any, result.errors.take(2).joinToString("\n"))
                        } else {
                            context.getString(R.string.error_parsing_mapping_sessions)
                        }
                        toastProvider.showToast(errorMessage, Toast.LENGTH_LONG)
                        loadingOverlayProvider.toggleLoadingOverlay(false)
                    }
                }
            )
        }
    }

    private fun executeImport(sessions: List<Session>, onImportComplete: () -> Unit) {
        loadingOverlayProvider.toggleLoadingOverlay(true)
        val job = scope.launch {
            withContext(ioDispatcher) {
                db.insertSessions(sessions)
            }
            
            withContext(mainDispatcher) {
                loadingOverlayProvider.toggleLoadingOverlay(false)
                onImportComplete()
                val toastMsg = context.getString(R.string.toast_imported_sessions, sessions.size)
                toastProvider.showToast(toastMsg)
            }
        }
        loadingOverlayProvider.setCurrentOverlayJob(job)
    }

    fun importFromCloud(
        sheetsService: com.google.api.services.sheets.v4.Sheets?,
        spreadsheetId: String,
        tabTitle: String,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        if (sheetsService == null) {
            loadingOverlayProvider.toggleLoadingOverlay(false)
            return
        }
        val job = scope.launch(ioDispatcher) {
            try {
                val table = dataProcessorProvider.ingestFromGoogleSheets(
                    context,
                    sheetsService,
                    spreadsheetId,
                    "'$tabTitle'",
                    caller = "ImportSessionController.importFromCloud"
                )
                handleTableIngested(table, courseId, onImportComplete)
            } catch (e: Exception) {
                Log.e("ImportSession", "Failed to ingest schedule from Sheets", e)
                withContext(mainDispatcher) {
                    loadingOverlayProvider.toggleLoadingOverlay(false)
                    toastProvider.showToast(context.getString(R.string.toast_cloud_schedule_import_failed))
                }
            }
        }
        loadingOverlayProvider.setCurrentOverlayJob(job)
    }

    fun importFromLocal(
        uri: Uri,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        val job = scope.launch(ioDispatcher) {
            try {
                val table = dataProcessorProvider.ingestFromCsv(context.contentResolver, uri, caller = "ImportSessionController.importFromLocal")
                handleTableIngested(table, courseId, onImportComplete)
            } catch (e: Exception) {
                Log.e("ImportSession", "CSV Import error", e)
                withContext(mainDispatcher) {
                    loadingOverlayProvider.toggleLoadingOverlay(false)
                    toastProvider.showToast(context.getString(R.string.toast_csv_import_error))
                }
            }
        }
        loadingOverlayProvider.setCurrentOverlayJob(job)
    }
}
