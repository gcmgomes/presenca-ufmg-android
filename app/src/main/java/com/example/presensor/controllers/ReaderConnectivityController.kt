package com.example.presensor.controllers

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.MainActivity
import com.example.presensor.R
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

@SuppressLint("MissingPermission")
class ReaderConnectivityController(
    private val activity: MainActivity,
    private val db: AppDatabase,
    private val secureStoreManager: SecureStoreManager,
    private val scope: CoroutineScope
) {
    private val discoveredDevices = mutableMapOf<String, Pair<ScanResult, Long>>()
    private var adapter: DeviceListAdapter? = null
    private var statusJob: Job? = null
    private var refreshJob: Job? = null
    private var connectingAddress: String? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null

    companion object {
        private const val REFRESH_INTERVAL_MS = 10000L
        private const val STALE_THRESHOLD_MS = 20000L
    }

    fun setupReaderManagementView(rootView: View) {
        val switchUseReader = rootView.findViewById<SwitchMaterial>(R.id.switchUseReader)
        val recyclerView = rootView.findViewById<RecyclerView>(R.id.readerRecyclerView)
        swipeRefreshLayout = rootView.findViewById(R.id.swipeRefreshReader)

        switchUseReader.isChecked = secureStoreManager.isReaderEnabled
        swipeRefreshLayout?.isEnabled = secureStoreManager.isReaderEnabled
        
        adapter = DeviceListAdapter(
            onDeviceSelected = { name, address -> handleReaderSelection(name, address) },
            onDeviceLongClicked = { name, address -> showReaderActionsDialog(name, address) }
        )
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = adapter

        updateDeviceList()

        switchUseReader.setOnCheckedChangeListener { _, isChecked ->
            secureStoreManager.isReaderEnabled = isChecked
            swipeRefreshLayout?.isEnabled = isChecked
            if (isChecked) {
                startDiscovery()
            } else {
                stopDiscovery(fullDisconnect = true)
            }
        }

        swipeRefreshLayout?.setOnRefreshListener {
            startDiscovery()
            scope.launch {
                delay(1000)
                swipeRefreshLayout?.isRefreshing = false
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

        startRefreshLoop()

        if (secureStoreManager.isReaderEnabled) {
            startDiscovery()
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
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
            if (now - entry.value.second > STALE_THRESHOLD_MS) {
                iterator.remove()
            }
        }
        updateDeviceList()
    }

    private fun startDiscovery() {
        discoveredDevices.clear()
        activity.readerManager?.isBroadDiscoveryMode = true
        activity.readerManager?.onDeviceFoundListener = { result ->
            activity.runOnUiThread {
                val address = result.device.address
                val isNew = !discoveredDevices.containsKey(address)
                discoveredDevices[address] = result to System.currentTimeMillis()
                
                if (isNew) {
                    updateDeviceList()
                }
            }
        }
        activity.readerManager?.startScan()
        updateDeviceList()
    }

    fun stopDiscovery(fullDisconnect: Boolean) {
        activity.readerManager?.onDeviceFoundListener = null
        if (fullDisconnect) {
            activity.readerManager?.disconnect()
        } else {
            activity.readerManager?.stopScanning()
        }
        discoveredDevices.clear()
        connectingAddress = null
        updateDeviceList()
        refreshJob?.cancel()
        adapter = null
        statusJob?.cancel()
        swipeRefreshLayout = null
    }

    private fun updateDeviceList() {
        if (!secureStoreManager.isReaderEnabled) {
            adapter?.submitList(emptyList(), emptyList(), emptyList())
            return
        }

        val connectedAddress = activity.readerManager?.connectedDeviceAddress
        
        val connectedItems = mutableListOf<DeviceItem>()
        val knownItems = mutableListOf<DeviceItem>()
        val unknownItems = mutableListOf<DeviceItem>()

        if (connectedAddress != null) {
            val scanResult = discoveredDevices[connectedAddress]?.first
            val name = scanResult?.scanRecord?.deviceName ?: scanResult?.device?.name ?: secureStoreManager.deviceName
            connectedItems.add(DeviceItem(name, connectedAddress, scanResult?.rssi, isConnected = true, isConnecting = false))
        }

        discoveredDevices.values.forEach { (result, _) ->
            val address = result.device.address
            if (address == connectedAddress) return@forEach

            val name = result.scanRecord?.deviceName ?: result.device.name ?: "Unknown"
            val isConnecting = address == connectingAddress
            
            val item = DeviceItem(name, address, result.rssi, isConnected = false, isConnecting = isConnecting)
            
            if (secureStoreManager.hasPasswordFor(name)) {
                knownItems.add(item)
            } else {
                unknownItems.add(item)
            }
        }
        
        adapter?.submitList(connectedItems, knownItems, unknownItems)
    }

    private fun handleReaderSelection(name: String, address: String) {
        if (address == activity.readerManager?.connectedDeviceAddress) return
        
        connectingAddress = address
        updateDeviceList()
        
        secureStoreManager.deviceName = name
        if (!secureStoreManager.hasPasswordFor(name)) {
            showPasswordPromptDialog(name)
        } else {
            connectToTargetReader()
        }
    }

    private fun showReaderActionsDialog(name: String, address: String) {
        val options = arrayOf(
            activity.getString(R.string.action_disconnect),
            activity.getString(R.string.action_forget),
            activity.getString(R.string.action_edit)
        )

        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_reader_actions_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> activity.readerManager?.disconnect(disableAutoReconnect = true)
                    1 -> {
                        secureStoreManager.clearCredentialsFor(name)
                        updateDeviceList()
                    }
                    2 -> showEditReaderDialog(name, address)
                }
            }
            .show()
    }

    private fun showEditReaderDialog(readerName: String, address: String) {
        val isConnected = (address == activity.readerManager?.connectedDeviceAddress)
        if (!isConnected) {
            Toast.makeText(activity, "Must be connected to edit configuration", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_reader, null)
        val inputName = dialogView.findViewById<TextInputEditText>(R.id.editReaderName)
        val inputOldPass = dialogView.findViewById<TextInputEditText>(R.id.editOldPassword)
        val inputNewPass = dialogView.findViewById<TextInputEditText>(R.id.editNewPassword)
        val inputConfirmPass = dialogView.findViewById<TextInputEditText>(R.id.editConfirmNewPassword)

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
                oldPass != storedPass -> inputOldPass.error = activity.getString(R.string.error_incorrect_old_password)
                newPass.isEmpty() -> inputNewPass.error = activity.getString(R.string.error_empty_password)
                newPass != confirmPass -> inputConfirmPass.error = activity.getString(R.string.error_passwords_mismatch)
                else -> {
                    // Send to BLE
                    activity.readerManager?.updateReaderConfig(newName, newPass)
                    // Update Local
                    secureStoreManager.clearCredentialsFor(readerName)
                    secureStoreManager.saveReaderCredentials(newName, newPass)
                    secureStoreManager.deviceName = newName
                    
                    Toast.makeText(activity, R.string.toast_config_update_sent, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    updateDeviceList()
                }
            }
        }
    }

    private fun showPasswordPromptDialog(readerName: String) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(R.string.title_assign_tag)
        builder.setMessage("Enter the access password for:\n'$readerName'")

        val inputField = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Reader Password"
        }

        val container = FrameLayout(activity)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 45
            rightMargin = 45
        }
        inputField.layoutParams = params
        container.addView(inputField)
        builder.setView(container)

        builder.setPositiveButton("Connect", null)
        builder.setNegativeButton(R.string.action_cancel) { dialog, _ ->
            connectingAddress = null
            updateDeviceList()
            dialog.dismiss()
        }

        val alertDialog = builder.create()
        alertDialog.show()

        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typedPassword = inputField.text.toString().trim()
            if (typedPassword.isNotEmpty()) {
                secureStoreManager.saveReaderCredentials(readerName, typedPassword)
                connectToTargetReader()
                alertDialog.dismiss()
            } else {
                inputField.error = "Password cannot be blank"
            }
        }
    }

    private fun connectToTargetReader() {
        activity.readerManager?.isBroadDiscoveryMode = false
        activity.readerManager?.startConnecting()
    }

    private data class DeviceItem(
        val name: String,
        val address: String,
        val rssi: Int?,
        val isConnected: Boolean,
        val isConnecting: Boolean
    )

    private class DeviceListAdapter(
        private val onDeviceSelected: (String, String) -> Unit,
        private val onDeviceLongClicked: (String, String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items = mutableListOf<Any>()

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_DEVICE = 1
        }

        fun submitList(connected: List<DeviceItem>, known: List<DeviceItem>, unknown: List<DeviceItem>) {
            items.clear()
            if (connected.isNotEmpty()) {
                items.add("CONNECTED")
                items.addAll(connected)
            }
            if (known.isNotEmpty()) {
                items.add("KNOWN")
                items.addAll(known)
            }
            if (unknown.isNotEmpty()) {
                items.add("UNKNOWN")
                items.addAll(unknown)
            }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is String) TYPE_HEADER else TYPE_DEVICE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderViewHolder(inflater.inflate(R.layout.item_list_header, parent, false))
            } else {
                DeviceViewHolder(inflater.inflate(R.layout.item_student_stat_card, parent, false))
            }
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
                
                // Set Connection Accent
                if (item.isConnected) {
                    holder.viewAccent.setBackgroundColor(Color.parseColor("#4CAF50")) // Material Green
                } else {
                    holder.viewAccent.setBackgroundColor(Color.TRANSPARENT)
                }

                // Set Signal Icon and Text
                if (item.isConnecting) {
                    holder.txtValue.text = holder.itemView.context.getString(R.string.status_connecting)
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

        override fun getItemCount(): Int = items.size

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
}
