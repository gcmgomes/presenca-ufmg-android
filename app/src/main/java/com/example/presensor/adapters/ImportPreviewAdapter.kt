package com.example.presensor.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.presensor.data.entities.Session
import com.example.presensor.R

class ImportPreviewAdapter(private val sessions: List<Session>) :
    RecyclerView.Adapter<ImportPreviewAdapter.ViewHolder>() {

    private val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        .withZone(ZoneId.systemDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.txtSessionName)
        val dateText: TextView = view.findViewById(R.id.txtSessionDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_import_session_preview, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        holder.nameText.text = session.name

        val instant = Instant.ofEpochMilli(session.date)
        holder.dateText.text = formatter.format(instant)
    }

    override fun getItemCount() = sessions.size
}