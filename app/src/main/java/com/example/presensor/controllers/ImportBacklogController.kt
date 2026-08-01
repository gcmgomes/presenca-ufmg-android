package com.example.presensor.controllers

import android.util.Log
import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Student
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.providers.ReaderInteractionProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.takeWhile

class ImportBacklogController(
    private val interactionProvider: ReaderInteractionProvider,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val orchestrator: ReaderOrchestrator?,
    private val toggleSpinner: (Boolean) -> Unit,
    private val registerAttendance: (Student?, Long, Boolean) -> Unit,
    private val refreshAttendanceList: () -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun startImportFlow() {
        if (orchestrator == null || orchestrator.isAuthenticated.value != true) {
            toggleSpinner(false)
            interactionProvider.showToast("No authenticated device found.")
            return
        }

        // Show the dialog with a progress bar immediately
        interactionProvider.showBacklogImportPreview(
            onConfirm = { selected ->
                if (selected.isNotEmpty()) {
                    interactionProvider.toggleBacklogImportLoading(true)
                    executeSequentialImport(selected)
                } else {
                    interactionProvider.showToast("Please select at least one item")
                }
            },
            onDismiss = { /* Clean up if needed */ }
        )

        // Start fetching items
        scope.launch(mainDispatcher) {
            interactionProvider.toggleBacklogImportLoading(true)
            fetchBacklogItems()
            toggleSpinner(false)
            interactionProvider.toggleBacklogImportLoading(false)

            if (interactionProvider.getBacklogItemCount() == 0) {
                interactionProvider.showToast(R.string.inventory_empty_message)
            }
        }
    }

    private suspend fun fetchBacklogItems() {
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
                                interactionProvider.addBacklogItem(newItem)
                                interactionProvider.updateBacklogCount(interactionProvider.getBacklogItemCount())
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.w("ImportBacklog", "Fetch interrupted or timed out: ${e.message}")
        }
    }

    fun dismissActiveDialog() {
        interactionProvider.dismissActiveDialog()
    }

    internal fun executeSequentialImport(
        selected: List<BacklogItem>
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
                        registerAttendance(item.student, item.timestamp * 1000L, true)
                        importedCount++
                        interactionProvider.removeBacklogItem(item)
                    } else {
                        Log.e("ImportBacklog", "Failed to delete item ${item.tagId}")
                    }
                }
            } finally {
                collectionJob.cancel()
                eventChannel.close()
                interactionProvider.dismissActiveDialog()
                refreshAttendanceList()
                if (importedCount > 0) {
                    interactionProvider.showToast(
                        interactionProvider.getString(
                            R.string.toast_imported_sessions,
                            importedCount
                        )
                    )
                }
            }
        }
    }
}
