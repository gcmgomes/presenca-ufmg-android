package com.example.presensor.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.example.presensor.R

data class CourseUtilActionItem(
    val text: String,
    val iconResId: Int,
    val onClick: () -> Unit
)

class CourseUtilsPagerAdapter(
    private val actionItems: List<CourseUtilActionItem>
) : RecyclerView.Adapter<CourseUtilsPagerAdapter.UtilsPageViewHolder>() {

    // 3 pages total (2 items per page for up to 6 items)
    override fun getItemCount(): Int = 3

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UtilsPageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course_utils_page, parent, false)
        return UtilsPageViewHolder(view)
    }

    override fun onBindViewHolder(holder: UtilsPageViewHolder, position: Int) {
        // Set distinct headers for each page window context
        holder.txtPageHeader.text = when (position) {
            0 -> "Course Data & Statistics"
            1 -> "Attendance Export Options"
            else -> "Advanced Course Management"
        }

        // Extract pairs based on chunk windows (Page 0: 0-1, Page 1: 2-3, Page 2: 4-5)
        val startIndex = position * 2
        holder.bindRow(holder.btnRow1, actionItems.getOrNull(startIndex))
        holder.bindRow(holder.btnRow2, actionItems.getOrNull(startIndex + 1))
    }

    class UtilsPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtPageHeader: TextView = itemView.findViewById(R.id.txtPageHeader)
        val btnRow1: MaterialButton = itemView.findViewById(R.id.btnRow1)
        val btnRow2: MaterialButton = itemView.findViewById(R.id.btnRow2)

        fun bindRow(button: MaterialButton, item: CourseUtilActionItem?) {
            if (item != null) {
                button.visibility = View.VISIBLE
                button.text = item.text
                button.setIconResource(item.iconResId)
                button.setOnClickListener { item.onClick() }
            } else {
                button.visibility = View.GONE
            }
        }
    }
}