package com.example.presensor.controllers.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.controllers.items.DeviceItem

class DeviceListAdapter(
    private var onDeviceSelected: (String, String) -> Unit,
    private var onDeviceLongClicked: (String, String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items = mutableListOf<Any>()

    companion object {
        private const val PAYLOAD_RSSI = "PAYLOAD_RSSI"
        private const val PAYLOAD_BATTERY = "PAYLOAD_BATTERY"
        private const val PAYLOAD_TIME = "PAYLOAD_TIME"
        private const val PAYLOAD_STATE = "PAYLOAD_STATE"
    }

    fun updateCallbacks(
        onDeviceSelected: (String, String) -> Unit,
        onDeviceLongClicked: (String, String) -> Unit
    ) {
        this.onDeviceSelected = onDeviceSelected
        this.onDeviceLongClicked = onDeviceLongClicked
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
                // ALWAYS update click listeners to capture the latest item state
                updateClickListeners(holder, item)
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
        updateClickListeners(holder, item)
    }

    private fun updateClickListeners(holder: DeviceViewHolder, item: DeviceItem) {
        holder.itemView.setOnClickListener {
            if (item.isNearby || item.isConnected || item.isConnecting) onDeviceSelected(
                item.name,
                item.address
            )
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
            holder.txtLegacyValue.text =
                holder.itemView.context.getString(R.string.status_connecting)
            holder.layoutSignalStack.visibility = View.GONE
            holder.layoutBatteryStack.visibility = View.GONE
        } else if (isOffline) {
            holder.layoutLegacyValueStack.visibility = View.VISIBLE
            holder.txtLegacyValue.text =
                holder.itemView.context.getString(R.string.status_not_found)
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
            holder.txtValueSecondary.text =
                if (item.batteryLevel != null) "${item.batteryLevel}%" else "--%"
        } else {
            holder.layoutBatteryStack.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size
    class HeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val txtHeader: TextView = v.findViewById(R.id.txtHeader)
    }

    class DeviceViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val cardRoot: com.google.android.material.card.MaterialCardView =
            v.findViewById(R.id.cardStatRoot)
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
