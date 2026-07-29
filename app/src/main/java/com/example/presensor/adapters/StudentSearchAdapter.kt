package com.example.presensor.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.data.entities.Student

/**
 * Adapter for searching students to register manual attendance.
 * Uses the harmonized card UI.
 */
class StudentSearchAdapter(
    private val onStudentSelected: (Student) -> Unit
) : ListAdapter<Student, StudentSearchAdapter.ViewHolder>(StudentDiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.txtPrimaryLabel)
        val emailText: TextView = view.findViewById(R.id.txtSecondaryLabel)
        val statText: TextView = view.findViewById(R.id.txtLegacyStatValue)
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
        val student = getItem(position)
        holder.nameText.text = student.name
        holder.emailText.text = student.email

        // Hide technical stacks
        holder.layoutSignalStack.visibility = View.GONE
        holder.layoutBatteryStack.visibility = View.GONE

        // Hide irrelevant fields for search
        holder.statText.visibility = View.GONE
        holder.dateText.visibility = View.GONE
        
        // Definitively hide the selection accent to prevent dangling artifacts in search
        holder.selectionAccent.visibility = View.GONE

        holder.itemView.setOnClickListener {
            onStudentSelected(student)
        }
    }

    private class StudentDiffCallback : DiffUtil.ItemCallback<Student>() {
        override fun areItemsTheSame(oldItem: Student, newItem: Student) = 
            oldItem.email == newItem.email
        override fun areContentsTheSame(oldItem: Student, newItem: Student) = 
            oldItem == newItem
    }
}
