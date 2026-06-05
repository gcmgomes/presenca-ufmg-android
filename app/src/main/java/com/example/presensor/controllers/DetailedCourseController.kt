package com.example.presensor.controllers

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.CourseUtilities
import com.example.presensor.R
import com.example.presensor.adapters.StudentStatsAdapter
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

class DetailedCourseController(
    private val activity: AppCompatActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase,
    private val layoutInflater: LayoutInflater,
    private val container: LinearLayout,
    private val courseController: CourseController,
    private val getColorFromAttr: (Int) -> Int,
    private val onToggleViewsRequested: (
        layoutDashboardView: Boolean,
        layoutCourseView: Boolean,
        layoutSessionView: Boolean,
        layoutCourseStatisticsView: Boolean
    ) -> Unit
) {

    private var currentStatsView: View? = null

    fun openCourseStatistics() {
        val course = courseController.getSelectedCourse() ?: return

        container.removeAllViews()
        onToggleViewsRequested(false, false, false, false)

        val statsView = layoutInflater.inflate(R.layout.layout_course_statistics, container, false)
        container.addView(statsView)

        setupDetailedCourseView(statsView)
        refreshCourseAttendanceList()

        courseController.fillCourseDetailedCardStatistics(
            statsView,
            course,
            db.getCourseCache().sessionIds,
            db.getCourseCache().activeStudents.map { it.email }.toSet(),
            db.getCourseCache().allAttendance
        )

        onToggleViewsRequested(false, false, false, true)
    }

    private fun setupDetailedCourseView(statsView: View) {
        currentStatsView = statsView

        val detailedCourseSearchView =
            statsView.findViewById<SearchView>(R.id.searchStudentsAttendance)
        detailedCourseSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                refreshCourseAttendanceList(newText ?: "")
                return true
            }
        })

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
                rv.layoutManager = LinearLayoutManager(activity)

                rv.adapter = StudentStatsAdapter(
                    db.getCourseCache().activeStudents,
                    db.getCourseCache().allSessions,
                    db.getCourseCache().allAttendance,
                    db.getCourseCache().sessionIds,
                    getColorFromAttr = { attr -> getColorFromAttr(attr) },
                    makeSessionTimeFormatter = { CourseUtilities.makeSessionTimeFormatter(activity) },
                    fromMillisToLocalDate = { ms -> CourseUtilities.fromMillisToLocalDate(ms) })
            }
        }
    }

    fun refreshCourseAttendanceList(filter: String = "") {
        val statsView = currentStatsView ?: return
        val filteredStudents = db.getCourseCache().getFilteredStudents(filter)
        val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
        (rv.adapter as? StudentStatsAdapter)?.updateData(filteredStudents)
    }

    fun loadDetailedCourseData() {
        val course = courseController.getSelectedCourse() ?: return
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val nowMillis =
                LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val sessionsDeferred = async(Dispatchers.IO) { db.dao().getSessionsByCourse(course.id) }
            val allSessions = sessionsDeferred.await().filter { it.date <= nowMillis }
            val sessionIds = allSessions.map { it.id }.toSet()

            val attendanceDeferred = async(Dispatchers.IO) {
                db.dao().getAllAttendanceForCourse(course.id)
            }
            val allAttendance = attendanceDeferred.await()

            val attendeeEmails = allAttendance.map { it.studentEmail }.distinct()
            val activeStudents = db.dao().getAllStudents().filter { it.email in attendeeEmails }

            db.getCourseCache().courseId = course.id
            db.getCourseCache().activeStudents = activeStudents
            db.getCourseCache().activeStudentEmails = attendeeEmails.toSet()
            db.getCourseCache().allSessions = allSessions
            db.getCourseCache().allAttendance = allAttendance
            db.getCourseCache().sessionIds = sessionIds
        }
    }

    fun clear() {
        currentStatsView = null
    }
}