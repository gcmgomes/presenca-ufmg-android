package com.example.presensor.controllers

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Student
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.adapters.ImportBacklogAdapter
import com.example.presensor.tools.providers.ToastProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.takeWhile

class ImportBacklogController(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val orchestrator: ReaderOrchestrator?,
    private val toastProvider: ToastProvider,
    private val toggleSpinner: (Boolean) -> Unit,
    private val registerAttendance: (Student?, Long) -> Unit,
    private val refreshAttendanceList: () -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var activeDialog: BottomSheetDialog? = null

    fun startImportFlow() {
        if (orchestrator == null || orchestrator.isAuthenticated.value != true) {
            toggleSpinner(false)
            toastProvider.showToast("No authenticated device found.")
            return
        }

        // Show the dialog with a progress bar immediately
        showPreviewDialog(isInitialFetch = true)
    }

    private suspend fun fetchBacklogItems(adapter: ImportBacklogAdapter, txtCount: TextView) {
        val orch = orchestrator ?: return
        
        orch.requestInventory()

        try {
            withTimeout(10000) {
                orch.inventoryFlow
                    .takeWhile { (tagId, _) -> tagId != "SYNC_DONE" }
                    .collect { (tagId, timestamp) ->
                        if (tagId != "DEL_OK" && tagId != "DEL_ERR") {
                            val student = withContext(ioDispatcher) {
                                db.getStudentByRfid(tagId.chunked(2).joinToString(":"))
                            }
                            val newItem = BacklogItem(tagId, student, timestamp)
                            withContext(mainDispatcher) {
                                adapter.addItem(newItem)
                                txtCount.text = activity.getString(
                                    R.string.dialog_import_backlog_hint,
                                    adapter.itemCount
                                )
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.w("ImportBacklog", "Fetch interrupted or timed out: ${e.message}")
        }
    }

    private fun showPreviewDialog(isInitialFetch: Boolean) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_list_preview, null)
        val rvPreview = dialogView.findViewById<RecyclerView>(R.id.rvPreviewList)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmAction)
        val txtCount = dialogView.findViewById<TextView>(R.id.txtPreviewHint)
        val txtTitle = dialogView.findViewById<TextView>(R.id.txtPreviewTitle)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.pbPreviewLoading)

        txtTitle.text = activity.getString(R.string.dialog_import_backlog_title)
        btnConfirm.text = activity.getString(R.string.dialog_import_backlog_button_text)

        val adapter = ImportBacklogAdapter()
        rvPreview.layoutManager = LinearLayoutManager(activity)
        rvPreview.adapter = adapter

        if (isInitialFetch) {
            progressBar.visibility = View.VISIBLE
            btnConfirm.isEnabled = false

            scope.launch(mainDispatcher) {
                fetchBacklogItems(adapter, txtCount)
                toggleSpinner(false)
                progressBar.visibility = View.GONE
                btnConfirm.isEnabled = true

                if (adapter.itemCount == 0) {
                    toastProvider.showToast(activity.getString(R.string.inventory_empty_message))
                }
            }
        }

        val dialog = BottomSheetDialog(activity)
        activeDialog = dialog
        dialog.setContentView(dialogView)

        dialog.setOnDismissListener {
            activeDialog = null
        }

        btnConfirm.setOnClickListener {
            val selected = adapter.getSelectedItems()
            if (selected.isNotEmpty()) {
                btnConfirm.isEnabled = false
                executeSequentialImport(selected, adapter, dialog)
            } else {
                toastProvider.showToast("Please select at least one item")
            }
        }

        with(com.example.presensor.controllers.dialogs.DialogFactory) {
            dialog.showWithSmartNfcReading()
        }
    }

    fun dismissActiveDialog() {
        activeDialog?.dismiss()
        activeDialog = null
    }

    internal fun executeSequentialImport(
        selected: List<BacklogItem>,
        adapter: ImportBacklogAdapter,
        dialog: BottomSheetDialog
    ) {
        scope.launch(mainDispatcher) {
            var importedCount = 0
            val eventChannel = Channel<String>(Channel.UNLIMITED)

            val collectionJob = scope.launch {
                orchestrator?.inventoryFlow?.collect { (tagId, _) ->
                    if (tagId == "DEL_OK" || tagId == "DEL_ERR") {
                        eventChannel.send(tagId)
                    }
                }
            }

            try {
                selected.forEach { item ->
                    orchestrator?.deleteBacklogItem(item.tagId, item.timestamp)

                    val result = withTimeoutOrNull(5000) {
                        eventChannel.receive()
                    }

                    if (result == "DEL_OK") {
                        registerAttendance(item.student, item.timestamp * 1000L)
                        importedCount++
                        adapter.removeItem(item)
                    } else {
                        Log.e("ImportBacklog", "Failed to delete item ${item.tagId}")
                    }
                }
            } finally {
                collectionJob.cancel()
                eventChannel.close()
                dialog.dismiss()
                refreshAttendanceList()
                if (importedCount > 0) {
                    toastProvider.showToast(
                        activity.getString(
                            R.string.toast_imported_sessions,
                            importedCount
                        )
                    )
                }
            }
        }
    }
}
