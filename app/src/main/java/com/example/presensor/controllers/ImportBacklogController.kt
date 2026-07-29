package com.example.presensor.controllers

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Student
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

    internal data class BacklogUIItem(
        val tagId: String,
        val student: Student?,
        val timestamp: Long
    )

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
                            val newItem = BacklogUIItem(tagId, student, timestamp)
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
        selected: List<BacklogUIItem>,
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

    internal class ImportBacklogAdapter :
        RecyclerView.Adapter<ImportBacklogAdapter.ViewHolder>() {

        private val items = mutableListOf<BacklogUIItem>()
        private val selectedKeys = mutableSetOf<String>()

        fun addItem(item: BacklogUIItem) {
            // Always insert at the top for descending order
            items.add(0, item)
            // Default new items to selected
            selectedKeys.add(item.tagId + item.timestamp)
            notifyItemInserted(0)
        }

        fun removeItem(item: BacklogUIItem) {
            val index = items.indexOf(item)
            if (index != -1) {
                items.removeAt(index)
                selectedKeys.remove(item.tagId + item.timestamp)
                notifyItemRemoved(index)
            }
        }

        fun getSelectedItems(): List<BacklogUIItem> {
            return items.filter { selectedKeys.contains(it.tagId + it.timestamp) }
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardRoot: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.cardStatRoot)
            val nameText: TextView = view.findViewById(R.id.txtPrimaryLabel)
            val rfidText: TextView = view.findViewById(R.id.txtSecondaryLabel)
            val timeText: TextView = view.findViewById(R.id.txtLegacyStatValue)
            val dateText: TextView = view.findViewById(R.id.txtLegacyStatValueSecondary)
            val selectionAccent: View = view.findViewById(R.id.viewConnectionAccent)
            val layoutSignalStack: View = view.findViewById(R.id.layoutSignalStack)
            val layoutBatteryStack: View = view.findViewById(R.id.layoutBatteryStack)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_stat_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.nameText.text = item.student?.name ?: "Unknown Student"
            holder.rfidText.text = item.tagId.chunked(2).joinToString(":")
            
            // Hide technical stacks
            holder.layoutSignalStack.visibility = View.GONE
            holder.layoutBatteryStack.visibility = View.GONE

            val date = java.util.Date(item.timestamp * 1000L)
            val timeFormat = java.time.format.DateTimeFormatter.ofPattern(
                "HH:mm:ss",
                java.util.Locale.getDefault()
            )
            holder.timeText.text = timeFormat.format(
                java.time.Instant.ofEpochSecond(item.timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
            )

            val df = android.text.format.DateFormat.getDateFormat(holder.itemView.context)
            holder.dateText.text = df.format(date)
            holder.dateText.visibility = View.VISIBLE

            updateUIState(holder, selectedKeys.contains(item.tagId + item.timestamp))

            holder.itemView.setOnClickListener {
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val clickedItem = items[currentPos]
                    val key = clickedItem.tagId + clickedItem.timestamp
                    if (selectedKeys.contains(key)) {
                        selectedKeys.remove(key)
                    } else {
                        selectedKeys.add(key)
                    }
                    updateUIState(holder, selectedKeys.contains(key))
                }
            }
        }

        private fun updateUIState(holder: ViewHolder, isSelected: Boolean) {
            holder.selectionAccent.setBackgroundColor(
                if (isSelected) "#4CAF50".toColorInt() else Color.TRANSPARENT
            )

            val alpha = if (isSelected) 1.0f else 0.5f
            holder.cardRoot.alpha = alpha
            holder.nameText.alpha = alpha
            holder.rfidText.alpha = alpha
            holder.timeText.alpha = alpha
            holder.dateText.alpha = alpha
            holder.selectionAccent.alpha = alpha
        }
    }
}
