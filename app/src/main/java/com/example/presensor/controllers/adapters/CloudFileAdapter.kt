package com.example.presensor.controllers.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R

class CloudFileAdapter<T>(
    private val items: List<T>,
    private val getName: (T) -> String,
    private val onItemClicked: (T) -> Unit
) : RecyclerView.Adapter<CloudFileAdapter.ViewHolder>() {

    private var filteredItems: List<T> = items

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtFileName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cloud_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filteredItems[position]
        holder.txtName.text = getName(item)
        holder.itemView.setOnClickListener { onItemClicked(item) }
    }

    override fun getItemCount(): Int = filteredItems.size

    fun filter(query: String) {
        filteredItems = if (query.isEmpty()) {
            items
        } else {
            items.filter { getName(it).contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }
}
