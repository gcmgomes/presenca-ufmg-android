package com.example.presensor.controllers

import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.tools.TimeUtils
import com.example.presensor.tools.UiUtils
import com.example.presensor.R
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.adapters.StudentStatsAdapter
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.AttendanceRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailedCourseController(
    private val activity: AppCompatActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase,
    private val courseController: CourseController,
    private val getColorFromAttr: (Int) -> Int,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

    private lateinit var btnEditCourse: ImageView
    private var currentStatsView: View? = null

    private var activeStudents: List<Student> = emptyList()
    private var allSessions: List<Session> = emptyList()
    private var allAttendance: List<AttendanceRecord> = emptyList()

    /**
     * Inflates the statistics view hierarchy, configures internal components,
     * fills metrics, and returns the view ready to be displayed.
     */
    fun inflateAndSetupStatsView(container: LinearLayout): View {
        val course = courseController.getSelectedCourse() ?: throw IllegalStateException("No course selected")

        // Inflate the view inside the controller context
        val statsView = layoutInflater.inflate(R.layout.layout_course_statistics, container, false)
        currentStatsView = statsView

        btnEditCourse = statsView.findViewById<ImageView>(R.id.btnEditCourse)

        btnEditCourse.setOnClickListener {
            courseController.showEditCourseDialog(course) {
                fetchDataAndRefresh()
            }
        }

        // 1. Setup SearchView Listeners
        val detailedCourseSearchView = statsView.findViewById<SearchView>(R.id.searchStudentsAttendance)
        detailedCourseSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                refreshDetailedCourseUI(newText ?: "")
                return true
            }
        })

        fetchDataAndRefresh()

        return statsView
    }

    /**
     * Performs a full data re-fetch from the database and updates the UI.
     */
    fun fetchDataAndRefresh(filter: String = "") {
        val course = courseController.getSelectedCourse() ?: return
        lifecycleOwner.lifecycleScope.launch(ioDispatcher) {
            val sessions = db.getSessionsByCourse(course.id)
            val attendances = db.getAllAttendanceForCourse(course.id)
            val students = db.getAllStudents()

            val activeEmails = attendances.map { it.studentEmail }.toSet()
            activeStudents = students.filter { it.email in activeEmails }
            allSessions = sessions
            allAttendance = attendances

            withContext(mainDispatcher) {
                val statsView = currentStatsView ?: return@withContext
                val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
                
                // Initialize or update adapter
                if (rv.adapter == null) {
                    rv.layoutManager = LinearLayoutManager(activity)
                    rv.adapter = StudentStatsAdapter(
                        activeStudents,
                        allSessions,
                        allAttendance,
                        allSessions.map { it.id }.toSet(),
                        getColorFromAttr = { attr -> getColorFromAttr(attr) },
                        makeSessionTimeFormatter = { TimeUtils.makeSessionTimeFormatter(activity) },
                        fromMillisToLocalDate = { ms -> TimeUtils.fromMillisToLocalDate(ms) }
                    )
                }
                
                refreshDetailedCourseUI(filter)
            }
        }
    }

    /**
     * Filters the student roster list view dynamically as the instructor types.
     * Uses currently loaded local data.
     */
    fun refreshDetailedCourseUI(filter: String = "") {
        val statsView = currentStatsView ?: return
        val course = courseController.getSelectedCourse() ?: return

        // 3. Bind the Summary Card Data metrics fields
        UiUtils.fillCourseDetailedCardStatistics(
            activity,
            statsView,
            course,
            allSessions.map { it.id }.toSet(),
            activeStudents.map { it.email }.toSet(),
            allAttendance
        )
        
        val filteredStudents = if (filter.isBlank()) {
            activeStudents
        } else {
            activeStudents.filter { it.name.contains(filter, ignoreCase = true) }
        }

        val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
        (rv.adapter as? StudentStatsAdapter)?.updateData(filteredStudents)
    }

    /**
     * Releases view references safely when navigating away to prevent memory leaks.
     */
    fun clear() {
        currentStatsView = null
    }
}