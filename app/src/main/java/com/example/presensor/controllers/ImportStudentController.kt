package com.example.presensor.controllers

import android.net.Uri
import android.util.Log
import com.example.presensor.R
import com.example.presensor.controllers.providers.StudentInteractionProvider
import com.example.presensor.tools.providers.DataProcessorProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportStudentController(
    private val interactionProvider: StudentInteractionProvider,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val dataProcessorProvider: DataProcessorProvider,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private fun handleTableIngested(
        table: InternalDataTable
    ) {
        scope.launch(mainDispatcher) {
            interactionProvider.showMappingDialog(
                fields = listOf("name", "email"),
                columns = table.headers,
                sampleRow = table.rows.firstOrNull(),
                onDismissed = { interactionProvider.toggleLoading(false) },
                onConfirmed = { mapping ->
                    val result =
                        dataProcessorProvider.parseStudentsFromTable(interactionProvider.getContext(), table, mapping)
                    if (result.items.isNotEmpty()) {
                        interactionProvider.showStudentImportPreview(
                            result.items,
                            onConfirm = { selected -> executeImport(selected) },
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
                            result.errors.forEach { Log.w("ImportStudent", it) }
                        }
                    } else {
                        val errorMessage = if (result.errors.isNotEmpty()) {
                            interactionProvider.getString(
                                R.string.msg_failed_to_parse_any,
                                result.errors.take(3).joinToString("\n")
                            )
                        } else {
                            interactionProvider.getString(R.string.toast_cloud_student_import_empty)
                        }
                        interactionProvider.showToast(errorMessage, isShort = false)
                        interactionProvider.toggleLoading(false)
                    }
                }
            )
        }
    }

    private fun executeImport(students: List<Student>) {
        interactionProvider.toggleLoading(true)
        val job = scope.launch {
            withContext(ioDispatcher) {
                db.insertStudents(students)
            }

            withContext(mainDispatcher) {
                interactionProvider.toggleLoading(false)
                interactionProvider.showToast(
                    interactionProvider.getString(R.string.toast_cloud_student_import_success, students.size)
                )
            }
        }
        interactionProvider.setLoadingJob(job)
    }

    fun importFromCloud(
        sheetsService: com.google.api.services.sheets.v4.Sheets?,
        spreadsheetId: String,
        tabTitle: String
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
                    caller = "ImportStudentController.importFromCloud"
                )
                handleTableIngested(table)
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to read rows within selected sheet layout bounds", e)
                withContext(mainDispatcher) {
                    interactionProvider.toggleLoading(false)
                    interactionProvider.showToast(
                        interactionProvider.getString(R.string.toast_cloud_student_import_failed)
                    )
                }
            }
        }
        interactionProvider.setLoadingJob(job)
    }

    fun importFromLocal(uri: Uri) {
        val job = scope.launch(ioDispatcher) {
            try {
                val table = dataProcessorProvider.ingestFromCsv(
                    interactionProvider.getContentResolver(),
                    uri,
                    caller = "ImportStudentController.importFromLocal"
                )
                handleTableIngested(table)
            } catch (e: Exception) {
                withContext(mainDispatcher) {
                    interactionProvider.toggleLoading(false)
                    interactionProvider.showToast(
                        interactionProvider.getString(R.string.toast_csv_import_error)
                    )
                }
            }
        }
        interactionProvider.setLoadingJob(job)
    }
}
