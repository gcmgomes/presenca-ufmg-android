package com.example.presensor.controllers

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.communication.ReaderEvent
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.communication.core.AppMode
import com.example.presensor.data.SecureStoreManager
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

@SuppressLint("MissingPermission")
class ReaderDiscoveryController(
    private val activity: MainActivity,
    private val secureStoreManager: SecureStoreManager,
    private val interactionProvider: ReaderInteractionProvider,
    private val scope: CoroutineScope
) {
    private var currentDevices: List<com.example.presensor.communication.core.ReaderDevice> = emptyList()
    private var listAdapter: DeviceListAdapter? = null
    
    private var statusJob: Job? = null
    private var refreshJob: Job? = null
    private var eventJob: Job? = null
    
    private var pendingPassword: String? = null
    private var pendingDeviceName: String? = null

    companion object {
        private const val TAG = "ReaderDiscoveryCtrl"
    }

    private fun logAndToast(msgResId: Int, isShort: Boolean = true) {
        interactionProvider.showToast(msgResId, isShort)
    }

    fun setupReaderList(rootView: View) {
        val switchUseReader = rootView.findViewById<SwitchMaterial>(R.id.switchUseReader)
        val recyclerView = rootView.findViewById<RecyclerView>(R.id.readerRecyclerView)
        val listRefresh = rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshReader)

        switchUseReader.isChecked = activity.readerOrchestrator?.isReaderEnabled?.value ?: false
        listRefresh?.isEnabled = activity.readerOrchestrator?.isReaderEnabled?.value ?: false

        listAdapter = DeviceListAdapter(
            onDeviceSelected = { name, address -> handleReaderSelection(name, address) },
            onDeviceLongClicked = { name, address -> handleReaderLongClick(name, address) }
        )
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = listAdapter

        updateDeviceList()

        switchUseReader.setOnCheckedChangeListener { _, isChecked ->
            activity.readerOrchestrator?.setReaderEnabled(isChecked)
            listRefresh?.isEnabled = isChecked
            if (isChecked) {
                startRefreshLoop()
                val name = secureStoreManager.deviceName
                val password = secureStoreManager.getAuthPasswordFor(name)
                if (password != null) {
                    activity.readerOrchestrator?.startConnecting(name, password)
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
            val orchestrator = activity.readerOrchestrator ?: return@launch
            orchestrator.discoveredDevices.collect { devices ->
                deviceUpdate(devices)
            }
        }

        eventJob?.cancel()
        eventJob = scope.launch(Dispatchers.Main) {
            launch {
                activity.readerOrchestrator?.isReaderEnabled?.collect { enabled ->
                    switchUseReader.isChecked = enabled
                    listRefresh?.isEnabled = enabled
                    if (!enabled) {
                        updateDeviceList()
                    }
                }
            }
            activity.readerOrchestrator?.eventFlow?.collect { event ->
                handleReaderEvent(event)
            }
        }

        if (activity.readerOrchestrator?.isReaderEnabled?.value == true) {
            startDiscovery()
            startRefreshLoop()
        }
    }

    fun teardownDiscovery(fullDisconnect: Boolean = false) {
        activity.readerOrchestrator?.isBroadDiscoveryMode = false
        if (fullDisconnect) {
            activity.readerOrchestrator?.disconnect()
        } else {
            activity.readerOrchestrator?.stopScanning()
            activity.readerOrchestrator?.setAppMode(AppMode.IDLE, "Discovery Teardown")
        }
        if (fullDisconnect) {
            currentDevices = emptyList()
            updateDeviceList()
        }
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun startDiscovery() {
        activity.readerOrchestrator?.isBroadDiscoveryMode = true
        activity.readerOrchestrator?.startScan()
        scope.launch {
            delay(5000)
            activity.readerOrchestrator?.stopScanning()
        }
        updateDeviceList()
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (activity.readerOrchestrator?.isReaderEnabled?.value == true) {
                    startDiscovery()
                    activity.readerOrchestrator?.requestRssiUpdate()
                    
                    // Always refresh status for authenticated device during refresh cycles
                    if (activity.readerOrchestrator?.isAuthenticated?.value == true) {
                        activity.readerOrchestrator?.requestStatus()
                    }
                }
                delay(20000)
            }
        }
    }

    private fun deviceUpdate(devices: List<com.example.presensor.communication.core.ReaderDevice>) {
        this.currentDevices = devices
        updateDeviceList()
    }

    private fun updateDeviceList() {
        if (activity.readerOrchestrator?.isReaderEnabled?.value != true) {
            listAdapter?.submitList(emptyList(), emptyList(), emptyList())
            return
        }

        val connectedItems = mutableListOf<DeviceItem>()
        val knownItems = mutableListOf<DeviceItem>()
        val unknownItems = mutableListOf<DeviceItem>()

        currentDevices.forEach { device ->
            val item = DeviceItem(
                name = device.name,
                address = device.address,
                rssi = device.rssi,
                batteryLevel = device.batteryLevel,
                deviceEpoch = device.deviceEpoch,
                isConnected = device.isConnected,
                isConnecting = device.isConnecting,
                isNearby = device.isNearby,
                lastSeen = device.lastSeen
            )

            when {
                item.isConnected -> connectedItems.add(item)
                secureStoreManager.hasPasswordFor(device.name) -> knownItems.add(item)
                else -> unknownItems.add(item)
            }
        }
        listAdapter?.submitList(connectedItems, knownItems, unknownItems)
    }

    internal fun handleReaderSelection(name: String, address: String) {
        if (address == activity.readerOrchestrator?.connectedDeviceAddress && activity.readerOrchestrator?.isAuthenticated?.value == true) {
            activity.readerOrchestrator?.disconnect(disableAutoReconnect = true)
            logAndToast(R.string.status_disconnected)
            startDiscovery()
            updateDeviceList()
            return
        }

        secureStoreManager.deviceName = name
        val storedPassword = secureStoreManager.getAuthPasswordFor(name)
        if (storedPassword == null) {
            interactionProvider.showPasswordPromptDialog(
                readerName = name,
                onPasswordEntered = { password ->
                    pendingPassword = password
                    pendingDeviceName = name
                    activity.readerOrchestrator?.isBroadDiscoveryMode = false
                    activity.readerOrchestrator?.startConnecting(name, password, address, isManual = true)
                    updateDeviceList()
                    logAndToast(R.string.status_connecting)
                },
                onDismissed = {
                    if (activity.readerOrchestrator?.connectionState?.value == ReaderOrchestrator.ConnectionState.DISCONNECTED ||
                        activity.readerOrchestrator?.connectionState?.value == ReaderOrchestrator.ConnectionState.SCANNING
                    ) {
                        updateDeviceList()
                    }
                }
            )
        } else {
            activity.readerOrchestrator?.isBroadDiscoveryMode = false
            activity.readerOrchestrator?.startConnecting(name, storedPassword, address, isManual = true)
            updateDeviceList()
            logAndToast(R.string.status_connecting)
        }
    }

    private fun handleReaderLongClick(name: String, address: String) {
        secureStoreManager.deviceName = name
        activity.openDeviceManager(address)
    }

    private fun handleReaderEvent(event: ReaderEvent) {
        when (event) {
            is ReaderEvent.ConnectionSuccessful -> {
                logAndToast(R.string.status_connected)
                if (pendingPassword != null && pendingDeviceName != null) {
                    secureStoreManager.saveReaderCredentials(pendingDeviceName!!, pendingPassword!!)
                    pendingPassword = null
                    pendingDeviceName = null
                }
                updateDeviceList()
            }
            is ReaderEvent.AuthenticationFailed -> {
                logAndToast(R.string.error_incorrect_password)
                pendingPassword = null
                pendingDeviceName = null
                updateDeviceList()
            }
            is ReaderEvent.Error -> {
                val displayMessage = if (event.message == ReaderOrchestrator.ERROR_TIMEOUT) {
                    activity.getString(R.string.toast_connection_timed_out)
                } else {
                    event.message
                }
                interactionProvider.showToast(R.string.toast_connection_timed_out) // Simplified for now, can improve
                pendingPassword = null
                pendingDeviceName = null
                updateDeviceList()
            }
        }
    }

    internal data class DeviceItem(
        val name: String,
        val address: String,
        val rssi: Int?,
        val batteryLevel: Int? = null,
        val deviceEpoch: Long? = null,
        val isConnected: Boolean,
        val isConnecting: Boolean,
        val isNearby: Boolean = true,
        val lastSeen: Long = 0
    )

    private class DeviceListAdapter(
        private val onDeviceSelected: (String, String) -> Unit,
        private val onDeviceLongClicked: (String, String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items = mutableListOf<Any>()

        companion object {
            private const val PAYLOAD_RSSI = "PAYLOAD_RSSI"
            private const val PAYLOAD_BATTERY = "PAYLOAD_BATTERY"
            private const val PAYLOAD_TIME = "PAYLOAD_TIME"
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
                    return if (old is String && new is String) old == new
                    else if (old is DeviceItem && new is DeviceItem) old.address == new.address
                    else false
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return items[oldItemPosition] == newList[newItemPosition]
                }

                override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
                    val old = items[oldItemPosition]
                    val new = newList[newItemPosition]
                    if (old is DeviceItem && new is DeviceItem) {
                        val payloads = mutableSetOf<String>()
                        if (old.rssi != new.rssi) payloads.add(PAYLOAD_RSSI)
                        if (old.batteryLevel != new.batteryLevel) payloads.add(PAYLOAD_BATTERY)
                        if (old.deviceEpoch != new.deviceEpoch) payloads.add(PAYLOAD_TIME)
                        if (old.isConnected != new.isConnected || old.isConnecting != new.isConnecting || old.isNearby != new.isNearby) {
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
            return if (viewType == 0) HeaderViewHolder(inflater.inflate(R.layout.item_list_header, parent, false))
            else DeviceViewHolder(inflater.inflate(R.layout.item_stat_card, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            onBindViewHolder(holder, position, emptyList())
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
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
                if (payloads.isNotEmpty()) {
                    val combinedPayloads = payloads.filterIsInstance<Set<String>>().flatten()
                    if (combinedPayloads.contains(PAYLOAD_RSSI)) updateRssi(holder, item)
                    if (combinedPayloads.contains(PAYLOAD_BATTERY)) updateBattery(holder, item)
                    if (combinedPayloads.contains(PAYLOAD_TIME)) {
                        updateRssi(holder, item)
                        updateBattery(holder, item)
                    }
                    if (combinedPayloads.contains(PAYLOAD_STATE)) {
                        updateAccent(holder, item)
                        updateRssi(holder, item)
                        updateBattery(holder, item)
                        updateDimming(holder, item)
                    }
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
            updateDimming(holder, item)
            holder.itemView.setOnClickListener {
                if (item.isNearby || item.isConnected || item.isConnecting) onDeviceSelected(item.name, item.address)
            }
            holder.itemView.setOnLongClickListener {
                onDeviceLongClicked(item.name, item.address)
                true
            }
        }

        private fun updateDimming(holder: DeviceViewHolder, item: DeviceItem) {
            val isOffline = !item.isNearby && !item.isConnected && !item.isConnecting
            val alpha = if (isOffline) 0.5f else 1.0f
            holder.cardRoot.alpha = alpha
            holder.txtName.alpha = alpha
            holder.txtMac.alpha = alpha
            holder.txtValue.alpha = alpha
            holder.txtValueSecondary.alpha = alpha
            holder.imgSignal.alpha = alpha
            holder.imgBattery.alpha = alpha
            holder.viewAccent.alpha = alpha
        }

        private fun updateAccent(holder: DeviceViewHolder, item: DeviceItem) {
            val color = when {
                item.isConnected -> holder.itemView.context.getColor(R.color.chalk_green)
                item.isConnecting -> holder.itemView.context.getColor(R.color.chalk_orange)
                else -> Color.TRANSPARENT
            }
            holder.viewAccent.setBackgroundColor(color)
        }

        private fun updateRssi(holder: DeviceViewHolder, item: DeviceItem) {
            val isOffline = !item.isNearby && !item.isConnected && !item.isConnecting
            
            // Show/Hide Twin Stacks
            holder.layoutSignalStack.visibility = if (isOffline) View.GONE else View.VISIBLE
            holder.layoutLegacyValueStack.visibility = View.GONE
            
            if (item.isConnecting) {
                holder.layoutLegacyValueStack.visibility = View.VISIBLE
                holder.txtLegacyValue.text = holder.itemView.context.getString(R.string.status_connecting)
                holder.layoutSignalStack.visibility = View.GONE
                holder.layoutBatteryStack.visibility = View.GONE
            } else if (isOffline) {
                holder.layoutLegacyValueStack.visibility = View.VISIBLE
                holder.txtLegacyValue.text = holder.itemView.context.getString(R.string.status_not_found)
                holder.layoutSignalStack.visibility = View.GONE
                holder.layoutBatteryStack.visibility = View.GONE
            } else {
                holder.imgSignal.visibility = View.VISIBLE
                val iconRes = when {
                    item.rssi == null -> R.drawable.ic_signal_weak
                    item.rssi >= -60 -> R.drawable.ic_signal_strong
                    item.rssi >= -80 -> R.drawable.ic_signal_medium
                    else -> R.drawable.ic_signal_weak
                }
                holder.imgSignal.setImageResource(iconRes)
                
                // Row 2: Values (Primary text is RSSI) - Consistent 11sp orange
                holder.txtValue.text = if (item.rssi != null) "${item.rssi} dBm" else "--"
            }
        }

        private fun updateBattery(holder: DeviceViewHolder, item: DeviceItem) {
            if (item.isConnected) {
                holder.layoutBatteryStack.visibility = View.VISIBLE
                val battery = item.batteryLevel ?: 0
                val iconRes = when {
                    battery <= 33 -> R.drawable.ic_battery_low
                    battery <= 66 -> R.drawable.ic_battery_mid
                    else -> R.drawable.ic_battery_full
                }
                holder.imgBattery.setImageResource(iconRes)
                holder.txtValueSecondary.text = if (item.batteryLevel != null) "${item.batteryLevel}%" else "--%"
            } else {
                holder.layoutBatteryStack.visibility = View.GONE
            }
        }

        override fun getItemCount() = items.size
        class HeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val txtHeader: TextView = v.findViewById(R.id.txtHeader)
        }
        class DeviceViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val cardRoot: com.google.android.material.card.MaterialCardView = v.findViewById(R.id.cardStatRoot)
            val txtName: TextView = v.findViewById(R.id.txtPrimaryLabel)
            val txtMac: TextView = v.findViewById(R.id.txtSecondaryLabel)
            val txtValue: TextView = v.findViewById(R.id.txtStatValue)
            val txtValueSecondary: TextView = v.findViewById(R.id.txtStatValueSecondary)
            val imgSignal: ImageView = v.findViewById(R.id.imgSignalIcon)
            val imgBattery: ImageView = v.findViewById(R.id.imgBatteryIcon)
            val layoutSignalStack: View = v.findViewById(R.id.layoutSignalStack)
            val layoutBatteryStack: View = v.findViewById(R.id.layoutBatteryStack)
            val layoutLegacyValueStack: View = v.findViewById(R.id.layoutLegacyValueStack)
            val txtLegacyValue: TextView = v.findViewById(R.id.txtLegacyStatValue)
            val viewAccent: View = v.findViewById(R.id.viewConnectionAccent)
        }
    }
}
