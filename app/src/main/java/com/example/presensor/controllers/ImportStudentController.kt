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
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportStudentController(
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
        table: InternalDataTable
    ) {
        scope.launch(mainDispatcher) {
            dialogProvider.showMappingDialog(
                context,
                fields = listOf("name", "email"),
                columns = table.headers,
                sampleRow = table.rows.firstOrNull(),
                onDismissed = { loadingOverlayProvider.toggleLoadingOverlay(false) },
                onConfirmed = { mapping ->
                    val result = dataProcessorProvider.parseStudentsFromTable(context, table, mapping)
                    if (result.items.isNotEmpty()) {
                        dialogProvider.showStudentImportPreview(
                            activity,
                            result.items,
                            onConfirm = { executeImport(result.items) },
                            onDismiss = { loadingOverlayProvider.toggleLoadingOverlay(false) }
                        )
                        if (result.errors.isNotEmpty()) {
                            toastProvider.showToast(
                                context.getString(R.string.msg_imported_with_errors, result.items.size, result.errors.size),
                                Toast.LENGTH_LONG
                            )
                            result.errors.forEach { Log.w("ImportStudent", it) }
                        }
                    } else {
                        val errorMessage = if (result.errors.isNotEmpty()) {
                            context.getString(R.string.msg_failed_to_parse_any, result.errors.take(3).joinToString("\n"))
                        } else {
                            context.getString(R.string.toast_cloud_student_import_empty)
                        }
                        toastProvider.showToast(errorMessage, Toast.LENGTH_LONG)
                        loadingOverlayProvider.toggleLoadingOverlay(false)
                    }
                }
            )
        }
    }

    private fun executeImport(students: List<Student>) {
        loadingOverlayProvider.toggleLoadingOverlay(true)
        val job = scope.launch {
            withContext(ioDispatcher) {
                db.insertStudents(students)
            }

            withContext(mainDispatcher) {
                loadingOverlayProvider.toggleLoadingOverlay(false)
                toastProvider.showToast(
                    context.getString(R.string.toast_cloud_student_import_success, students.size)
                )
            }
        }
        loadingOverlayProvider.setCurrentOverlayJob(job)
    }

    fun importFromCloud(
        sheetsService: com.google.api.services.sheets.v4.Sheets?,
        spreadsheetId: String,
        tabTitle: String
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
                    caller = "ImportStudentController.importFromCloud"
                )
                handleTableIngested(table)
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to read rows within selected sheet layout bounds", e)
                withContext(mainDispatcher) {
                    loadingOverlayProvider.toggleLoadingOverlay(false)
                    toastProvider.showToast(
                        context.getString(R.string.toast_cloud_student_import_failed)
                    )
                }
            }
        }
        loadingOverlayProvider.setCurrentOverlayJob(job)
    }

    fun importFromLocal(uri: Uri) {
        val job = scope.launch(ioDispatcher) {
            try {
                val table = dataProcessorProvider.ingestFromCsv(context.contentResolver, uri, caller = "ImportStudentController.importFromLocal")
                handleTableIngested(table)
            } catch (e: Exception) {
                withContext(mainDispatcher) {
                    loadingOverlayProvider.toggleLoadingOverlay(false)
                    toastProvider.showToast(
                        context.getString(R.string.toast_csv_import_error)
                    )
                }
            }
        }
        loadingOverlayProvider.setCurrentOverlayJob(job)
    }
}
