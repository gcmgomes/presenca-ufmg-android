package com.example.presensor.cloud

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.R
import com.example.presensor.controllers.CloudSyncController
import com.example.presensor.controllers.ImportSessionController
import com.example.presensor.controllers.providers.InteractionProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.tools.DataLoader
import com.example.presensor.tools.DataProcessor
import com.example.presensor.tools.TimeUtils
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CourseCloudActions(
    private val uiProvider: InteractionProvider,
    private val cloudSyncController: CloudSyncController,
    private val importSessionController: ImportSessionController,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase,
    private val getSelectedCourse: () -> Course?,
    private val onImportComplete: () -> Unit,
    private val runWithCloudAuthentication: (() -> Unit) -> Unit,
    private val setCurrentOverlayJob: (Job?) -> Unit,
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val dataLoader: DataLoader = DataProcessor
) {

    /**
     * Initiates the authentication and file/tab selection flow for importing a course schedule.
     */
    fun triggerCloudScheduleImport() {
        getSelectedCourse() ?: return
        uiProvider.toggleLoading(true)

        val action = {
            cloudSyncController.fetchAvailableSpreadsheets { spreadsheets ->
                uiProvider.toggleLoading(false)

                if (spreadsheets.isEmpty()) {
                    uiProvider.showToast(R.string.toast_cloud_sheets_empty)
                    return@fetchAvailableSpreadsheets
                }

                // 1. Select the Spreadsheet file
                cloudSyncController.showCloudFileDialog(
                    title = uiProvider.getString(R.string.dialog_cloud_schedule_import_title),
                    subtitle = uiProvider.getString(R.string.dialog_cloud_schedule_import_subtitle),
                    driveItems = spreadsheets,
                    getName = { it.name }
                ) { selectedSpreadsheet ->

                    uiProvider.toggleLoading(true)

                    // 2. Fetch sheet tabs inside that workbook
                    cloudSyncController.fetchSpreadsheetTabs(selectedSpreadsheet.id) { tabs ->
                        uiProvider.toggleLoading(false)

                        if (tabs.isEmpty()) {
                            uiProvider.showToast(R.string.toast_cloud_tab_retrieval_failed)
                            return@fetchSpreadsheetTabs
                        }

                        // 3. Select the target tab containing the schedule rows
                        cloudSyncController.showCloudFileDialog(
                            title = uiProvider.getString(R.string.dialog_cloud_select_schedule_tab_title),
                            subtitle = uiProvider.getString(R.string.dialog_cloud_select_schedule_tab_subtitle),
                            driveItems = tabs,
                            getName = { it }
                        ) { selectedTab ->
                            // 4. Trigger background parsing matching local structural rules
                            importSessionController.importFromCloud(
                                sheetsService = cloudSyncController.getSheetsService(),
                                spreadsheetId = selectedSpreadsheet.id,
                                tabTitle = selectedTab,
                                courseId = getSelectedCourse()?.id ?: -1,
                                onImportComplete = onImportComplete
                            )
                        }
                    }
                }
            }
        }

        runWithCloudAuthentication(action)
    }

    /**
     * Initiates the authentication and sheet selection flow for updating attendance matrices.
     */
    fun triggerCloudAttendanceExport() {
        getSelectedCourse() ?: return
        uiProvider.toggleLoading(true)

        val action = {
            cloudSyncController.fetchAvailableSpreadsheets { spreadsheets ->
                uiProvider.toggleLoading(false)

                if (spreadsheets.isEmpty()) {
                    uiProvider.showToast(R.string.toast_cloud_sheets_empty)
                    return@fetchAvailableSpreadsheets
                }

                // Show file dialog matching the correct Drive File type signature
                cloudSyncController.showCloudFileDialog(
                    title = uiProvider.getString(R.string.dialog_cloud_export_attendance_title),
                    subtitle = uiProvider.getString(R.string.dialog_cloud_export_attendance_subtitle),
                    driveItems = spreadsheets,
                    getName = { it.name }
                ) { selectedSpreadsheet ->

                    uiProvider.toggleLoading(true)

                    // Fetch sheets/tabs inside workbook
                    cloudSyncController.fetchSpreadsheetTabs(selectedSpreadsheet.id) { tabs ->
                        uiProvider.toggleLoading(false)

                        if (tabs.isEmpty()) {
                            uiProvider.showToast(R.string.toast_cloud_sheet_tabs_failed)
                            return@fetchSpreadsheetTabs
                        }

                        // Select target tab using the exact same dialog container mapped to Strings
                        cloudSyncController.showCloudFileDialog(
                            title = uiProvider.getString(R.string.dialog_cloud_select_target_tab_title),
                            subtitle = uiProvider.getString(R.string.dialog_cloud_select_target_tab_subtitle),
                            driveItems = tabs,
                            getName = { it }
                        ) { selectedTab ->
                            // Execute grid operations on background workers safely
                            performCloudSpreadsheetMatrixSync(selectedSpreadsheet.id, selectedTab)
                        }
                    }
                }
            }
        }

        runWithCloudAuthentication(action)
    }

    /**
     * Downloads the chosen spreadsheet matrix, joins missing students/sessions, and updates cells.
     */
    internal fun performCloudSpreadsheetMatrixSync(spreadsheetId: String, tabName: String) {
        val course = getSelectedCourse() ?: return
        uiProvider.toggleLoading(true)

        val job = lifecycleOwner.lifecycleScope.launch(ioDispatcher) {
            try {
                val sheetsService = cloudSyncController.getSheetsService()
                    ?: throw IllegalStateException("Sheets service was not initialized properly")

                val dateFormat = TimeUtils.makeSessionTimeFormatter(uiProvider.getContext())

                // 1. Query Local Room DB State Trees
                val localSessions = db.getSessionsByCourse(course.id).sortedBy { it.date }
                val localStudents = db.getStudentsForCourse(course.id).sortedBy { it.email }
                val localAttendanceMap = db.getAllAttendanceForCourse(course.id)
                    .groupBy { it.studentEmail to it.sessionId }

                // 2. Fetch Existing Cloud Layout Bounds
                val table = dataLoader.ingestFromGoogleSheets(
                    uiProvider.getContext(),
                    sheetsService,
                    spreadsheetId,
                    "'$tabName'",
                    caller = "CourseCloudActions.performCloudSpreadsheetMatrixSync"
                )

                val currentGrid: MutableList<MutableList<String>> = table.toFullGrid().map { it.toMutableList() }.toMutableList()

                // Initialize structural headers if sheet tab is completely pristine
                if (currentGrid.size <= 1 && table.headers.isEmpty()) {
                    currentGrid.clear()
                    currentGrid.add(mutableListOf(
                        uiProvider.getString(R.string.label_student_email_column),
                        uiProvider.getString(R.string.label_student_name_column)
                    ))
                }

                val headerRow = currentGrid[0]

                // 3. Coordinate Columns Alignment (Sessions)
                val sessionToColumnIdx = mutableMapOf<Long, Int>()
                localSessions.forEach { session ->
                    val formattedHeader = "${session.name} (${TimeUtils.fromMillisToLocalDate(session.date).format(dateFormat)})"
                    var matchIndex = headerRow.indexOfFirst { it.equals(formattedHeader, ignoreCase = true) }
                    if (matchIndex == -1) {
                        headerRow.add(formattedHeader)
                        matchIndex = headerRow.lastIndex
                    }
                    sessionToColumnIdx[session.id] = matchIndex
                }

                // 4. Coordinate Rows Alignment (Students)
                val studentToRowIdx = mutableMapOf<String, Int>()
                for (i in 1 until currentGrid.size) {
                    val emailCell = currentGrid[i].getOrNull(0)?.toString()?.trim() ?: ""
                    if (emailCell.isNotEmpty()) {
                        studentToRowIdx[emailCell] = i
                    }
                }

                localStudents.forEach { student ->
                    if (!studentToRowIdx.containsKey(student.email)) {
                        val newRow = mutableListOf<String>(student.email, student.name)
                        currentGrid.add(newRow)
                        studentToRowIdx[student.email] = currentGrid.lastIndex
                    }
                }

                // 5. Fill and Update Intersections (Attendance Matrix Joining)
                localStudents.forEach { student ->
                    val rowIdx = studentToRowIdx[student.email] ?: return@forEach
                    val rowData = currentGrid[rowIdx]

                    // Pad list elements out to match expansion columns size safely
                    while (rowData.size < headerRow.size) {
                        rowData.add("")
                    }

                    localSessions.forEach { session ->
                        val colIdx = sessionToColumnIdx[session.id] ?: return@forEach
                        val key = student.email to session.id
                        val wasPresent = localAttendanceMap.containsKey(key)

                        // Assign "P" for Present and "A" for Absent
                        rowData[colIdx] = if (wasPresent) "P" else "A"
                    }
                }

                // 6. Save Matched Matrix State back to Google Drive Context
                val updateBody = ValueRange().setValues(currentGrid as List<List<Any>>?)
                sheetsService.spreadsheets().values()
                    .update(spreadsheetId, "'$tabName'!A1", updateBody)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                withContext(mainDispatcher) {
                    uiProvider.toggleLoading(false)
                    uiProvider.showToast(R.string.toast_cloud_attendance_sync_success)
                }
            } catch (e: Exception) {
                Log.e("CourseCloudActions", "Cloud spreadsheet matching sync execution crash", e)
                withContext(mainDispatcher) {
                    uiProvider.toggleLoading(false)
                    uiProvider.showToast(R.string.toast_cloud_sync_failed)
                }
            }
        }
        setCurrentOverlayJob(job)
    }
}
