package com.example.presensor.controllers

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.communication.core.AppMode
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.SecureStoreManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class ReaderManagementController(
    private val activity: MainActivity,
    private val db: AppDatabase,
    private val secureStoreManager: SecureStoreManager,
    private val interactionProvider: ReaderInteractionProvider,
    private val scope: CoroutineScope
) {
    private var backlogAdapter: BacklogAdapter? = null
    private var metricsJob: Job? = null
    private var inventoryJob: Job? = null
    private var dashboardUIJob: Job? = null
    
    private var backlogItems = mutableListOf<BacklogItem>()
    private val receivedInSync = mutableSetOf<BacklogItem>()
    private var isSyncInProgress = false
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var currentDashboardAddress: String? = null

    private var txtDeviceName: TextView? = null
    private var txtDeviceMac: TextView? = null
    private var txtStatFiles: TextView? = null
    private var txtStatTime: TextView? = null
    private var txtStatBattery: TextView? = null

    companion object {
        private const val TAG = "ReaderManagementCtrl"
    }

    private fun logAndToast(msgResId: Int, isShort: Boolean = true) {
        interactionProvider.showToast(msgResId, isShort)
    }

    fun setupReaderManagementView(rootView: View, address: String? = null) {
        currentDashboardAddress = address ?: activity.readerOrchestrator?.connectedDeviceAddress
        txtDeviceName = rootView.findViewById(R.id.txtDeviceName)
        txtDeviceMac = rootView.findViewById(R.id.txtDeviceMac)
        txtStatFiles = rootView.findViewById(R.id.txtStatFilesCount)
        txtStatTime = rootView.findViewById(R.id.txtStatDeviceTime)
        txtStatBattery = rootView.findViewById(R.id.txtStatBattery)
        val viewAccent = rootView.findViewById<View>(R.id.viewDeviceDetailAccent)

        swipeRefreshLayout = rootView as? SwipeRefreshLayout ?: rootView.findViewById(R.id.swipeRefreshDeviceManager)

        val rvBacklog = rootView.findViewById<RecyclerView>(R.id.rvDeviceBacklog)
        backlogAdapter = BacklogAdapter { item -> handleBacklogItemLongClick(item) }
        rvBacklog.layoutManager = LinearLayoutManager(activity)
        rvBacklog.adapter = backlogAdapter

        rootView.findViewById<View>(R.id.btnEditDevice).setOnClickListener {
            val addr = activity.readerOrchestrator?.connectedDeviceAddress
            if (addr != null) {
                interactionProvider.showEditReaderDialog(secureStoreManager.deviceName) { newName, newPass ->
                    activity.readerOrchestrator?.updateReaderConfig(newName, newPass)
                    secureStoreManager.clearCredentialsFor(secureStoreManager.deviceName)
                    secureStoreManager.saveReaderCredentials(newName, newPass)
                    secureStoreManager.deviceName = newName
                    logAndToast(R.string.toast_config_update_sent)
                    rebootAndReconnect(newName, newPass, addr)
                    updateHeader()
                }
            }
        }

        rootView.findViewById<View>(R.id.btnSyncTime).setOnClickListener {
            if (activity.readerOrchestrator?.isAuthenticated?.value == true) {
                activity.readerOrchestrator?.syncTime()
                logAndToast(R.string.action_sync_time)
            }
        }

        rootView.findViewById<View>(R.id.btnForget).setOnClickListener {
            val name = secureStoreManager.deviceName
            activity.readerOrchestrator?.disconnect(disableAutoReconnect = true)
            secureStoreManager.clearCredentialsFor(name)
            resetDashboardUI()
        }

        swipeRefreshLayout?.setOnRefreshListener {
            if (activity.readerOrchestrator?.isAuthenticated?.value == true) {
                refreshManagementData("Manual Pull-to-Refresh")
            } else {
                swipeRefreshLayout?.isRefreshing = false
            }
        }

        updateHeader()

        dashboardUIJob?.cancel()
        dashboardUIJob = scope.launch(Dispatchers.Main) {
            kotlinx.coroutines.flow.combine(
                activity.readerOrchestrator!!.connectionState,
                activity.readerOrchestrator!!.isAuthenticated
            ) { state, auth -> state to auth }
                .distinctUntilChanged()
                .collect { (state, auth) ->
                    val isReady = state == ReaderOrchestrator.ConnectionState.CONNECTED && auth
                    val isConnecting = state == ReaderOrchestrator.ConnectionState.CONNECTING ||
                            (state == ReaderOrchestrator.ConnectionState.CONNECTED && !auth)

                    val accentColor = when {
                        isReady -> "#4CAF50".toColorInt()
                        isConnecting -> "#FF9800".toColorInt()
                        else -> Color.TRANSPARENT
                    }
                    viewAccent?.setBackgroundColor(accentColor)

                    val btnDisconnect = rootView.findViewById<LinearLayout>(R.id.btnDisconnect)
                    val imgDisconnect = btnDisconnect.getChildAt(0) as? ImageView
                    val txtDisconnect = btnDisconnect.getChildAt(1) as? TextView

                    if (isReady || isConnecting) {
                        txtDisconnect?.text = activity.getString(R.string.action_disconnect)
                        imgDisconnect?.setImageResource(R.drawable.ic_reader_disconnected)
                        btnDisconnect.setOnClickListener {
                            activity.readerOrchestrator?.disconnect(disableAutoReconnect = true)
                            resetDashboardUI()
                        }
                    } else {
                        txtDisconnect?.text = activity.getString(R.string.action_connect)
                        imgDisconnect?.setImageResource(R.drawable.ic_reader_connected)
                        btnDisconnect.setOnClickListener {
                            val targetAddr = currentDashboardAddress ?: activity.readerOrchestrator?.connectedDeviceAddress ?: ""
                            // We can't call handleReaderSelection directly here easily if it's in DiscoveryCtrl.
                            // But we can call Orchestrator.startConnecting if we have credentials.
                            val name = secureStoreManager.deviceName
                            val pass = secureStoreManager.getAuthPasswordFor(name)
                            if (pass != null) activity.readerOrchestrator?.startConnecting(name, pass, targetAddr, true)
                        }
                    }

                    if (isReady) {
                        activity.readerOrchestrator?.setAppMode(AppMode.MANAGEMENT, "Dashboard Reactivation")
                        swipeRefreshLayout?.isRefreshing = true
                        refreshManagementData("State Transition")
                    } else if (!isConnecting) {
                        txtStatFiles?.text = "--"
                        txtStatTime?.text = "--"
                        txtStatBattery?.text = "--%"
                    }
                }
        }

        metricsJob?.cancel()
        metricsJob = scope.launch(Dispatchers.Main) {
            activity.readerOrchestrator?.metricsFlow?.collect { (epoch, battery) ->
                txtStatTime?.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epoch * 1000L))
                txtStatBattery?.text = "$battery%"
            }
        }

        inventoryJob?.cancel()
        inventoryJob = scope.launch(Dispatchers.Main) {
            activity.readerOrchestrator?.inventoryFlow?.collect { (rawTagId, timestamp) ->
                if (rawTagId == "SYNC_DONE") {
                    if (isSyncInProgress) {
                        backlogItems.removeAll { it !in receivedInSync }
                        backlogAdapter?.submitList(backlogItems.toList())
                        isSyncInProgress = false
                    }
                    swipeRefreshLayout?.isRefreshing = false
                    txtStatFiles?.text = backlogItems.size.toString()
                } else if (rawTagId == "DEL_OK") {
                    logAndToast(R.string.toast_backlog_deleted_success)
                    refreshManagementData("Post-Deletion Refresh")
                } else {
                    val tagId = rawTagId.chunked(2).joinToString(":")
                    val studentName = withContext(Dispatchers.IO) {
                        db.getStudentByRfid(tagId)?.name ?: activity.getString(R.string.label_unknown_student)
                    }
                    val item = BacklogItem(tagId, studentName, timestamp)
                    if (isSyncInProgress) receivedInSync.add(item)
                    if (!backlogItems.contains(item)) {
                        backlogItems.add(item)
                        backlogItems.sortByDescending { it.timestamp }
                        backlogAdapter?.submitList(backlogItems.toList())
                    }
                    txtStatFiles?.text = backlogItems.size.toString()
                }
            }
        }
    }

    private fun refreshManagementData(caller: String) {
        receivedInSync.clear()
        isSyncInProgress = true
        scope.launch {
            delay(500)
            activity.readerOrchestrator?.requestInventory()
            activity.readerOrchestrator?.requestStatus()
            delay(5000)
            withContext(Dispatchers.Main) {
                if (swipeRefreshLayout?.isRefreshing == true) {
                    swipeRefreshLayout?.isRefreshing = false
                    isSyncInProgress = false
                    logAndToast(R.string.toast_device_communication_time_out)
                }
            }
        }
    }

    private fun resetDashboardUI() {
        txtStatFiles?.text = "--"
        txtStatTime?.text = "--"
        txtStatBattery?.text = "--%"
        backlogItems.clear()
        backlogAdapter?.submitList(emptyList())
        swipeRefreshLayout?.isRefreshing = false
        activity.readerOrchestrator?.setAppMode(AppMode.IDLE, "Dashboard Teardown")
    }

    private fun updateHeader() {
        val manager = activity.readerOrchestrator
        txtDeviceName?.text = secureStoreManager.deviceName
        val mac = manager?.connectedDeviceAddress ?: "XX:XX:XX:XX:XX:XX"
        txtDeviceMac?.text = activity.getString(R.string.label_device_mac, mac)
        val activeDevice = manager?.discoveredDevices?.value?.find { it.address == mac }
        activeDevice?.let { device ->
            device.batteryLevel?.let { txtStatBattery?.text = "$it%" }
            device.deviceEpoch?.let { epoch ->
                txtStatTime?.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epoch * 1000L))
            }
        }
        txtStatFiles?.text = backlogItems.size.toString()
    }

    fun teardownView() {
        activity.readerOrchestrator?.setAppMode(AppMode.IDLE, "Management Dashboard Exit")
        metricsJob?.cancel()
        inventoryJob?.cancel()
        dashboardUIJob?.cancel()
        currentDashboardAddress = null
        backlogItems.clear()
        receivedInSync.clear()
        isSyncInProgress = false
        backlogAdapter = null
        swipeRefreshLayout = null
    }

    internal fun handleBacklogItemLongClick(item: BacklogItem) {
        interactionProvider.showDestructiveDeleteDialog(
            title = activity.getString(R.string.delete_action_text),
            message = activity.getString(R.string.dialog_delete_session_message, item.studentName),
            onConfirmed = {
                activity.readerOrchestrator?.deleteBacklogItem(item.tagId, item.timestamp)
            }
        )
    }

    private fun rebootAndReconnect(newName: String, newPass: String, address: String) {
        swipeRefreshLayout?.isRefreshing = true
        activity.readerOrchestrator?.rebootReader(newName, newPass, address)
    }

    internal data class BacklogItem(val tagId: String, val studentName: String, val timestamp: Long)

    private class BacklogAdapter(private val onItemLongClicked: (BacklogItem) -> Unit) : RecyclerView.Adapter<BacklogAdapter.ViewHolder>() {
        private var items = mutableListOf<BacklogItem>()
        fun submitList(newItems: List<BacklogItem>) {
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos].tagId == newItems[newPos].tagId && items[oldPos].timestamp == newItems[newPos].timestamp
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = items[oldPos] == newItems[newPos]
            })
            items.clear()
            items.addAll(newItems)
            diffResult.dispatchUpdatesTo(this)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_stat_card, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtName.text = item.studentName
            holder.txtTag.text = item.tagId
            val date = Date(item.timestamp * 1000L)
            holder.txtTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
            val df = android.text.format.DateFormat.getDateFormat(holder.itemView.context)
            holder.txtDate.text = df.format(date)
            holder.txtDate.visibility = View.VISIBLE
            holder.itemView.setOnLongClickListener { onItemLongClicked(item); true }
        }
        override fun getItemCount() = items.size
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtPrimaryLabel)
            val txtTag: TextView = v.findViewById(R.id.txtSecondaryLabel)
            val txtTime: TextView = v.findViewById(R.id.txtStatValue)
            val txtDate: TextView = v.findViewById(R.id.txtStatValueSecondary)
        }
    }
}
