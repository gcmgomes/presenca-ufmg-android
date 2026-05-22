package com.example.presensor.adapters


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.data.entities.Student
import com.example.presensor.R

class ImportStudentAdapter(private val students: List<Student>) :
    RecyclerView.Adapter<ImportStudentAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.txtPreviewStudentName)
        val emailText: TextView = view.findViewById(R.id.txtPreviewStudentEmail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_import_student_preview, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val student = students[position]
        holder.nameText.text = student.name
        holder.emailText.text = student.email
    }

    override fun getItemCount() = students.size
}