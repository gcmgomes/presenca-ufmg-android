package com.example.presensor.controllers.providers

import android.view.View
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.adapters.StudentStatsAdapter
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.TimeUtils
import com.example.presensor.tools.UiUtils

class AndroidDetailedCourseInteractionProvider(
    activity: MainActivity
) : BaseAndroidInteractionProvider(activity), DetailedCourseInteractionProvider {

    private var studentStatsAdapter: StudentStatsAdapter? = null

    override fun openDetailedCourseView(
        onEditCourseRequested: () -> Unit,
        onSearchQueryChanged: (String) -> Unit
    ) {
        activity.runOnUiThread {
            val container = activity.findViewById<LinearLayout>(R.id.layoutCourseStatisticsView) ?: return@runOnUiThread
            container.removeAllViews()
            
            val statsView = activity.layoutInflater.inflate(R.layout.layout_course_statistics, container, false)
            container.addView(statsView)

            statsView.findViewById<View>(R.id.btnEditCourse)?.setOnClickListener { onEditCourseRequested() }

            val searchView = statsView.findViewById<androidx.appcompat.widget.SearchView>(R.id.searchStudentsAttendance)
            searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    onSearchQueryChanged(newText ?: "")
                    return true
                }
            })
            
            studentStatsAdapter = null // Reset for new view
        }
    }

    override fun updateDetailedCourseHeader(
        course: Course,
        sessionIds: Set<Long>,
        studentEmails: Set<String>,
        attendance: List<AttendanceRecord>
    ) {
        activity.runOnUiThread {
            val statsView = activity.findViewById<View>(R.id.layoutCourseStatisticsView) ?: return@runOnUiThread
            UiUtils.fillCourseDetailedCardStatistics(
                activity,
                statsView,
                course,
                sessionIds,
                studentEmails,
                attendance
            )
        }
    }

    override fun updateStudentStatsList(
        students: List<Student>,
        allSessions: List<Session>,
        allAttendance: List<AttendanceRecord>,
        getColorFromAttr: (Int) -> Int
    ) {
        activity.runOnUiThread {
            val statsView = activity.findViewById<View>(R.id.layoutCourseStatisticsView) ?: return@runOnUiThread
            val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats) ?: return@runOnUiThread

            if (studentStatsAdapter == null) {
                studentStatsAdapter = StudentStatsAdapter(
                    students,
                    allSessions,
                    allAttendance,
                    allSessions.map { it.id }.toSet(),
                    getColorFromAttr = getColorFromAttr,
                    makeSessionTimeFormatter = { TimeUtils.makeSessionTimeFormatter(activity) },
                    fromMillisToLocalDate = { ms -> TimeUtils.fromMillisToLocalDate(ms) }
                )
                rv.layoutManager = LinearLayoutManager(activity)
                rv.adapter = studentStatsAdapter
            } else {
                studentStatsAdapter?.updateData(students)
            }
        }
    }
}
