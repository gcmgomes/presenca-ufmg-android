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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailedCourseController(
    private val activity: AppCompatActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase,
    private val courseController: CourseController,
    private val getColorFromAttr: (Int) -> Int,
) {
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

    private lateinit var btnEditCourse: ImageView
    private var currentStatsView: View? = null

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
                refreshDetailedCourseUI()
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


        // 2. Async Load and Bind RecyclerView Data
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val cache = db.getCourseCache()
            withContext(Dispatchers.Main) {
                val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
                rv.layoutManager = LinearLayoutManager(activity)

                rv.adapter = StudentStatsAdapter(
                    cache.activeStudents,
                    cache.allSessions,
                    cache.allAttendance,
                    cache.sessionIds,
                    getColorFromAttr = { attr -> getColorFromAttr(attr) },
                    makeSessionTimeFormatter = { TimeUtils.makeSessionTimeFormatter(activity) },
                    fromMillisToLocalDate = { ms -> TimeUtils.fromMillisToLocalDate(ms) }
                )
            }
        }

        return statsView
    }

    /**
     * Filters the student roster list view dynamically as the instructor types.
     */
    fun refreshDetailedCourseUI(filter: String = "") {
        val statsView = currentStatsView ?: return


        // 3. Bind the Summary Card Data metrics fields
        val cache = db.getCourseCache()
        UiUtils.fillCourseDetailedCardStatistics(
            activity,
            statsView,
            courseController.getSelectedCourse()!!,
            cache.sessionIds,
            cache.activeStudents.map { it.email }.toSet(),
            cache.allAttendance
        )
        val filteredStudents = db.getCourseCache().getFilteredStudents(filter)
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