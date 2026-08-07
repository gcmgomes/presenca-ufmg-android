package com.example.presensor.controllers

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.viewpager2.widget.ViewPager2
import com.example.presensor.R
import com.example.presensor.MainUiBinder
import com.example.presensor.tools.UiUtils
import com.example.presensor.MainActivity
import com.example.presensor.cloud.DashboardCloudActions
import com.example.presensor.controllers.adapters.ActionsPageAdapter
import com.example.presensor.controllers.items.ActionItem
import com.example.presensor.controllers.providers.DashboardInteractionProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class DashboardController(
    private val activity: MainActivity,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val uiProvider: DashboardInteractionProvider,
    private val cloudSyncController: CloudSyncController,
    private val importStudentController: ImportStudentController,
    private val onCourseSelected: (Course) -> Unit,
    private val onCourseLongClicked: (Course) -> Unit,
    private val onCourseCreateRequested: (() -> Unit) -> Unit,
    private val onCourseEditRequested: (Course) -> Unit,
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) {
    // Safely look up views directly from the Activity context to prevent null reference crashes
    private val container: LinearLayout = activity.findViewById(R.id.currentCoursesContainer)
    private val txtCurrentTerm: TextView = activity.findViewById(R.id.txtCurrentTerm)
    private val searchView: SearchView = activity.findViewById(R.id.courseSearchView)
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

    private val cloudActions = DashboardCloudActions(
        uiProvider = uiProvider,
        cloudSyncController = cloudSyncController,
        importStudentController = importStudentController,
        runWithCloudAuthentication = { action: () -> Unit -> activity.runWithCloudAuthentication(action) },
        refreshDashboard = { refreshDashboard() },
        mainDispatcher = mainDispatcher,
        ioDispatcher = ioDispatcher
    )

    init {
        setupSearchView()
    }

    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                Log.d("", "Aha!")
                refreshDashboard(newText ?: "")
                return true
            }
        })
    }

    fun setupOnClickListeners() {
        activity.findViewById<FloatingActionButton>(R.id.btnCreateCourse)
            .setOnClickListener {
                onCourseCreateRequested {
                    refreshDashboard()
                }
            }
        setupQuickActionsClickListeners()
    }

    private fun setupQuickActionsClickListeners() {
        val viewPager = activity.findViewById<ViewPager2>(R.id.actionsViewPager)
        val tabLayout =
            activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.actionsPageIndicator)

        val actionItems = listOf(
            // Page 1 Elements
            ActionItem(
                activity.getString(R.string.menu_student_import),
                R.drawable.ic_person
            ) {
                triggerStudentImportPicker()
            },
            ActionItem(
                activity.getString(R.string.menu_database_import),
                R.drawable.ic_import
            ) {
                triggerDatabaseImportPicker()
            },
            ActionItem(
                activity.getString(R.string.menu_database_export),
                R.drawable.ic_export
            ) {
                triggerDatabaseExportPicker()
            },

            // Page 2 Elements
            ActionItem(
                activity.getString(R.string.menu_cloud_student_import),
                R.drawable.ic_person
            ) {
                cloudActions.triggerStudentImportCloudPicker()
            },
            ActionItem(
                activity.getString(R.string.menu_cloud_database_import),
                R.drawable.ic_import
            ) {
                cloudActions.triggerDatabaseImportCloudPicker()
            },
            ActionItem(
                activity.getString(R.string.menu_cloud_database_export),
                R.drawable.ic_export
            ) {
                cloudActions.triggerDatabaseExportCloudPicker()
            },


            // Page 3 Elements
            ActionItem(
                "Reader list",
                R.drawable.ic_person
            ) {
                activity.openReaderManagement()
            },


            )

        val pageTitles = listOf(
            activity.getString(R.string.category_local_operations),
            activity.getString(R.string.category_cloud_operations),
            "Reader management"
        )

        viewPager.adapter = ActionsPageAdapter(
            actionItems = actionItems,
            pageTitles = pageTitles,
            itemsPerPage = 3,
            layoutResId = R.layout.item_dashboard_actions_page,
            buttonIds = listOf(R.id.btnRow1, R.id.btnRow2, R.id.btnRow3)
        )

        // Bind the indicators to scroll along with viewpager context
        TabLayoutMediator(tabLayout, viewPager) { _, _ -> }.attach()
    }

    fun setupQuickActionsAccordion() {
        val includedDashboard = activity.findViewById<ViewGroup>(R.id.layoutDashboardView) ?: return
        val expandableLayout =
            includedDashboard.findViewById<LinearLayout>(R.id.layoutActionsContent)
        val arrowIcon = includedDashboard.findViewById<ImageView>(R.id.imgExpandArrow)
        val headerClickArea =
            includedDashboard.findViewById<RelativeLayout>(R.id.layoutDashboardActionsHeader)
        // Reference the ViewPager2 to recalculate its page measures on expand
        val viewPager =
            includedDashboard.findViewById<ViewPager2>(R.id.actionsViewPager)

        headerClickArea.setOnClickListener {
            val animationDuration = 300L
            val transition = TransitionSet().apply {
                addTransition(ChangeBounds())
                addTransition(Fade())
                ordering = TransitionSet.ORDERING_TOGETHER
                duration = animationDuration
                interpolator = FastOutSlowInInterpolator()
            }

            TransitionManager.beginDelayedTransition(includedDashboard, transition)

            if (expandableLayout.isGone) {
                expandableLayout.visibility = View.VISIBLE
                arrowIcon.animate().rotation(180f).setDuration(animationDuration).start()

                // Force the ViewPager to dynamically measure and bind its
                // layout blocks now that its parent visibility is updated
                viewPager?.requestLayout()
            } else {
                expandableLayout.visibility = View.GONE
                arrowIcon.animate().rotation(0f).setDuration(animationDuration).start()
            }
        }
    }

    private val importStudentLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    activity.importStudentController.importFromLocal(uri)
                }
            }
        }

    private val databaseExportLauncher =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { handleDumpUriSelected(it) }
        }


    private val databaseImportLauncher =
        activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleImportUriSelected(it) }
        }

    internal fun triggerStudentImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/comma-separated-values"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/csv", "text/comma-separated-values", "text/plain")
            )
        }
        importStudentLauncher.launch(intent)
    }

    internal fun triggerDatabaseExportPicker() {
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = "Presensor_Backup_$timestamp.csv"

        // Exact match to your standard open/create document picker pattern
        databaseExportLauncher.launch(fileName)
    }


    internal fun handleDumpUriSelected(uri: Uri) {
        activity.lifecycleScope.launch {
            // Open the stream context via ContentResolver safely on a background worker thread
            val success = withContext(ioDispatcher) {
                val outputStream = activity.contentResolver.openOutputStream(uri)
                if (outputStream != null) {
                    db.performFullDatabaseDump(outputStream)
                } else {
                    false
                }
            }

            // Deliver foreground UI notifications based on operation success status
            if (success) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_database_export_success),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_database_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    internal fun triggerDatabaseImportPicker() {
        // Limits the picker visibility to CSV files cleanly
        databaseImportLauncher.launch("text/comma-separated-values")
    }

    internal fun handleImportUriSelected(uri: Uri) {
        activity.lifecycleScope.launch {
            val success = withContext(ioDispatcher) {
                val inputStream = activity.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    db.importFullDatabaseDump(inputStream)
                } else {
                    false
                }
            }

            if (success) {
                Toast.makeText(activity, "Database restored successfully!", Toast.LENGTH_SHORT)
                    .show()
                // Optional callback function hook to notify layout to refresh dashboard states
                refreshDashboard()
            } else {
                Toast.makeText(
                    activity,
                    "Failed to parse or restore backup file",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    fun refreshDashboard(filter: String = "") {
        val calendar = Calendar.getInstance()
        val curYear = calendar.get(Calendar.YEAR)
        val curSemester = if (calendar.get(Calendar.MONTH) < 6) 1 else 2

        // Maps cleanly using layout format ordinal definitions
        val semesterString = if (curSemester == 1) {
            activity.getString(R.string.semester_ordinal_1st)
        } else {
            activity.getString(R.string.semester_ordinal_2nd)
        }
        txtCurrentTerm.text =
            activity.getString(R.string.semester_display_format, curYear, semesterString)

        scope.launch {
            var allCourses = db.getAllCourses()
            if (filter.isNotEmpty()) {
                allCourses = allCourses.filter { it.name.contains(filter, ignoreCase = true) }
            }
            container.removeAllViews()

            val thisSemester =
                allCourses.filter { it.year == curYear && it.semester == curSemester }
            val nextSemester =
                allCourses.filter { (it.year == curYear && it.semester > curSemester) || (it.year > curYear) }
            val previousSemesters =
                allCourses.filter { (it.year == curYear && it.semester < curSemester) || (it.year < curYear) }

            addCoursesToSection(
                activity.getString(R.string.current_semester_head_text),
                thisSemester
            )
            addCoursesToSection(
                activity.getString(R.string.upcoming_semester_head_text),
                nextSemester
            )

            if (previousSemesters.isNotEmpty()) {
                MainUiBinder.addSectionHeader(
                    container,
                    activity.getString(R.string.previous_semester_head_text)
                )
                var lastYear = -1
                previousSemesters.sortedWith(compareByDescending<Course> { it.year }.thenByDescending { it.semester })
                    .forEach { course ->
                        if (course.year != lastYear) {
                            MainUiBinder.addYearDivider(container, course.year.toString())
                        }
                        lastYear = course.year
                        addCourseCardToContainer(course)
                    }
            }
        }
    }

    private fun addCoursesToSection(title: String, courses: List<Course>) {
        if (courses.isNotEmpty()) {
            MainUiBinder.addSectionHeader(container, title)
            courses.forEach { addCourseCardToContainer(it) }
        }
    }

    private fun addCourseCardToContainer(course: Course) {
        val cardView = layoutInflater.inflate(R.layout.item_course_card, container, false)
        cardView.findViewById<View>(R.id.viewCourseAccent)
            .setBackgroundColor(
                UiUtils.getColorForAccent(
                    course.name,
                    activity.resources.obtainTypedArray(R.array.chalk_colors_list)
                )
            )
        cardView.findViewById<TextView>(R.id.txtCourseName).text = course.name

        val semesterString = if (course.semester == 1) {
            activity.getString(R.string.semester_ordinal_1st)
        } else {
            activity.getString(R.string.semester_ordinal_2nd)
        }
        cardView.findViewById<TextView>(R.id.txtCourseDetails).text =
            activity.getString(R.string.semester_display_format, course.year, semesterString)

        cardView.setOnClickListener { onCourseSelected(course) }
        cardView.setOnLongClickListener {
            onCourseLongClicked(course)
            true
        }

        // Inside your Course Adapter's ViewHolder binding block:
        val imgEditCourse = cardView.findViewById<ImageView>(R.id.imgEditCourseDashboard)

        imgEditCourse.setOnClickListener {
            // Trigger the custom long click / edit callback passing the selected course
            onCourseEditRequested(course)
        }
        container.addView(cardView)
    }
}
