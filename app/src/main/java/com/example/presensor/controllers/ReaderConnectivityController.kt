package com.example.presensor.controllers

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.graphics.Color
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.ble.ReaderEvent
import com.example.presensor.ble.ReaderManager
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.SecureStoreManager
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class ReaderConnectivityController(
    private val activity: MainActivity,
    private val db: AppDatabase,
    private val secureStoreManager: SecureStoreManager,
    private val scope: CoroutineScope
) {
    private val discoveredDevices = mutableMapOf<String, Pair<ScanResult, Long>>()
    private var listAdapter: DeviceListAdapter? = null
    private var backlogAdapter: BacklogAdapter? = null

    private var statusJob: Job? = null
    private var refreshJob: Job? = null
    private var metricsJob: Job? = null
    private var inventoryJob: Job? = null
    private var eventJob: Job? = null

    private var connectingAddress: String? = null
    private var backlogItems = mutableListOf<BacklogItem>()
    private val receivedInSync = mutableSetOf<BacklogItem>()
    private var isSyncInProgress = false
    private var swipeRefreshLayout: SwipeRefreshLayout? = null

    // Dashboard Views
    private var txtDeviceName: TextView? = null
    private var txtDeviceMac: TextView? = null
    private var txtStatFiles: TextView? = null
    private var txtStatTime: TextView? = null
    private var txtStatBattery: TextView? = null

    companion object {
        private const val REFRESH_INTERVAL_MS = 10000L
        private const val STALE_THRESHOLD_MS = 20000L
        private const val TAG = "ReaderConnController"
    }

    private fun logAndToast(msgResId: Int, isShort: Boolean = true) {
        val text = activity.getString(msgResId)
        android.util.Log.i(TAG, "[User Feedback] $text")
        Toast.makeText(activity, text, if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
            .show()
    }

    // --- Reader List (Scanning) ---

    fun setupReaderList(rootView: View) {
        val switchUseReader = rootView.findViewById<SwitchMaterial>(R.id.switchUseReader)
        val recyclerView = rootView.findViewById<RecyclerView>(R.id.readerRecyclerView)
        val listRefresh = rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshReader)

        switchUseReader.isChecked = secureStoreManager.isReaderEnabled
        listRefresh?.isEnabled = secureStoreManager.isReaderEnabled

        listAdapter = DeviceListAdapter(
            onDeviceSelected = { name, address -> handleReaderSelection(name, address) },
            onDeviceLongClicked = { name, address -> handleReaderLongClick(name, address) }
        )
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = listAdapter

        updateDeviceList()

        switchUseReader.setOnCheckedChangeListener { _, isChecked ->
            secureStoreManager.isReaderEnabled = isChecked
            listRefresh?.isEnabled = isChecked
            if (isChecked) {
                startDiscovery()
                startRefreshLoop()
            } else {
                teardownDiscovery(fullDisconnect = true)
            }
        }

        listRefresh?.setOnRefreshListener {
            startDiscovery()
            scope.launch {
                delay(1000)
                listRefresh.isRefreshing = false
            }
        }

        statusJob?.cancel()
        statusJob = scope.launch(Dispatchers.Main) {
            activity.readerManager?.connectionState?.collectLatest { state ->
                if (state == ReaderManager.ConnectionState.CONNECTED || state == ReaderManager.ConnectionState.DISCONNECTED) {
                    connectingAddress = null
                }
                updateDeviceList()
            }
        }

        eventJob?.cancel()
        eventJob = scope.launch(Dispatchers.Main) {
            activity.readerManager?.eventFlow?.collect { event ->
                handleReaderEvent(event)
            }
        }

        if (secureStoreManager.isReaderEnabled) {
            startDiscovery()
            startRefreshLoop()
        }
    }

    fun teardownDiscovery(fullDisconnect: Boolean = false) {
        activity.readerManager?.onDeviceFoundListener = null
        activity.readerManager?.isBroadDiscoveryMode = false
        if (fullDisconnect) {
            activity.readerManager?.disconnect()
        } else {
            activity.readerManager?.stopScanning()
            activity.readerManager?.setAppMode(ReaderManager.AppMode.IDLE, "Discovery Teardown")
        }
        discoveredDevices.clear()
        connectingAddress = null
        updateDeviceList()
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun startDiscovery() {
        discoveredDevices.clear()
        activity.readerManager?.isBroadDiscoveryMode = true
        activity.readerManager?.onDeviceFoundListener = { result ->
            activity.runOnUiThread {
                val address = result.device.address
                val isNew = !discoveredDevices.containsKey(address)
                discoveredDevices[address] = result to System.currentTimeMillis()
                if (isNew) updateDeviceList()
            }
        }
        activity.readerManager?.startScan()

        scope.launch {
            delay(6000)
            activity.readerManager?.stopScanning()
        }
        updateDeviceList()
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (secureStoreManager.isReaderEnabled) {
                    startDiscovery()
                    activity.readerManager?.requestRssiUpdate()
                }
                delay(REFRESH_INTERVAL_MS)
                pruneAndRefresh()
            }
        }
    }

    private fun pruneAndRefresh() {
        val now = System.currentTimeMillis()
        val iterator = discoveredDevices.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.second > STALE_THRESHOLD_MS) iterator.remove()
        }
        updateDeviceList()
    }

    private fun updateDeviceList() {
        if (!secureStoreManager.isReaderEnabled) {
            listAdapter?.submitList(emptyList(), emptyList(), emptyList())
            return
        }

        val connectedAddress = activity.readerManager?.connectedDeviceAddress
        val isAuth = activity.readerManager?.isAuthenticated ?: false
        val lastRssi = activity.readerManager?.lastConnectedRssi

        val connectedItems = mutableListOf<DeviceItem>()
        val knownItems = mutableListOf<DeviceItem>()
        val unknownItems = mutableListOf<DeviceItem>()

        if (connectedAddress != null) {
            val scanResult = discoveredDevices[connectedAddress]?.first
            val name = scanResult?.scanRecord?.deviceName ?: scanResult?.device?.name
            ?: secureStoreManager.deviceName
            val rssi = scanResult?.rssi ?: lastRssi
            connectedItems.add(
                DeviceItem(
                    name,
                    connectedAddress,
                    rssi,
                    isConnected = isAuth,
                    isConnecting = !isAuth
                )
            )
        }

        discoveredDevices.values.forEach { (result, _) ->
            val address = result.device.address
            if (address == connectedAddress) return@forEach
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "Unknown"
            val isConnecting = address == connectingAddress
            val item = DeviceItem(
                name,
                address,
                result.rssi,
                isConnected = false,
                isConnecting = isConnecting
            )
            if (secureStoreManager.hasPasswordFor(name)) knownItems.add(item) else unknownItems.add(
                item
            )
        }
        listAdapter?.submitList(connectedItems, knownItems, unknownItems)
    }

    private fun handleReaderSelection(name: String, address: String) {
        android.util.Log.d(
            TAG,
            "[Connect Flow] handleReaderSelection triggered for '$name' at $address"
        )

        // 1. If already connected AND authenticated, do nothing
        if (address == activity.readerManager?.connectedDeviceAddress && activity.readerManager?.isAuthenticated == true) {
            android.util.Log.d(TAG, "[Connect Flow] Already connected and authenticated.")
            return
        }

        // 2. Prepare for connection
        secureStoreManager.deviceName = name

        // 3. Fetch password and initiate connection
        val storedPassword = secureStoreManager.getAuthPasswordFor(name)
        if (storedPassword == null) {
            android.util.Log.d(TAG, "[Connect Flow] No password stored. Prompting user.")
            showPasswordPromptDialog(name, address)
        } else {
            android.util.Log.d(
                TAG,
                "[Connect Flow] Credentials found. Triggering ReaderManager.startConnecting()"
            )
            connectingAddress = address
            activity.readerManager?.isBroadDiscoveryMode = false
            activity.readerManager?.startConnecting(name, storedPassword)
            updateDeviceList()
            logAndToast(R.string.status_connecting)
        }
    }

    private fun handleBacklogItemLongClick(item: BacklogItem) {
        android.util.Log.d(TAG, "[Management] Long-click on backlog item: ${item.tagId}")

        com.example.presensor.controllers.dialogs.DialogFactory.showDestructiveDeleteDialog(
            context = activity,
            title = activity.getString(R.string.delete_action_text),
            message = activity.getString(R.string.dialog_delete_session_message, item.studentName),
            onConfirmed = {
                android.util.Log.i(TAG, "[Management] Deletion confirmed for tag ${item.tagId}")
                activity.readerManager?.deleteBacklogItem(item.tagId, item.timestamp)
            }
        )
    }

    private fun handleReaderLongClick(name: String, address: String) {
        android.util.Log.d(TAG, "[UI] handleReaderLongClick for '$name' at $address")
        secureStoreManager.deviceName = name
        activity.openDeviceManager()
    }

    // --- Device Management (Dashboard) ---

    fun setupReaderManagementView(rootView: View) {
        android.util.Log.i(
            TAG,
            "[Management] setupReaderManagementView START. Root ID: ${rootView.id}"
        )

        txtDeviceName = rootView.findViewById(R.id.txtDeviceName)
        txtDeviceMac = rootView.findViewById(R.id.txtDeviceMac)
        txtStatFiles = rootView.findViewById(R.id.txtStatFilesCount)
        txtStatTime = rootView.findViewById(R.id.txtStatDeviceTime)
        txtStatBattery = rootView.findViewById(R.id.txtStatBattery)

        // CRITICAL FIX: Ensure we are finding the refresh layout relative to this rootView
        swipeRefreshLayout =
            rootView as? SwipeRefreshLayout ?: rootView.findViewById(R.id.swipeRefreshDeviceManager)

        android.util.Log.i(
            TAG,
            "[Management] swipeRefreshLayout resolution: ${if (swipeRefreshLayout != null) "SUCCESS" else "FAILED"}"
        )

        val rvBacklog = rootView.findViewById<RecyclerView>(R.id.rvDeviceBacklog)
        backlogAdapter = BacklogAdapter(
            onItemLongClicked = { item -> handleBacklogItemLongClick(item) }
        )
        rvBacklog.layoutManager = LinearLayoutManager(activity)
        rvBacklog.adapter = backlogAdapter

        rootView.findViewById<View>(R.id.btnEditDevice).setOnClickListener {
            val address = activity.readerManager?.connectedDeviceAddress
            if (address != null) showEditReaderDialog(secureStoreManager.deviceName, address)
        }
        rootView.findViewById<View>(R.id.btnSyncTime).setOnClickListener {
            activity.readerManager?.syncTime()
            logAndToast(R.string.action_sync_time)
        }
        rootView.findViewById<View>(R.id.btnDisconnect).setOnClickListener {
            activity.readerManager?.disconnect(disableAutoReconnect = true)
            resetDashboardUI()
        }
        rootView.findViewById<View>(R.id.btnForget).setOnClickListener {
            val name = secureStoreManager.deviceName
            activity.readerManager?.disconnect(disableAutoReconnect = true)
            secureStoreManager.clearCredentialsFor(name)
            resetDashboardUI()
        }

        swipeRefreshLayout?.setOnRefreshListener {
            // EMERGENCY LOG: This must show up if the listener works
            android.util.Log.e(TAG, "[CRITICAL] Pull-to-refresh TRIGGERED. Entry point hit.")
            refreshManagementData("Manual Pull-to-Refresh")
        }

        updateHeader()
        activity.readerManager?.setAppMode(
            ReaderManager.AppMode.MANAGEMENT,
            "Management Dashboard Setup"
        )

        swipeRefreshLayout?.isRefreshing = true
        refreshManagementData("Automatic Dashboard Init")

        metricsJob?.cancel()
        metricsJob = scope.launch(Dispatchers.Main) {
            activity.readerManager?.metricsFlow?.collect { (epoch, battery) ->
                txtStatTime?.text =
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epoch * 1000L))
                txtStatBattery?.text = "$battery%"
            }
        }

        inventoryJob?.cancel()
        inventoryJob = scope.launch(Dispatchers.Main) {
            activity.readerManager?.inventoryFlow?.collect { (rawTagId, timestamp) ->

                if (rawTagId == "SYNC_DONE") {
                    if (isSyncInProgress) {
                        // Remove items that are no longer on the device
                        val removed = backlogItems.removeAll { it !in receivedInSync }
                        if (removed) {
                            backlogAdapter?.submitList(backlogItems.toList())
                        }
                        isSyncInProgress = false
                    }
                    swipeRefreshLayout?.isRefreshing = false
                    txtStatFiles?.text = backlogItems.size.toString()
                } else if (rawTagId == "DEL_OK") {
                    logAndToast(R.string.toast_course_deleted_success) // Reusing "deleted success" toast
                    refreshManagementData("Post-Deletion Refresh")
                } else if (rawTagId == "DEL_ERR") {
                    logAndToast(R.string.toast_cloud_sync_failed)
                } else {
                    val tagId = rawTagId.chunked(2).joinToString(":")
                    val studentName = withContext(Dispatchers.IO) {
                        db.getStudentByRfid(tagId)?.name
                            ?: activity.getString(R.string.label_unknown_student)
                    }
                    val item = BacklogItem(tagId, studentName, timestamp)
                    
                    if (isSyncInProgress) {
                        receivedInSync.add(item)
                    }

                    if (!backlogItems.contains(item)) {
                        backlogItems.add(item)
                        // Sort descending so newer items are at the top and "push" others down
                        backlogItems.sortByDescending { it.timestamp }
                        backlogAdapter?.submitList(backlogItems.toList())
                    }
                    txtStatFiles?.text = backlogItems.size.toString()
                }
            }
        }

        refreshManagementData("Automatic Dashboard Init")
    }

    private fun refreshManagementData(caller: String) {
        android.util.Log.i(
            TAG,
            "[Management] ---> refreshManagementData() entry point triggered. (Caller: $caller)"
        )
        // --- UI Refinement: Do not clear the list immediately to avoid "blink" ---
        // Instead, we mark the start of a sync and merge the items as they come.
        receivedInSync.clear()
        isSyncInProgress = true
        
        txtStatFiles?.text = backlogItems.size.toString()

        scope.launch {
            try {
                // 2. Tactical delay to allow ESP32 to process state transition
                android.util.Log.d(TAG, "[Management Flow] Step 1.5: Waiting 500ms...")
                delay(500)

                // 3. Dispatch the GET command
                android.util.Log.d(TAG, "[Management Flow] Step 2: Requesting Inventory (GET)")
                activity.readerManager?.requestInventory()

                // 4. Safety timeout for refresh UI
                delay(5000)
                withContext(Dispatchers.Main) {
                    if (swipeRefreshLayout?.isRefreshing == true) {
                        swipeRefreshLayout?.isRefreshing = false
                        isSyncInProgress = false
                        android.util.Log.w(TAG, "[Management Flow] Inventory fetch timed out.")
                        logAndToast(R.string.toast_device_communication_time_out)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    TAG,
                    "[Management Flow ERROR] Failure in refresh sequence: ${e.message}"
                )
                withContext(Dispatchers.Main) {
                    swipeRefreshLayout?.isRefreshing = false
                    isSyncInProgress = false
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
        activity.readerManager?.setAppMode(
            ReaderManager.AppMode.IDLE,
            "Dashboard Disconnect/Forget"
        )
    }

    private fun updateHeader() {
        txtDeviceName?.text = secureStoreManager.deviceName
        val mac = activity.readerManager?.connectedDeviceAddress ?: "XX:XX:XX:XX:XX:XX"
        txtDeviceMac?.text = activity.getString(R.string.label_device_mac, mac)
    }

    fun teardownView() {
        activity.readerManager?.setAppMode(ReaderManager.AppMode.IDLE, "Management Dashboard Exit")
        metricsJob?.cancel()
        inventoryJob?.cancel()
        
        // --- CRITICAL FIX: Clear state so re-entry starts fresh ---
        backlogItems.clear()
        receivedInSync.clear()
        isSyncInProgress = false

        backlogAdapter = null
        swipeRefreshLayout = null
    }

    private fun handleReaderEvent(event: ReaderEvent) {
        when (event) {
            is ReaderEvent.ConnectionSuccessful -> {
                logAndToast(R.string.status_connected)
                updateDeviceList()
            }

            is ReaderEvent.AuthenticationFailed -> {
                logAndToast(R.string.error_incorrect_password)
                connectingAddress = null
                updateDeviceList()
            }

            is ReaderEvent.Error -> {
                android.util.Log.e(TAG, "[Reader Error] ${event.message}")
                Toast.makeText(activity, event.message, Toast.LENGTH_SHORT).show()
                updateDeviceList()
            }
        }
    }

    private fun showPasswordPromptDialog(readerName: String, address: String) {
        val dialogView =
            LayoutInflater.from(activity).inflate(R.layout.dialog_reader_password, null)
        val inputField = dialogView.findViewById<TextInputEditText>(R.id.editReaderPassword)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.title_assign_tag, readerName))
            .setView(dialogView)
            .setPositiveButton("Connect", null)
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener {
                // If we aren't CONNECTING or CONNECTED, clear the visual state
                if (activity.readerManager?.connectionState?.value == ReaderManager.ConnectionState.DISCONNECTED ||
                    activity.readerManager?.connectionState?.value == ReaderManager.ConnectionState.SCANNING
                ) {
                    if (connectingAddress == address) {
                        connectingAddress = null
                        updateDeviceList()
                    }
                }
            }
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typedPassword = inputField.text.toString().trim()
            if (typedPassword.isNotEmpty()) {
                android.util.Log.d(
                    TAG,
                    "[Connect Flow] Password entered. Saving and starting connection."
                )
                secureStoreManager.saveReaderCredentials(readerName, typedPassword)

                connectingAddress = address
                activity.readerManager?.isBroadDiscoveryMode = false
                activity.readerManager?.startConnecting(readerName, typedPassword)

                dialog.dismiss()
                updateDeviceList()
                logAndToast(R.string.status_connecting)
            } else {
                inputField.error = "Password cannot be blank"
            }
        }
    }

    private fun showEditReaderDialog(readerName: String, address: String) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_reader, null)
        val inputName = dialogView.findViewById<TextInputEditText>(R.id.editReaderName)
        val inputOldPass = dialogView.findViewById<TextInputEditText>(R.id.editOldPassword)
        val inputNewPass = dialogView.findViewById<TextInputEditText>(R.id.editNewPassword)
        val inputConfirmPass =
            dialogView.findViewById<TextInputEditText>(R.id.editConfirmNewPassword)
        inputName.setText(readerName)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.action_edit)
            .setView(dialogView)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = inputName.text.toString().trim()
            val oldPass = inputOldPass.text.toString()
            val newPass = inputNewPass.text.toString()
            val confirmPass = inputConfirmPass.text.toString()
            val storedPass = secureStoreManager.getAuthPasswordFor(readerName) ?: ""

            when {
                newName.isEmpty() -> inputName.error = activity.getString(R.string.error_empty_name)
                oldPass != storedPass -> inputOldPass.error =
                    activity.getString(R.string.error_incorrect_old_password)

                newPass.isEmpty() -> inputNewPass.error =
                    activity.getString(R.string.error_empty_password)

                newPass != confirmPass -> inputConfirmPass.error =
                    activity.getString(R.string.error_passwords_mismatch)

                else -> {
                    activity.readerManager?.updateReaderConfig(newName, newPass)
                    secureStoreManager.clearCredentialsFor(readerName)
                    secureStoreManager.saveReaderCredentials(newName, newPass)
                    secureStoreManager.deviceName = newName
                    logAndToast(R.string.toast_config_update_sent)
                    dialog.dismiss()
                    updateHeader()
                }
            }
        }
    }

    private data class DeviceItem(
        val name: String,
        val address: String,
        val rssi: Int?,
        val isConnected: Boolean,
        val isConnecting: Boolean
    )

    private data class BacklogItem(val tagId: String, val studentName: String, val timestamp: Long)

    private class DeviceListAdapter(
        private val onDeviceSelected: (String, String) -> Unit,
        private val onDeviceLongClicked: (String, String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items = mutableListOf<Any>()

        fun submitList(
            connected: List<DeviceItem>,
            known: List<DeviceItem>,
            unknown: List<DeviceItem>
        ) {
            val newList = mutableListOf<Any>()
            if (connected.isNotEmpty()) {
                newList.add("CONNECTED")
                newList.addAll(connected)
            }
            if (known.isNotEmpty()) {
                newList.add("KNOWN")
                newList.addAll(known)
            }
            if (unknown.isNotEmpty()) {
                newList.add("UNKNOWN")
                newList.addAll(unknown)
            }

            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = newList.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = items[oldItemPosition]
                    val new = newList[newItemPosition]
                    return if (old is String && new is String) {
                        old == new
                    } else if (old is DeviceItem && new is DeviceItem) {
                        old.address == new.address
                    } else {
                        false
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return items[oldItemPosition] == newList[newItemPosition]
                }
            })

            items.clear()
            items.addAll(newList)
            diffResult.dispatchUpdatesTo(this)
        }

        override fun getItemViewType(position: Int) = if (items[position] is String) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderViewHolder(
                inflater.inflate(
                    R.layout.item_list_header,
                    parent,
                    false
                )
            )
            else DeviceViewHolder(inflater.inflate(R.layout.item_stat_card, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderViewHolder && item is String) {
                holder.txtHeader.text = when (item) {
                    "CONNECTED" -> holder.itemView.context.getString(R.string.header_connected_devices)
                    "KNOWN" -> holder.itemView.context.getString(R.string.header_known_devices)
                    else -> holder.itemView.context.getString(R.string.header_unknown_devices)
                }
            } else if (holder is DeviceViewHolder && item is DeviceItem) {
                holder.txtName.text = item.name
                holder.txtMac.text = item.address

                // --- UI Fix: Connection accent now purely based on hardware state ---
                holder.viewAccent.setBackgroundColor(if (item.isConnected) "#4CAF50".toColorInt() else Color.TRANSPARENT)

                if (item.isConnecting) {
                    holder.txtValue.text =
                        holder.itemView.context.getString(R.string.status_connecting)
                    holder.imgSignal.visibility = View.GONE
                } else {
                    holder.imgSignal.visibility = View.VISIBLE
                    val (iconRes, rssiText) = when {
                        item.rssi == null -> R.drawable.ic_signal_weak to "--"
                        item.rssi >= -60 -> R.drawable.ic_signal_strong to "${item.rssi}"
                        item.rssi >= -80 -> R.drawable.ic_signal_medium to "${item.rssi}"
                        else -> R.drawable.ic_signal_weak to "${item.rssi}"
                    }
                    holder.imgSignal.setImageResource(iconRes)
                    holder.txtValue.text = rssiText
                }
                holder.itemView.setOnClickListener { onDeviceSelected(item.name, item.address) }
                holder.itemView.setOnLongClickListener {
                    onDeviceLongClicked(item.name, item.address)
                    true
                }
            }
        }

        override fun getItemCount() = items.size
        class HeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val txtHeader: TextView = v.findViewById(R.id.txtHeader)
        }

        class DeviceViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtPrimaryLabel)
            val txtMac: TextView = v.findViewById(R.id.txtSecondaryLabel)
            val txtValue: TextView = v.findViewById(R.id.txtStatValue)
            val imgSignal: ImageView = v.findViewById(R.id.imgSignalIcon)
            val viewAccent: View = v.findViewById(R.id.viewConnectionAccent)
        }
    }

    private class BacklogAdapter(
        private val onItemLongClicked: (BacklogItem) -> Unit
    ) : RecyclerView.Adapter<BacklogAdapter.ViewHolder>() {
        private var items = mutableListOf<BacklogItem>()

        fun submitList(newItems: List<BacklogItem>) {
            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = items[oldItemPosition]
                    val new = newItems[newItemPosition]
                    return old.tagId == new.tagId && old.timestamp == new.timestamp
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return items[oldItemPosition] == newItems[newItemPosition]
                }
            })

            items.clear()
            items.addAll(newItems)
            diffResult.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_stat_card, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.txtName.text = item.studentName
            holder.txtTag.text = item.tagId
            holder.txtDate.text = SimpleDateFormat(
                "dd/MM HH:mm:ss",
                Locale.getDefault()
            ).format(Date(item.timestamp * 1000L))

            holder.itemView.setOnLongClickListener {
                onItemLongClicked(item)
                true
            }
        }

        override fun getItemCount() = items.size
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtPrimaryLabel)
            val txtTag: TextView = v.findViewById(R.id.txtSecondaryLabel)
            val txtDate: TextView = v.findViewById(R.id.txtStatValue)
        }
    }
}
