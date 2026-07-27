package com.example.presensor.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.presensor.data.entities.Session
import com.example.presensor.R

/**
 * Adapter for session import preview. Uses green accent to signal selection.
 */
class ImportPreviewAdapter : ListAdapter<Session, ImportPreviewAdapter.ViewHolder>(SessionDiffCallback()) {

    private val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        .withZone(ZoneId.systemDefault())

    private val selectedIds = mutableSetOf<String>()

    override fun submitList(list: List<Session>?) {
        super.submitList(list)
        // All items selected by default. Use Name+Date as composite key for preview uniqueness
        list?.forEach { selectedIds.add(it.name + it.date) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.txtPrimaryLabel)
        val dateText: TextView = view.findViewById(R.id.txtSecondaryLabel)
        val statText: TextView = view.findViewById(R.id.txtStatValue)
        val selectionAccent: View = view.findViewById(R.id.viewConnectionAccent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stat_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = getItem(position)
        holder.nameText.text = session.name

        val instant = Instant.ofEpochMilli(session.date)
        holder.dateText.text = formatter.format(instant)
        
        holder.statText.visibility = View.GONE

        val key = session.name + session.date
        val isSelected = selectedIds.contains(key)
        holder.selectionAccent.setBackgroundColor(
            if (isSelected) "#4CAF50".toColorInt() else Color.TRANSPARENT
        )
        
        // Dim the entire card and its text when deselected
        val alpha = if (isSelected) 1.0f else 0.5f
        holder.itemView.alpha = alpha
        holder.nameText.alpha = alpha
        holder.dateText.alpha = alpha
        holder.statText.alpha = alpha
        holder.selectionAccent.alpha = alpha

        holder.itemView.setOnClickListener {
            if (selectedIds.contains(key)) {
                selectedIds.remove(key)
            } else {
                selectedIds.add(key)
            }
            notifyItemChanged(position)
        }
    }

    fun getSelectedItems(): List<Session> {
        return currentList.filter { selectedIds.contains(it.name + it.date) }
    }

    private class SessionDiffCallback : DiffUtil.ItemCallback<Session>() {
        override fun areItemsTheSame(oldItem: Session, newItem: Session) = 
            oldItem.name == newItem.name && oldItem.date == newItem.date
        override fun areContentsTheSame(oldItem: Session, newItem: Session) = oldItem == newItem
    }
}
