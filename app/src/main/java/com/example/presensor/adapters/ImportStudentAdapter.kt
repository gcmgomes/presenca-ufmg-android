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
import com.example.presensor.data.entities.Student
import com.example.presensor.R

/**
 * Adapter for student import preview. Uses green accent to signal selection.
 */
class ImportStudentAdapter : ListAdapter<Student, ImportStudentAdapter.ViewHolder>(StudentDiffCallback()) {

    private val selectedEmails = mutableSetOf<String>()

    override fun submitList(list: List<Student>?) {
        super.submitList(list)
        // All items are selected by default on first load
        list?.forEach { selectedEmails.add(it.email) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.txtPrimaryLabel)
        val subText: TextView = view.findViewById(R.id.txtSecondaryLabel)
        val statText: TextView = view.findViewById(R.id.txtStatValue)
        val dateText: TextView = view.findViewById(R.id.txtStatValueSecondary)
        val selectionAccent: View = view.findViewById(R.id.viewConnectionAccent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stat_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val student = getItem(position)
        holder.nameText.text = student.name
        holder.subText.text = student.email
        
        // Hide irrelevant fields for student roster
        holder.statText.visibility = View.GONE
        holder.dateText.visibility = View.GONE

        val isSelected = selectedEmails.contains(student.email)
        holder.selectionAccent.setBackgroundColor(
            if (isSelected) "#4CAF50".toColorInt() else Color.TRANSPARENT
        )
        
        // Dim the entire card and its text when deselected
        val alpha = if (isSelected) 1.0f else 0.5f
        holder.itemView.alpha = alpha
        holder.nameText.alpha = alpha
        holder.subText.alpha = alpha
        holder.statText.alpha = alpha
        holder.dateText.alpha = alpha
        holder.selectionAccent.alpha = alpha

        holder.itemView.setOnClickListener {
            if (selectedEmails.contains(student.email)) {
                selectedEmails.remove(student.email)
            } else {
                selectedEmails.add(student.email)
            }
            notifyItemChanged(position)
        }
    }

    fun getSelectedItems(): List<Student> {
        return currentList.filter { selectedEmails.contains(it.email) }
    }

    private class StudentDiffCallback : DiffUtil.ItemCallback<Student>() {
        override fun areItemsTheSame(oldItem: Student, newItem: Student) = oldItem.email == newItem.email
        override fun areContentsTheSame(oldItem: Student, newItem: Student) = oldItem == newItem
    }
}
