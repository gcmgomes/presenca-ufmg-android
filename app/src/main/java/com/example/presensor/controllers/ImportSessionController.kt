package com.example.presensor.controllers

import android.net.Uri
import android.util.Log
import com.example.presensor.R
import com.example.presensor.controllers.providers.SessionInteractionProvider
import com.example.presensor.tools.providers.DataProcessorProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportSessionController(
    private val interactionProvider: SessionInteractionProvider,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val dataProcessorProvider: DataProcessorProvider,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private fun handleTableIngested(
        table: InternalDataTable,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        scope.launch(mainDispatcher) {
            interactionProvider.showMappingDialog(
                fields = listOf("name", "date"),
                columns = table.headers,
                sampleRow = table.rows.firstOrNull(),
                onDismissed = { interactionProvider.toggleLoading(false) },
                onConfirmed = { mapping ->
                    val result = dataProcessorProvider.parseSessionsFromTable(
                        interactionProvider.getContext(),
                        table,
                        courseId,
                        mapping
                    )
                    if (result.items.isNotEmpty()) {
                        interactionProvider.showSessionImportPreview(
                            result.items,
                            onConfirm = { selected -> executeImport(selected, onImportComplete) },
                            onDismiss = { interactionProvider.toggleLoading(false) }
                        )
                        if (result.errors.isNotEmpty()) {
                            interactionProvider.showToast(
                                interactionProvider.getString(
                                    R.string.msg_imported_with_errors,
                                    result.items.size,
                                    result.errors.size
                                ),
                                isShort = false
                            )
                            result.errors.forEach { Log.w("ImportSession", it) }
                        }
                    } else {
                        val errorMessage = if (result.errors.isNotEmpty()) {
                            interactionProvider.getString(
                                R.string.msg_failed_to_parse_any,
                                result.errors.take(2).joinToString("\n")
                            )
                        } else {
                            interactionProvider.getString(R.string.error_parsing_mapping_sessions)
                        }
                        interactionProvider.showToast(errorMessage, isShort = false)
                        interactionProvider.toggleLoading(false)
                    }
                }
            )
        }
    }

    private fun executeImport(sessions: List<Session>, onImportComplete: () -> Unit) {
        interactionProvider.toggleLoading(true)
        val job = scope.launch {
            withContext(ioDispatcher) {
                db.insertSessions(sessions)
            }

            withContext(mainDispatcher) {
                interactionProvider.toggleLoading(false)
                onImportComplete()
                val toastMsg = interactionProvider.getString(R.string.toast_imported_sessions, sessions.size)
                interactionProvider.showToast(toastMsg)
            }
        }
        interactionProvider.setLoadingJob(job)
    }

    fun importFromCloud(
        sheetsService: com.google.api.services.sheets.v4.Sheets?,
        spreadsheetId: String,
        tabTitle: String,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        if (sheetsService == null) {
            interactionProvider.toggleLoading(false)
            return
        }
        val job = scope.launch(ioDispatcher) {
            try {
                val table = dataProcessorProvider.ingestFromGoogleSheets(
                    interactionProvider.getContext(),
                    sheetsService,
                    spreadsheetId,
                    "'$tabTitle'",
                    caller = "ImportSessionController.importFromCloud"
                )
                handleTableIngested(table, courseId, onImportComplete)
            } catch (e: Exception) {
                Log.e("ImportSession", "Failed to ingest schedule from Sheets", e)
                withContext(mainDispatcher) {
                    interactionProvider.toggleLoading(false)
                    interactionProvider.showToast(interactionProvider.getString(R.string.toast_cloud_schedule_import_failed))
                }
            }
        }
        interactionProvider.setLoadingJob(job)
    }

    fun importFromLocal(
        uri: Uri,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        val job = scope.launch(ioDispatcher) {
            try {
                val table = dataProcessorProvider.ingestFromCsv(
                    interactionProvider.getContentResolver(),
                    uri,
                    caller = "ImportSessionController.importFromLocal"
                )
                handleTableIngested(table, courseId, onImportComplete)
            } catch (e: Exception) {
                Log.e("ImportSession", "CSV Import error", e)
                withContext(mainDispatcher) {
                    interactionProvider.toggleLoading(false)
                    interactionProvider.showToast(interactionProvider.getString(R.string.toast_csv_import_error))
                }
            }
        }
        interactionProvider.setLoadingJob(job)
    }
}
