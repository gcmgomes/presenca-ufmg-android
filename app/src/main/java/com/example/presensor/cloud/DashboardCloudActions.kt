package com.example.presensor.cloud

import android.util.Log
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.presensor.R
import com.example.presensor.controllers.CloudSyncController
import com.example.presensor.controllers.ImportStudentController
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.providers.DashboardInteractionProvider

class DashboardCloudActions(
    private val uiProvider: DashboardInteractionProvider,
    private val cloudSyncController: CloudSyncController,
    private val importStudentController: ImportStudentController,
    private val runWithCloudAuthentication: (() -> Unit) -> Unit,
    private val refreshDashboard: () -> Unit,
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) {

    fun triggerStudentImportCloudPicker() {
        uiProvider.toggleLoading(true)

        val action = {
            cloudSyncController.fetchAvailableSpreadsheets { spreadsheets ->
                uiProvider.toggleLoading(false)

                if (spreadsheets.isEmpty()) {
                    uiProvider.showToast(R.string.toast_cloud_sheets_empty)
                    return@fetchAvailableSpreadsheets
                }

                // 1. Show the searchable Spreadsheet file dialog
                cloudSyncController.showCloudFileDialog(
                    title = uiProvider.getString(R.string.dialog_cloud_import_sheets_title),
                    subtitle = uiProvider.getString(R.string.dialog_cloud_import_sheets_subtitle),
                    driveItems = spreadsheets,
                    getName = { it.name }
                ) { selectedSpreadsheet ->

                    uiProvider.toggleLoading(true)

                    // 2. Query for internal workbook worksheet tabs
                    cloudSyncController.fetchSpreadsheetTabs(selectedSpreadsheet.id) { tabs ->
                        uiProvider.toggleLoading(false)

                        if (tabs.isEmpty()) {
                            uiProvider.showToast(R.string.toast_cloud_sheet_tabs_failed)
                            return@fetchSpreadsheetTabs
                        }

                        // 3. REUSED: Show the exact same searchable file dialog structure for tabs!
                        cloudSyncController.showCloudFileDialog(
                            title = uiProvider.getString(R.string.dialog_cloud_select_tab_title),
                            subtitle = uiProvider.getString(R.string.dialog_cloud_select_tab_subtitle),
                            driveItems = tabs,
                            getName = { it } // Since items are already Strings, just return itself
                        ) { selectedTab ->

                            // 4. Trigger background sheet data collection ingestion
                            uiProvider.toggleLoading(true)
                            importStudentController.importFromCloud(
                                cloudSyncController.getSheetsService(),
                                selectedSpreadsheet.id,
                                selectedTab
                            )
                        }
                    }
                }
            }
        }

        runWithCloudAuthentication(action)
    }

    fun triggerDatabaseExportCloudPicker() {
        val layoutInflater = uiProvider.getLayoutInflater()
        val dialogView = layoutInflater.inflate(R.layout.dialog_cloud_export, null)
        val suffixInput = dialogView.findViewById<EditText>(R.id.editExportSuffix)
        dialogView.findViewById<TextView>(R.id.textExportPrefixPreview).text =
            uiProvider.getString(R.string.dialog_cloud_export_prefix_preview) + " " + uiProvider.getString(
                R.string.dialog_cloud_backup_prefix
            )

        with(DialogFactory) {
            // 2. Build the interactive container before authorizing to match workflow steps
            com.google.android.material.dialog.MaterialAlertDialogBuilder(uiProvider.getContext())
                .setTitle(R.string.dialog_cloud_export_title)
                .setView(dialogView)
                .setPositiveButton(R.string.action_export) { _, _ ->
                    val inputSuffix = suffixInput.text.toString()

                    // 3. Lock layout screens smoothly to prevent interaction flickering rules
                    uiProvider.toggleLoading(true)

                    val action = {
                        // Run the synchronized cloud task passing the customized suffix label
                        cloudSyncController.uploadBackupToDrive(inputSuffix)
                    }

                    runWithCloudAuthentication(action)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showWithSmartNfcReading()
        }
    }

    fun triggerDatabaseImportCloudPicker() {
        uiProvider.toggleLoading(true)
        triggerCustomImportFlow()
    }

    fun triggerCustomImportFlow() {
        uiProvider.toggleLoading(true)

        val action = {
            cloudSyncController.fetchAvailableBackups { files ->
                uiProvider.toggleLoading(false)

                if (files.isEmpty()) {
                    with(DialogFactory) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(uiProvider.getContext())
                            .setTitle("No Backups Found")
                            .setMessage("No valid database backups were discovered on your Google Drive account.")
                            .setPositiveButton("OK", null)
                            .showWithSmartNfcReading()
                    }
                    return@fetchAvailableBackups
                }

                // Call the unified dialog framework
                cloudSyncController.showCloudFileDialog(
                    title = uiProvider.getString(R.string.dialog_cloud_import_title),
                    subtitle = uiProvider.getString(R.string.dialog_cloud_import_subtitle),
                    driveItems = files,
                    getName = { it.name }
                ) { selectedFile ->

                    // Handle download sync restoration
                    cloudSyncController.downloadAndRestoreBackup(
                        selectedFile.id,
                        onComplete = { success -> if (success) refreshDashboard() }
                    )
                }
            }
        }

        runWithCloudAuthentication(action)
    }
}
