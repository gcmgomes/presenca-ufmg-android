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
import com.example.presensor.communication.ReaderEvent
import com.example.presensor.communication.ReaderManager
import com.example.presensor.communication.core.AppMode
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.SecureStoreManager
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private var currentDevices: List<com.example.presensor.communication.core.ReaderDevice> = emptyList()
    private var isAuth: Boolean = false
    private var connectedAddress: String? = null
    
    private var listAdapter: DeviceListAdapter? = null
    private var backlogAdapter: BacklogAdapter? = null

    private var statusJob: Job? = null
    private var refreshJob: Job? = null
    private var metricsJob: Job? = null
    private var inventoryJob: Job? = null
    private var eventJob: Job? = null

    private var connectingAddress: String? = null
    private var pendingPassword: String? = null
    private var pendingDeviceName: String? = null
    private var backlogItems = mutableListOf<BacklogItem>()
    private val receivedInSync = mutableSetOf<BacklogItem>()
    private var isSyncInProgress = false
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var dashboardUIJob: Job? = null
    private var currentDashboardAddress: String? = null

    // Dashboard Views
    private var txtDeviceName: TextView? = null
    private var txtDeviceMac: TextView? = null
    private var txtStatFiles: TextView? = null
    private var txtStatTime: TextView? = null
    private var txtStatBattery: TextView? = null

    companion object {
        private const val REFRESH_INTERVAL_MS = 10000L
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

        switchUseReader.isChecked = activity.readerManager?.isReaderEnabled?.value ?: false
        listRefresh?.isEnabled = activity.readerManager?.isReaderEnabled?.value ?: false

        listAdapter = DeviceListAdapter(
            onDeviceSelected = { name, address -> handleReaderSelection(name, address) },
            onDeviceLongClicked = { name, address -> handleReaderLongClick(name, address) }
        )
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = listAdapter

        updateDeviceList()

        switchUseReader.setOnCheckedChangeListener { _, isChecked ->
            activity.readerManager?.setReaderEnabled(isChecked)
            listRefresh?.isEnabled = isChecked
            if (isChecked) {
                startRefreshLoop()

                // --- UI FIX: Unified Start Path ---
                // We attempt to connect if credentials exist; otherwise, startConnecting() 
                // will default to a broad scan if it fails internally.
                val name = secureStoreManager.deviceName
                val password = secureStoreManager.getAuthPasswordFor(name)
                if (password != null) {
                    activity.readerManager?.startConnecting(name, password)
                } else {
                    startDiscovery()
                }
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
            val rManager = activity.readerManager ?: return@launch
            
            // --- ATOMIC UI SYNC (Task 4.2 Fix) ---
            // Combine all factors into a single flow to ensure they are updated simultaneously
            combine(
                rManager.discoveredDevices,
                rManager.connectionState,
                rManager.isAuthenticated,
                rManager.connectedAddress
            ) { devices, state, auth, connAddr ->
                deviceUpdate(devices, state, auth, connAddr)
            }.collect { /* collect triggers updateDeviceList via deviceUpdate */ }
        }

        eventJob?.cancel()
        eventJob = scope.launch(Dispatchers.Main) {
            launch {
                activity.readerManager?.isReaderEnabled?.collect { enabled ->
                    switchUseReader.isChecked = enabled
                    listRefresh?.isEnabled = enabled
                    if (!enabled) {
                        updateDeviceList()
                    }
                }
            }
            activity.readerManager?.eventFlow?.collect { event ->
                handleReaderEvent(event)
            }
        }

        if (activity.readerManager?.isReaderEnabled?.value == true) {
            startDiscovery()
            startRefreshLoop()
        }
    }

    fun teardownDiscovery(fullDisconnect: Boolean = false) {
        activity.readerManager?.isBroadDiscoveryMode = false
        if (fullDisconnect) {
            activity.readerManager?.disconnect()
        } else {
            activity.readerManager?.stopScanning()
            activity.readerManager?.setAppMode(AppMode.IDLE, "Discovery Teardown")
        }
        // CRITICAL FIX: Only clear the list if we are doing a FULL disconnect/shutdown
        if (fullDisconnect) {
            currentDevices = emptyList()
            updateDeviceList()
        }
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun startDiscovery() {
        activity.readerManager?.isBroadDiscoveryMode = true
        activity.readerManager?.startScan()

        // --- SCAN OPTIMIZATION: 5s active search to save battery ---
        scope.launch {
            delay(5000)
            activity.readerManager?.stopScanning()
        }
        updateDeviceList()
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (activity.readerManager?.isReaderEnabled?.value == true) {
                    // Increase interval to 20 seconds to stay safely under OS throttling limits
                    startDiscovery()
                    activity.readerManager?.requestRssiUpdate()
                }
                delay(20000)
            }
        }
    }

    private fun deviceUpdate(
        devices: List<com.example.presensor.communication.core.ReaderDevice>,
        state: ReaderManager.ConnectionState,
        auth: Boolean,
        connAddr: String?
    ) {
        // Sync local variables
        this.currentDevices = devices
        this.isAuth = auth
        this.connectedAddress = connAddr

        // Handle the 'Connecting' label clearing logic
        val isFailure = state == ReaderManager.ConnectionState.DISCONNECTED
        val isTargetAuthenticated = auth && connAddr?.uppercase() == connectingAddress?.uppercase()

        if (isFailure || isTargetAuthenticated) {
            if (connectingAddress != null) {
                android.util.Log.d(TAG, "[Connect Flow] Atomic Clear of connectingAddress. Success: $isTargetAuthenticated")
                connectingAddress = null
            }
        }

        updateDeviceList()
    }

    private fun updateDeviceList() {
        if (activity.readerManager?.isReaderEnabled?.value != true) {
            listAdapter?.submitList(emptyList(), emptyList(), emptyList())
            return
        }

        // --- ATOMIC SYNC: Using local variables updated by deviceUpdate() ---
        val connectedAddress = this.connectedAddress?.uppercase()
        val isAuth = this.isAuth

        val connectedItems = mutableListOf<DeviceItem>()
        val knownItems = mutableListOf<DeviceItem>()
        val unknownItems = mutableListOf<DeviceItem>()

        // The Manager now provides a single combined list of both discovered and active devices.
        // We simply map them and categorize them.
        currentDevices.forEach { device ->
            val deviceAddr = device.address.uppercase()
            val isConnected = deviceAddr == connectedAddress && isAuth
            // Connecting if: specifically targeted by UI OR hardware is in handshake phase
            val isConnecting = (deviceAddr == connectingAddress?.uppercase()) || (deviceAddr == connectedAddress && !isAuth)

            val item = DeviceItem(
                name = device.name,
                address = device.address,
                rssi = device.rssi,
                batteryLevel = device.batteryLevel,
                isConnected = isConnected,
                isConnecting = isConnecting
            )

            when {
                isConnected -> connectedItems.add(item)
                secureStoreManager.hasPasswordFor(device.name) -> knownItems.add(item)
                else -> unknownItems.add(item)
            }
        }

        android.util.Log.v(TAG, "[UI Sync] updateDeviceList counts: connected=${connectedItems.size}, known=${knownItems.size}, unknown=${unknownItems.size}")
        listAdapter?.submitList(connectedItems, knownItems, unknownItems)
    }

    private fun handleReaderSelection(name: String, address: String) {
        android.util.Log.d(
            TAG,
            "[Connect Flow] handleReaderSelection triggered for '$name' at $address"
        )

        // 1. If already connected AND authenticated, DISCONNECT on simple tap (User Request)
        if (address == activity.readerManager?.connectedDeviceAddress && activity.readerManager?.isAuthenticated?.value == true) {
            android.util.Log.d(TAG, "[Connect Flow] Already connected. Tapping to DISCONNECT.")
            activity.readerManager?.disconnect(disableAutoReconnect = true)
            logAndToast(R.string.status_disconnected)

            // --- UI Fix: Immediately resume discovery so the device re-appears as 'Known' instantly ---
            startDiscovery()
            updateDeviceList()
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
            activity.readerManager?.startConnecting(name, storedPassword, address)
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
        activity.openDeviceManager(address)
    }

    // --- Device Management (Dashboard) ---

    fun setupReaderManagementView(rootView: View, address: String? = null) {
        android.util.Log.i(
            TAG,
            "[Management] setupReaderManagementView START. Root ID: ${rootView.id}"
        )

        currentDashboardAddress = address ?: activity.readerManager?.connectedDeviceAddress

        txtDeviceName = rootView.findViewById(R.id.txtDeviceName)
        txtDeviceMac = rootView.findViewById(R.id.txtDeviceMac)
        txtStatFiles = rootView.findViewById(R.id.txtStatFilesCount)
        txtStatTime = rootView.findViewById(R.id.txtStatDeviceTime)
        txtStatBattery = rootView.findViewById(R.id.txtStatBattery)
        val viewAccent = rootView.findViewById<View>(R.id.viewDeviceDetailAccent)

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
            if (activity.readerManager?.isAuthenticated?.value == true) {
                activity.readerManager?.syncTime()
                logAndToast(R.string.action_sync_time)
            } else {
                Toast.makeText(activity, R.string.toast_tag_not_registered, Toast.LENGTH_SHORT)
                    .show() // Fallback
            }
        }

        rootView.findViewById<View>(R.id.btnForget).setOnClickListener {
            val name = secureStoreManager.deviceName
            activity.readerManager?.disconnect(disableAutoReconnect = true)
            secureStoreManager.clearCredentialsFor(name)
            resetDashboardUI()
        }

        swipeRefreshLayout?.setOnRefreshListener {
            if (activity.readerManager?.isAuthenticated?.value == true) {
                android.util.Log.e(TAG, "[CRITICAL] Pull-to-refresh TRIGGERED. Entry point hit.")
                refreshManagementData("Manual Pull-to-Refresh")
            } else {
                swipeRefreshLayout?.isRefreshing = false
                Toast.makeText(activity, R.string.toast_cloud_sync_failed, Toast.LENGTH_SHORT)
                    .show()
            }
        }

        updateHeader()

        // --- REACTIVE DASHBOARD UI ---
        val dashboardTraceId = System.currentTimeMillis() % 10000
        android.util.Log.i(
            TAG,
            "[Management] STARTING Dashboard Collector Trace: #$dashboardTraceId"
        )

        dashboardUIJob?.cancel()
        dashboardUIJob = scope.launch(Dispatchers.Main) {
            // Observe both connection state and authentication via combine
            kotlinx.coroutines.flow.combine(
                activity.readerManager!!.connectionState,
                activity.readerManager!!.isAuthenticated
            ) { state, auth -> state to auth }
                .distinctUntilChanged()
                .collect { (state, auth) ->
                    val isReady = state == ReaderManager.ConnectionState.CONNECTED && auth
                    val isConnecting = state == ReaderManager.ConnectionState.CONNECTING ||
                            (state == ReaderManager.ConnectionState.CONNECTED && !auth)

                    android.util.Log.d(
                        TAG,
                        "[Trace #$dashboardTraceId] UI Update -> State: $state, Auth: $auth, Ready: $isReady"
                    )

                    // 1. Accent color
                    val accentColor = when {
                        isReady -> "#4CAF50".toColorInt()       // Green
                        isConnecting -> "#FF9800".toColorInt()  // Orange
                        else -> Color.TRANSPARENT
                    }
                    viewAccent?.setBackgroundColor(accentColor)

                    // 2. Action buttons (Disconnect/Connect toggle)
                    val btnDisconnect = rootView.findViewById<LinearLayout>(R.id.btnDisconnect)
                    val imgDisconnect = btnDisconnect.getChildAt(0) as? ImageView
                    val txtDisconnect = btnDisconnect.getChildAt(1) as? TextView

                    if (isReady || isConnecting) {
                        txtDisconnect?.text = activity.getString(R.string.action_disconnect)
                        imgDisconnect?.setImageResource(R.drawable.ic_reader_disconnected)
                        btnDisconnect.setOnClickListener {
                            activity.readerManager?.disconnect(disableAutoReconnect = true)
                            resetDashboardUI()
                        }
                    } else {
                        txtDisconnect?.text = activity.getString(R.string.action_connect)
                        imgDisconnect?.setImageResource(R.drawable.ic_reader_connected)
                        btnDisconnect.setOnClickListener {
                            val targetAddr = currentDashboardAddress
                                ?: activity.readerManager?.connectedDeviceAddress ?: ""
                            handleReaderSelection(secureStoreManager.deviceName, targetAddr)
                        }
                    }

                    // 3. Trigger management setup ONLY when it transition to functional ready
                    if (isReady) {
                        activity.readerManager?.setAppMode(
                            AppMode.MANAGEMENT,
                            "Management Dashboard Reactivation"
                        )
                        swipeRefreshLayout?.isRefreshing = true
                        refreshManagementData("Dashboard State Transition")
                    } else if (!isConnecting) {
                        txtStatFiles?.text = "--"
                        txtStatTime?.text = "--"
                        txtStatBattery?.text = "--%"
                    }
                }
        }

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
                    logAndToast(R.string.toast_backlog_deleted_success)
                    refreshManagementData("Post-Deletion Refresh")
                } else if (rawTagId == "DEL_ERR") {
                    logAndToast(R.string.toast_backlog_delete_failed)
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
            AppMode.IDLE,
            "Dashboard Disconnect/Forget"
        )
    }

    private fun updateHeader() {
        txtDeviceName?.text = secureStoreManager.deviceName
        val mac = activity.readerManager?.connectedDeviceAddress ?: "XX:XX:XX:XX:XX:XX"
        txtDeviceMac?.text = activity.getString(R.string.label_device_mac, mac)
    }

    fun teardownView() {
        activity.readerManager?.setAppMode(AppMode.IDLE, "Management Dashboard Exit")
        metricsJob?.cancel()
        inventoryJob?.cancel()
        dashboardUIJob?.cancel()
        currentDashboardAddress = null

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
                
                // --- TASK 4.3 V2: Persist credentials ONLY on confirmed success ---
                if (pendingPassword != null && pendingDeviceName != null) {
                    android.util.Log.i(TAG, "[Connect Flow] Success confirmed. Promoting '$pendingDeviceName' to KNOWN category.")
                    secureStoreManager.saveReaderCredentials(pendingDeviceName!!, pendingPassword!!)
                    pendingPassword = null
                    pendingDeviceName = null
                }
                
                updateDeviceList()
            }

            is ReaderEvent.AuthenticationFailed -> {
                logAndToast(R.string.error_incorrect_password)
                // --- TASK 4.3 V2: Discard pending credentials on rejection ---
                pendingPassword = null
                pendingDeviceName = null
                
                // --- BUGFIX (Task 4.2): Ensure UI lock is released on rejection ---
                android.util.Log.w(TAG, "[Connect Flow] Auth Failed event received. Clearing connectingAddress.")
                connectingAddress = null
                updateDeviceList()
            }

            is ReaderEvent.Error -> {
                android.util.Log.e(TAG, "[Reader Error] ${event.message}")
                Toast.makeText(activity, event.message, Toast.LENGTH_SHORT).show()
                
                pendingPassword = null
                pendingDeviceName = null
                
                connectingAddress = null
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
                    "[Connect Flow] Password entered. Deferring save until success."
                )
                
                // --- TASK 4.3 V2: Hold credentials in limbo until auth success ---
                pendingPassword = typedPassword
                pendingDeviceName = readerName

                connectingAddress = address
                activity.readerManager?.isBroadDiscoveryMode = false
                activity.readerManager?.startConnecting(readerName, typedPassword, address)

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
        val batteryLevel: Int? = null,
        val isConnected: Boolean,
        val isConnecting: Boolean
    )

    private data class BacklogItem(val tagId: String, val studentName: String, val timestamp: Long)

    private class DeviceListAdapter(
        private val onDeviceSelected: (String, String) -> Unit,
        private val onDeviceLongClicked: (String, String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items = mutableListOf<Any>()

        companion object {
            private const val PAYLOAD_RSSI = "PAYLOAD_RSSI"
            private const val PAYLOAD_BATTERY = "PAYLOAD_BATTERY"
            private const val PAYLOAD_STATE = "PAYLOAD_STATE"
        }

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

                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean {
                    return items[oldItemPosition] == newList[newItemPosition]
                }

                override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                    val old = items[oldItemPosition]
                    val new = newList[newItemPosition]

                    if (old is DeviceItem && new is DeviceItem) {
                        val payloads = mutableSetOf<String>()
                        if (old.rssi != new.rssi) payloads.add(PAYLOAD_RSSI)
                        if (old.batteryLevel != new.batteryLevel) payloads.add(PAYLOAD_BATTERY)
                        if (old.isConnected != new.isConnected || old.isConnecting != new.isConnecting) {
                            payloads.add(PAYLOAD_STATE)
                        }
                        if (payloads.isNotEmpty()) return payloads
                    }
                    return super.getChangePayload(oldItemPosition, newItemPosition)
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
            onBindViewHolder(holder, position, emptyList())
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: List<Any>
        ) {
            val item = items[position]

            if (holder is HeaderViewHolder && item is String) {
                holder.txtHeader.text = when (item) {
                    "CONNECTED" -> holder.itemView.context.getString(R.string.header_connected_devices)
                    "KNOWN" -> holder.itemView.context.getString(R.string.header_known_devices)
                    else -> holder.itemView.context.getString(R.string.header_unknown_devices)
                }
                return
            }

            if (holder is DeviceViewHolder && item is DeviceItem) {
                // If we have payloads, only update specific fields
                if (payloads.isNotEmpty()) {
                    val combinedPayloads = payloads.filterIsInstance<Set<String>>().flatten()
                    if (combinedPayloads.contains(PAYLOAD_RSSI)) {
                        updateRssi(holder, item)
                    }
                    if (combinedPayloads.contains(PAYLOAD_BATTERY)) {
                        updateBattery(holder, item)
                    }
                    if (combinedPayloads.contains(PAYLOAD_STATE)) {
                        updateAccent(holder, item)
                        updateRssi(holder, item) // State change affects RSSI text too (Connecting...)
                    }
                    
                    // For any other change (like name), we still fall back to full bind
                    if (combinedPayloads.isEmpty()) fullBind(holder, item)
                } else {
                    fullBind(holder, item)
                }
            }
        }

        private fun fullBind(holder: DeviceViewHolder, item: DeviceItem) {
            holder.txtName.text = item.name
            holder.txtMac.text = item.address

            updateAccent(holder, item)
            updateRssi(holder, item)
            updateBattery(holder, item)

            holder.itemView.setOnClickListener { onDeviceSelected(item.name, item.address) }
            holder.itemView.setOnLongClickListener {
                onDeviceLongClicked(item.name, item.address)
                true
            }
        }

        private fun updateAccent(holder: DeviceViewHolder, item: DeviceItem) {
            val color = when {
                item.isConnected -> "#4CAF50".toColorInt()  // Green
                item.isConnecting -> "#FF9800".toColorInt() // Orange
                else -> Color.TRANSPARENT
            }
            holder.viewAccent.setBackgroundColor(color)
        }

        private fun updateRssi(holder: DeviceViewHolder, item: DeviceItem) {
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
        }

        private fun updateBattery(holder: DeviceViewHolder, item: DeviceItem) {
            if (item.batteryLevel != null && item.isConnected) {
                holder.txtValueSecondary.visibility = View.VISIBLE
                holder.txtValueSecondary.text = "${item.batteryLevel}%"
            } else {
                holder.txtValueSecondary.visibility = View.GONE
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
            val txtValueSecondary: TextView = v.findViewById(R.id.txtStatValueSecondary)
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

                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean {
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

            val date = Date(item.timestamp * 1000L)
            holder.txtTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)

            // Use system's local date format (e.g., dd/MM/yyyy for pt-BR)
            val df = android.text.format.DateFormat.getDateFormat(holder.itemView.context)
            holder.txtDate.text = df.format(date)
            holder.txtDate.visibility = View.VISIBLE

            holder.itemView.setOnLongClickListener {
                onItemLongClicked(item)
                true
            }
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
