package com.example.presensor.cloud

import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.ImportStudentController
import com.example.presensor.controllers.dialogs.DialogFactory

class DashboardCloudActions(
    private val activity: MainActivity,
    private val refreshDashboard: () -> Unit
) {

    fun triggerStudentImportCloudPicker() {
        activity.toggleLoadingOverlay(true)

        val action = {
            activity.cloudSyncController.fetchAvailableSpreadsheets { spreadsheets ->
                activity.toggleLoadingOverlay(false)

                if (spreadsheets.isEmpty()) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_cloud_sheets_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@fetchAvailableSpreadsheets
                }

                // 1. Show the searchable Spreadsheet file dialog
                activity.cloudSyncController.showCloudFileDialog(
                    title = activity.getString(R.string.dialog_cloud_import_sheets_title),
                    subtitle = activity.getString(R.string.dialog_cloud_import_sheets_subtitle),
                    driveItems = spreadsheets,
                    getName = { it.name }
                ) { selectedSpreadsheet ->

                    activity.toggleLoadingOverlay(true)

                    // 2. Query for internal workbook worksheet tabs
                    activity.cloudSyncController.fetchSpreadsheetTabs(selectedSpreadsheet.id) { tabs ->
                        activity.toggleLoadingOverlay(false)

                        if (tabs.isEmpty()) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.toast_cloud_sheet_tabs_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@fetchSpreadsheetTabs
                        }

                        // 3. REUSED: Show the exact same searchable file dialog structure for tabs!
                        activity.cloudSyncController.showCloudFileDialog(
                            title = activity.getString(R.string.dialog_cloud_select_tab_title),
                            subtitle = activity.getString(R.string.dialog_cloud_select_tab_subtitle),
                            driveItems = tabs,
                            getName = { it } // Since items are already Strings, just return itself
                        ) { selectedTab ->

                            // 4. Trigger background sheet data collection ingestion
                            activity.toggleLoadingOverlay(true)
                            activity.importStudentController.importFromCloud(
                                activity.cloudSyncController.getSheetsService(),
                                selectedSpreadsheet.id,
                                selectedTab
                            )
                        }
                    }
                }
            }
        }

        activity.setPendingAction(action)
        activity.cloudSyncController.runWithCloudAuthentication(
            activity.cloudSignInLauncher,
            action
        )
    }

    fun triggerDatabaseExportCloudPicker() {
        val layoutInflater = activity.layoutInflater
        val dialogView = layoutInflater.inflate(R.layout.dialog_cloud_export, null)
        val suffixInput = dialogView.findViewById<EditText>(R.id.editExportSuffix)
        dialogView.findViewById<TextView>(R.id.textExportPrefixPreview).text =
            activity.getString(R.string.dialog_cloud_export_prefix_preview) + " " + activity.getString(
                R.string.dialog_cloud_backup_prefix
            )

        with(DialogFactory) {
            // 2. Build the interactive container before authorizing to match workflow steps
            AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_cloud_export_title)
                .setView(dialogView)
                .setPositiveButton(R.string.action_export) { _, _ ->
                    val inputSuffix = suffixInput.text.toString()

                    // 3. Lock layout screens smoothly to prevent interaction flickering rules
                    activity.toggleLoadingOverlay(true)

                    val action = {
                        // Run the synchronized cloud task passing the customized suffix label
                        activity.cloudSyncController.uploadBackupToDrive(inputSuffix) { isLoading ->
                            activity.toggleLoadingOverlay(isLoading)
                        }
                    }

                    activity.setPendingAction(action)
                    activity.cloudSyncController.runWithCloudAuthentication(
                        activity.cloudSignInLauncher,
                        action
                    )
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showWithSmartNfcReading()
        }
    }

    fun triggerDatabaseImportCloudPicker() {
        activity.toggleLoadingOverlay(true)
        triggerCustomImportFlow()
    }

    fun triggerCustomImportFlow() {
        activity.toggleLoadingOverlay(true)

        val action = {
            activity.cloudSyncController.fetchAvailableBackups { files ->
                activity.toggleLoadingOverlay(false)

                if (files.isEmpty()) {
                    with(DialogFactory) {
                        AlertDialog.Builder(activity)
                            .setTitle("No Backups Found")
                            .setMessage("No valid database backups were discovered on your Google Drive account.")
                            .setPositiveButton("OK", null)
                            .showWithSmartNfcReading()
                    }
                    return@fetchAvailableBackups
                }

                // Call the unified dialog framework
                activity.cloudSyncController.showCloudFileDialog(
                    title = activity.getString(R.string.dialog_cloud_import_title),
                    subtitle = activity.getString(R.string.dialog_cloud_import_subtitle),
                    driveItems = files,
                    getName = { it.name }
                ) { selectedFile ->

                    // Handle download sync restoration
                    activity.cloudSyncController.downloadAndRestoreBackup(
                        selectedFile.id,
                        onLoadingToggle = { isLoading -> activity.toggleLoadingOverlay(isLoading) },
                        onComplete = { success -> if (success) refreshDashboard() }
                    )
                }
            }
        }

        activity.setPendingAction(action)
        activity.cloudSyncController.runWithCloudAuthentication(
            activity.cloudSignInLauncher,
            action
        )
    }
}
