package com.example.presensor.cloud

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.CourseUtilities
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
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
    private val showImportPreview: (List<Session>) -> Unit
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
                    Toast.makeText(activity, "No spreadsheets found on Google Drive.", Toast.LENGTH_SHORT).show()
                    return@fetchAvailableSpreadsheets
                }

                // 1. Select the Spreadsheet file
                activity.cloudSyncController.showCloudFileDialog(
                    title = "Import Schedule from Sheets",
                    subtitle = "Choose the Google Spreadsheet containing your class schedule calendar:",
                    driveItems = spreadsheets,
                    getName = { it.name }
                ) { selectedSpreadsheet ->

                    activity.toggleLoadingOverlay(true)

                    // 2. Fetch sheet tabs inside that workbook
                    activity.cloudSyncController.fetchSpreadsheetTabs(selectedSpreadsheet.id) { tabs ->
                        activity.toggleLoadingOverlay(false)

                        if (tabs.isEmpty()) {
                            Toast.makeText(activity, "Failed to retrieve worksheet tabs.", Toast.LENGTH_SHORT).show()
                            return@fetchSpreadsheetTabs
                        }

                        // 3. Select the target tab containing the schedule rows
                        activity.cloudSyncController.showCloudFileDialog(
                            title = "Select Schedule Tab",
                            subtitle = "Choose the worksheet tab that contains your session dates:",
                            driveItems = tabs,
                            getName = { it }
                        ) { selectedTab ->
                            // 4. Trigger background parsing matching local structural rules
                            performCloudScheduleImport(selectedSpreadsheet.id, selectedTab)
                        }
                    }
                }
            }
        }

        activity.setPendingAction(action)
        activity.cloudSyncController.runWithCloudAuthentication(activity.cloudSignInLauncher, action)
    }

    /**
     * Downloads spreadsheet rows from Drive, parses them into Session entities,
     * and hands them off to the preview pane. Empty topics are skipped.
     */
    private fun performCloudScheduleImport(spreadsheetId: String, tabName: String) {
        val course = getSelectedCourse() ?: return
        activity.toggleLoadingOverlay(true)

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = activity.cloudSyncController.getSheetsService()
                    ?: throw IllegalStateException("Sheets service was not initialized properly")

                // Pull down columns A and B (A = Data, B = Tópicos) up to 100 rows
                val response = sheetsService.spreadsheets().values()
                    .get(spreadsheetId, "'$tabName'!A1:B100")
                    .execute()

                val rows = response.getValues() ?: emptyList<List<Any>>()
                val sessionsToInsert = mutableListOf<Session>()

                // Explicitly handles the "dd/MM/yyyy" date format from your schedule sheets
                val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

                for (row in rows) {
                    val rawDateStr = row.getOrNull(0)?.toString()?.trim() ?: ""
                    val sessionName = row.getOrNull(1)?.toString()?.trim() ?: ""

                    // Strict validation: Skip headers, completely blank lines, or rows missing a topic title
                    if (rawDateStr.isEmpty() || sessionName.isEmpty() || rawDateStr.equals("Data", ignoreCase = true)) {
                        continue
                    }

                    try {
                        // Parse the date safely from Column A
                        val parsedLocalDate = LocalDate.parse(rawDateStr, dateFormatter)
                        val epochMillis = parsedLocalDate.atStartOfDay(ZoneId.systemDefault())
                            .toInstant().toEpochMilli()

                        val session = Session(
                            courseId = course.id,
                            name = sessionName,
                            date = epochMillis,
                            isLocked = false
                        )
                        sessionsToInsert.add(session)
                    } catch (dateEx: Exception) {
                        Log.w("CourseCloudActions", "Skipping malformed calendar row date string: $rawDateStr", dateEx)
                    }
                }

                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    if (sessionsToInsert.isNotEmpty()) {
                        // Match the structural presentation logic perfectly, passing to bottom sheet preview
                        showImportPreview(sessionsToInsert)
                    } else {
                        Toast.makeText(activity, "No valid structural schedule entries found.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseCloudActions", "Google Sheets schedule parser crash", e)
                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, "Cloud schedule import failed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                    title = "Select Sheet to Export Attendance",
                    subtitle = "Choose the Google Spreadsheet to update with current session records:",
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
                            title = "Select Target Sheet Tab",
                            subtitle = "Choose the worksheet tab to merge attendance matrix columns:",
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

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = activity.cloudSyncController.getSheetsService()
                    ?: throw IllegalStateException("Sheets service was not initialized properly")

                val dateFormat = CourseUtilities.makeSessionTimeFormatter(activity)

                // 1. Query Local Room DB State Trees
                val localSessions = db.getSessionsByCourse(course.id).sortedBy { it.date }
                val localStudents = db.getStudentsForCourse(course.id).sortedBy { it.email }
                val localAttendanceMap = db.getAllAttendanceForCourse(course.id)
                    .groupBy { it.studentEmail to it.sessionId }

                // 2. Fetch Existing Cloud Layout Bounds
                val response = sheetsService.spreadsheets().values()
                    .get(spreadsheetId, "'$tabName'!A1:Z1000")
                    .execute()

                val currentGrid: MutableList<MutableList<Any>> = response.getValues()?.map {
                    it.map { cell -> cell ?: "" }.toMutableList()
                }?.toMutableList() ?: mutableListOf()

                // Initialize structural headers if sheet tab is completely pristine
                if (currentGrid.isEmpty()) {
                    currentGrid.add(mutableListOf("Student Email", "Student Name"))
                }

                val headerRow = currentGrid[0]

                // 3. Coordinate Columns Alignment (Sessions)
                val sessionToColumnIdx = mutableMapOf<Long, Int>()
                localSessions.forEach { session ->
                    val formattedHeader = "${session.name} (${CourseUtilities.fromMillisToLocalDate(session.date).format(dateFormat)})"
                    var matchIndex = headerRow.indexOfFirst { it.toString().equals(formattedHeader, ignoreCase = true) }
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
                        val newRow = mutableListOf<Any>(student.email, student.name)
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
                val updateBody = com.google.api.services.sheets.v4.model.ValueRange().setValues(currentGrid as List<List<Any>>?)
                sheetsService.spreadsheets().values()
                    .update(spreadsheetId, "'$tabName'!A1", updateBody)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, "Attendance synced successfully to Cloud!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("CourseCloudActions", "Cloud spreadsheet matching sync execution crash", e)
                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, "Cloud sync failed. Check connectivity configurations.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
