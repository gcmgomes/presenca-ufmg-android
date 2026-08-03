package com.example.presensor.controllers.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.controllers.items.BacklogItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class ImportBacklogAdapter : RecyclerView.Adapter<ImportBacklogAdapter.ViewHolder>() {

    private val items = mutableListOf<BacklogItem>()
    private val selectedKeys = mutableSetOf<String>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

    fun addItem(item: BacklogItem, shouldAutoSelect: Boolean = true) {
        // Always insert at the top for descending order
        items.add(0, item)
        // Default new items to selected based on flag
        if (shouldAutoSelect) {
            selectedKeys.add(item.tagId + item.timestamp)
        }
        notifyItemInserted(0)
    }

    fun removeItem(item: BacklogItem) {
        val index = items.indexOf(item)
        if (index != -1) {
            items.removeAt(index)
            selectedKeys.remove(item.tagId + item.timestamp)
            notifyItemRemoved(index)
        }
    }

    fun getSelectedItems(): List<BacklogItem> {
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
