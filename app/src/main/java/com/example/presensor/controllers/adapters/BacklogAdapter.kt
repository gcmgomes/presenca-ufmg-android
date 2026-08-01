package com.example.presensor.controllers.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.controllers.items.BacklogItem
import java.text.SimpleDateFormat
import java.util.*

class BacklogAdapter(private val onItemLongClicked: (BacklogItem) -> Unit) :
    RecyclerView.Adapter<BacklogAdapter.ViewHolder>() {
    private var items = mutableListOf<BacklogItem>()

    fun submitList(newItems: List<BacklogItem>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos].tagId == newItems[newPos].tagId && items[oldPos].timestamp == newItems[newPos].timestamp

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos] == newItems[newPos]
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
        holder.txtName.text = item.student?.name
            ?: holder.itemView.context.getString(R.string.label_unknown_student)
        holder.txtTag.text = item.tagId

        // Hide technical stacks for backlog records
        holder.layoutSignalStack.visibility = View.GONE
        holder.layoutBatteryStack.visibility = View.GONE

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
        val txtTime: TextView = v.findViewById(R.id.txtLegacyStatValue)
        val txtDate: TextView = v.findViewById(R.id.txtLegacyStatValueSecondary)
        val layoutSignalStack: View = v.findViewById(R.id.layoutSignalStack)
        val layoutBatteryStack: View = v.findViewById(R.id.layoutBatteryStack)
    }
}
