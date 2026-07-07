package com.example.presensor.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R

data class DashboardActionItem(
    val text: String,
    val iconResId: Int,
    val onClick: () -> Unit
)

class DashboardActionsPagerAdapter(
    private val actionItems: List<DashboardActionItem>
) : RecyclerView.Adapter<DashboardActionsPagerAdapter.PageViewHolder>() {

    override fun getItemCount(): Int = Math.ceil(actionItems.size / 3.0).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dashboard_actions_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val context = holder.itemView.context

        // Dynamically assign page headers based on chunk window position
        holder.txtPageHeader.text = if (position == 0) {
            context.getString(R.string.category_local_operations) // e.g., "Local Device Storage"
        } else {
            context.getString(R.string.category_cloud_operations) // e.g., "Google Cloud Backup"
        }

        val startIndex = position * 3
        holder.bindRow(holder.btnRow1, actionItems.getOrNull(startIndex))
        holder.bindRow(holder.btnRow2, actionItems.getOrNull(startIndex + 1))
        holder.bindRow(holder.btnRow3, actionItems.getOrNull(startIndex + 2))
    }

    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtPageHeader: TextView = itemView.findViewById(R.id.txtPageHeader)
        val btnRow1: Button = itemView.findViewById(R.id.btnRow1)
        val btnRow2: Button = itemView.findViewById(R.id.btnRow2)
        val btnRow3: Button = itemView.findViewById(R.id.btnRow3)

        fun bindRow(button: Button, item: DashboardActionItem?) {
            if (item != null) {
                button.visibility = View.VISIBLE
                button.text = item.text
                button.setCompoundDrawablesWithIntrinsicBounds(item.iconResId, 0, 0, 0)
                button.setOnClickListener { item.onClick() }
            } else {
                button.visibility = View.GONE
            }
        }
    }
}