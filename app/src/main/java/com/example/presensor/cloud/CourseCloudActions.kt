package com.example.presensor.cloud

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.ImportSessionController
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.tools.DataProcessor
import com.example.presensor.tools.TimeUtils
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CourseCloudActions(
    private val activity: MainActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase,
    private val getSelectedCourse: () -> Course?,
    private val onImportComplete: () -> Unit
) {

    /**
     * Initiates the authentication and file/tab selection flow for importing a course schedule.
     */
    fun triggerCloudScheduleImport() {
        getSelectedCourse() ?: return
        activity.toggleLoadingOverlay(true)

        val action = {
            activity.cloudSyncController.fetchAvailableSpreadsheets { spreadsheets ->
                activity.toggleLoadingOverlay(false)

                if (spreadsheets.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.toast_cloud_sheets_empty), Toast.LENGTH_SHORT).show()
                    return@fetchAvailableSpreadsheets
                }

                // 1. Select the Spreadsheet file
                activity.cloudSyncController.showCloudFileDialog(
                    title = activity.getString(R.string.dialog_cloud_schedule_import_title),
                    subtitle = activity.getString(R.string.dialog_cloud_schedule_import_subtitle),
                    driveItems = spreadsheets,
                    getName = { it.name }
                ) { selectedSpreadsheet ->

                    activity.toggleLoadingOverlay(true)

                    // 2. Fetch sheet tabs inside that workbook
                    activity.cloudSyncController.fetchSpreadsheetTabs(selectedSpreadsheet.id) { tabs ->
                        activity.toggleLoadingOverlay(false)

                        if (tabs.isEmpty()) {
                            Toast.makeText(activity, activity.getString(R.string.toast_cloud_tab_retrieval_failed), Toast.LENGTH_SHORT).show()
                            return@fetchSpreadsheetTabs
                        }

                        // 3. Select the target tab containing the schedule rows
                        activity.cloudSyncController.showCloudFileDialog(
                            title = activity.getString(R.string.dialog_cloud_select_schedule_tab_title),
                            subtitle = activity.getString(R.string.dialog_cloud_select_schedule_tab_subtitle),
                            driveItems = tabs,
                            getName = { it }
                        ) { selectedTab ->
                            // 4. Trigger background parsing matching local structural rules
                            activity.importSessionController.importFromCloud(
                                sheetsService = activity.cloudSyncController.getSheetsService(),
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

        activity.setPendingAction(action)
        activity.cloudSyncController.runWithCloudAuthentication(activity.cloudSignInLauncher, action)
    }

    /**
     * Initiates the authentication and sheet selection flow for updating attendance matrices.
     */
    fun triggerCloudAttendanceExport() {
        getSelectedCourse() ?: return
        activity.toggleLoadingOverlay(true)

        val action = {
            activity.cloudSyncController.fetchAvailableSpreadsheets { spreadsheets ->
                activity.toggleLoadingOverlay(false)

                if (spreadsheets.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.toast_cloud_sheets_empty), Toast.LENGTH_SHORT).show()
                    return@fetchAvailableSpreadsheets
                }

                // Show file dialog matching the correct Drive File type signature
                activity.cloudSyncController.showCloudFileDialog(
                    title = activity.getString(R.string.dialog_cloud_export_attendance_title),
                    subtitle = activity.getString(R.string.dialog_cloud_export_attendance_subtitle),
                    driveItems = spreadsheets,
                    getName = { it.name }
                ) { selectedSpreadsheet ->

                    activity.toggleLoadingOverlay(true)

                    // Fetch sheets/tabs inside workbook
                    activity.cloudSyncController.fetchSpreadsheetTabs(selectedSpreadsheet.id) { tabs ->
                        activity.toggleLoadingOverlay(false)

                        if (tabs.isEmpty()) {
                            Toast.makeText(activity, activity.getString(R.string.toast_cloud_sheet_tabs_failed), Toast.LENGTH_SHORT).show()
                            return@fetchSpreadsheetTabs
                        }

                        // Select target tab using the exact same dialog container mapped to Strings
                        activity.cloudSyncController.showCloudFileDialog(
                            title = activity.getString(R.string.dialog_cloud_select_target_tab_title),
                            subtitle = activity.getString(R.string.dialog_cloud_select_target_tab_subtitle),
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

        activity.setPendingAction(action)
        activity.cloudSyncController.runWithCloudAuthentication(activity.cloudSignInLauncher, action)
    }

    /**
     * Downloads the chosen spreadsheet matrix, joins missing students/sessions, and updates cells.
     */
    private fun performCloudSpreadsheetMatrixSync(spreadsheetId: String, tabName: String) {
        val course = getSelectedCourse() ?: return
        activity.toggleLoadingOverlay(true)

        val job = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = activity.cloudSyncController.getSheetsService()
                    ?: throw IllegalStateException("Sheets service was not initialized properly")

                val dateFormat = TimeUtils.makeSessionTimeFormatter(activity)

                // 1. Query Local Room DB State Trees
                val localSessions = db.getSessionsByCourse(course.id).sortedBy { it.date }
                val localStudents = db.getStudentsForCourse(course.id).sortedBy { it.email }
                val localAttendanceMap = db.getAllAttendanceForCourse(course.id)
                    .groupBy { it.studentEmail to it.sessionId }

                // 2. Fetch Existing Cloud Layout Bounds
                val table = DataProcessor.ingestFromGoogleSheets(
                    activity,
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
                        activity.getString(R.string.label_student_email_column),
                        activity.getString(R.string.label_student_name_column)
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

                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, activity.getString(R.string.toast_cloud_attendance_sync_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("CourseCloudActions", "Cloud spreadsheet matching sync execution crash", e)
                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, activity.getString(R.string.toast_cloud_sync_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        activity.setCurrentOverlayJob(job)
    }
}
