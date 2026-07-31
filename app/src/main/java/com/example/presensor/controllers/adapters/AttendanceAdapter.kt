package com.example.presensor.controllers.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.data.entities.AttendanceRecord
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Adapter for displaying attendance records in a session.
 * Uses ListAdapter and DiffUtil for optimized updates.
 */
class AttendanceAdapter(
    config: AsyncDifferConfig<AttendanceRecord>? = null
) : ListAdapter<AttendanceRecord, AttendanceAdapter.ViewHolder>(
    config ?: AsyncDifferConfig.Builder(AttendanceDiffCallback()).build()
) {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtPrimaryLabel)
        val txtEmail: TextView = view.findViewById(R.id.txtSecondaryLabel)
        val txtTime: TextView = view.findViewById(R.id.txtLegacyStatValue)
        val txtDate: TextView = view.findViewById(R.id.txtLegacyStatValueSecondary)
        val viewAccent: View = view.findViewById(R.id.viewConnectionAccent)
        val layoutSignalStack: View = view.findViewById(R.id.layoutSignalStack)
        val layoutBatteryStack: View = view.findViewById(R.id.layoutBatteryStack)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stat_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = getItem(position)
        holder.txtName.text = record.studentName
        holder.txtEmail.text = record.studentEmail

        // Hide technical stacks for student records
        holder.layoutSignalStack.visibility = View.GONE
        holder.layoutBatteryStack.visibility = View.GONE

        val date = java.util.Date(record.timestamp)
        holder.txtTime.text = timeFormat.format(
            java.time.Instant.ofEpochMilli(record.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
        )

        val df = android.text.format.DateFormat.getDateFormat(holder.itemView.context)
        holder.txtDate.text = df.format(date)
        holder.txtDate.visibility = View.VISIBLE
    }

    internal class AttendanceDiffCallback : DiffUtil.ItemCallback<AttendanceRecord>() {
        override fun areItemsTheSame(
            oldItem: AttendanceRecord,
            newItem: AttendanceRecord
        ): Boolean {
            return oldItem.studentEmail == newItem.studentEmail && oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(
            oldItem: AttendanceRecord,
            newItem: AttendanceRecord
        ): Boolean {
            return oldItem == newItem
        }
    }
}
