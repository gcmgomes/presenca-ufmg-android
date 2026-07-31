package com.example.presensor.controllers.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.example.presensor.R
import com.example.presensor.controllers.items.ActionItem

class ActionsPageAdapter(
    private val actionItems: List<ActionItem>,
    private val pageTitles: List<String>,
    private val itemsPerPage: Int,
    private val layoutResId: Int,
    private val buttonIds: List<Int>
) : RecyclerView.Adapter<ActionsPageAdapter.ActionsPageViewHolder>() {

    override fun getItemCount(): Int = pageTitles.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActionsPageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutResId, parent, false)
        return ActionsPageViewHolder(view, buttonIds)
    }

    override fun onBindViewHolder(holder: ActionsPageViewHolder, position: Int) {
        holder.txtPageHeader.text = pageTitles.getOrNull(position) ?: ""

        val startIndex = position * itemsPerPage
        for (i in 0 until itemsPerPage) {
            val item = actionItems.getOrNull(startIndex + i)
            holder.bindRow(i, item)
        }
    }

    class ActionsPageViewHolder(itemView: View, buttonIds: List<Int>) : RecyclerView.ViewHolder(itemView) {
        val txtPageHeader: TextView = itemView.findViewById(R.id.txtPageHeader)
        private val buttons: List<MaterialButton?> = buttonIds.map { itemView.findViewById(it) }

        fun bindRow(buttonIndex: Int, item: ActionItem?) {
            val button = buttons.getOrNull(buttonIndex) ?: return
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
