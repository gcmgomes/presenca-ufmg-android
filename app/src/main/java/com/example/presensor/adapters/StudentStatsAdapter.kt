package com.example.presensor.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.R

class StudentStatsAdapter(
    private var activeStudents: List<Student>, // Changed from val to var to allow dataset updates
    private val allSessions: List<Session>,
    private val allAttendance: List<AttendanceRecord>,
    private val sessionIds: Set<Long>,
    private val getColorFromAttr: (Int) -> Int,
    private val makeSessionTimeFormatter: () -> DateTimeFormatter,
    private val fromMillisToLocalDate: (Long) -> LocalDate
) : RecyclerView.Adapter<StudentStatsAdapter.StudentViewHolder>() {

    // Persistent storage tracking expanded states across text filter updates
    private val expandedStudentEmails = HashSet<String>()

    inner class StudentViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.txtPrimaryLabel)
        val email: TextView = v.findViewById(R.id.txtSecondaryLabel)
        val container: LinearLayout = v.findViewById(R.id.layoutExpandedContent)
        val percentage: TextView = v.findViewById(R.id.txtStatValue)
        val root: View = v.findViewById(R.id.cardStatRoot)
    }

    // Public API function invoked by the Main UI search loop
    fun updateData(newStudentsList: List<Student>) {
        this.activeStudents = newStudentsList
        notifyDataSetChanged() // Refreshes positions safely while maintaining adapter instance state
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StudentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_student_stat_card, parent, false)
        return StudentViewHolder(view)
    }

    override fun getItemCount() = activeStudents.size

    override fun onBindViewHolder(holder: StudentViewHolder, position: Int) {
        val student = activeStudents[position]
        holder.name.text = student.name
        holder.email.text = student.email

        val studentAttendanceSet = allAttendance.filter { it.studentEmail == student.email }.map { it.sessionId }.distinct()
        holder.percentage.text = if (sessionIds.isNotEmpty()) "${100 * studentAttendanceSet.size / sessionIds.size}%" else "123%"

        // Check if this student was expanded before the search filter updated
        if (expandedStudentEmails.contains(student.email)) {
            populateExpandedSessions(holder, student)
            holder.container.visibility = View.VISIBLE
        } else {
            holder.container.visibility = View.GONE
        }

        holder.root.setOnClickListener {
            if (expandedStudentEmails.contains(student.email)) {
                expandedStudentEmails.remove(student.email)
                holder.container.visibility = View.GONE
            } else {
                expandedStudentEmails.add(student.email)
                populateExpandedSessions(holder, student)
                holder.container.visibility = View.VISIBLE
            }
        }
    }

    private fun populateExpandedSessions(holder: StudentViewHolder, student: Student) {
        holder.container.removeAllViews()
        val inflater = LayoutInflater.from(holder.itemView.context)
        val dateFormat = makeSessionTimeFormatter()

        allSessions.sortedBy { it.id }.forEach { session ->
            val mini = inflater.inflate(R.layout.item_mini_session_stat_card, holder.container, false)
            val miniSessionNameView = mini.findViewById<TextView>(R.id.txtMiniSessionName)
            miniSessionNameView.text = session.name

            if (allAttendance.any { it.studentEmail == student.email && it.sessionId == session.id }) {
                miniSessionNameView.setTextColor(getColorFromAttr(R.attr.studentAttendedClassColor))
            } else {
                miniSessionNameView.setTextColor(getColorFromAttr(R.attr.studentSkippedClassColor))
            }
            mini.findViewById<TextView>(R.id.txtMiniSessionDate).text =
                fromMillisToLocalDate(session.date).format(dateFormat)
            holder.container.addView(mini)
        }
    }
}