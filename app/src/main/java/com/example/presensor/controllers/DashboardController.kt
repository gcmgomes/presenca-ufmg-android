package com.example.presensor.controllers

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.viewpager2.widget.ViewPager2
import com.example.presensor.R
import com.example.presensor.MainUiBinder
import com.example.presensor.MainActivity
import com.example.presensor.CourseUtilities
import com.example.presensor.DialogFactory
import com.example.presensor.MainActivity.AppState
import com.example.presensor.adapters.DashboardActionItem
import com.example.presensor.adapters.DashboardActionsPagerAdapter
import com.example.presensor.adapters.ImportStudentAdapter
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Student
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class DashboardController(
    private val activity: MainActivity,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val onCourseSelected: (Course) -> Unit,
    private val onCourseLongClicked: (Course) -> Unit,
    private val onCourseCreateRequested: (() -> Unit) -> Unit,
    private val onCourseEditRequested: (Course) -> Unit,
) {
    // Safely look up views directly from the Activity context to prevent null reference crashes
    private val container: LinearLayout = activity.findViewById(R.id.currentCoursesContainer)
    private val txtCurrentTerm: TextView = activity.findViewById(R.id.txtCurrentTerm)
    private val searchView: SearchView = activity.findViewById(R.id.courseSearchView)
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

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

    private fun triggerStudentImportCloudPicker() {
        activity.toggleLoadingOverlay(true)

        val action = {
            activity.cloudSyncController.fetchAvailableSpreadsheets { spreadsheets ->
                activity.toggleLoadingOverlay(false)

                if (spreadsheets.isEmpty()) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_cloud_sheets_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@fetchAvailableSpreadsheets
                }

                // 1. Show the searchable Spreadsheet file dialog
                activity.cloudSyncController.showCloudFileDialog(
                    title = activity.getString(R.string.dialog_cloud_import_sheets_title),
                    subtitle = activity.getString(R.string.dialog_cloud_import_sheets_subtitle),
                    driveItems = spreadsheets,
                    getName = { it.name }
                ) { selectedSpreadsheet ->

                    activity.toggleLoadingOverlay(true)

                    // 2. Query for internal workbook worksheet tabs
                    activity.cloudSyncController.fetchSpreadsheetTabs(selectedSpreadsheet.id) { tabs ->
                        activity.toggleLoadingOverlay(false)

                        if (tabs.isEmpty()) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.toast_cloud_sheet_tabs_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@fetchSpreadsheetTabs
                        }

                        // 3. REUSED: Show the exact same searchable file dialog structure for tabs!
                        activity.cloudSyncController.showCloudFileDialog(
                            title = activity.getString(R.string.dialog_cloud_select_tab_title),
                            subtitle = activity.getString(R.string.dialog_cloud_select_tab_subtitle),
                            driveItems = tabs,
                            getName = { it } // Since items are already Strings, just return itself
                        ) { selectedTab ->

                            // 4. Trigger background sheet data collection ingestion
                            activity.toggleLoadingOverlay(true)
                            ImportStudentController.importFromCloud(
                                activity,
                                activity.cloudSyncController.getSheetsService(),
                                selectedSpreadsheet.id,
                                selectedTab
                            )
                        }
                    }
                }
            }
        }

        activity.setPendingAction(action)
        activity.cloudSyncController.runWithCloudAuthentication(
            activity.cloudSignInLauncher,
            action
        )
    }

    private fun triggerDatabaseExportCloudPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cloud_export, null)
        val suffixInput = dialogView.findViewById<EditText>(R.id.editExportSuffix)
        dialogView.findViewById<TextView>(R.id.textExportPrefixPreview).text =
            activity.getString(R.string.dialog_cloud_export_prefix_preview) + " " + activity.getString(
                R.string.dialog_cloud_backup_prefix
            )

        with(DialogFactory) {
            // 2. Build the interactive container before authorizing to match workflow steps
            AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_cloud_export_title)
                .setView(dialogView)
                .setPositiveButton(R.string.action_export) { _, _ ->
                    val inputSuffix = suffixInput.text.toString()

                    // 3. Lock layout screens smoothly to prevent interaction flickering rules
                    activity.toggleLoadingOverlay(true)

                    val action = {
                        // Run the synchronized cloud task passing the customized suffix label
                        activity.cloudSyncController.uploadBackupToDrive(inputSuffix) { isLoading ->
                            activity.toggleLoadingOverlay(isLoading)
                        }
                    }

                    activity.setPendingAction(action)
                    activity.cloudSyncController.runWithCloudAuthentication(
                        activity.cloudSignInLauncher,
                        action
                    )
                }
                .setNegativeButton(R.string.action_cancel, null)
                .showWithSmartNfcReading()
        }
    }

    private fun triggerDatabaseImportCloudPicker() {
        activity.toggleLoadingOverlay(true)
        triggerCustomImportFlow()
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
            DashboardActionItem(
                activity.getString(R.string.menu_student_import),
                R.drawable.ic_person
            ) {
                triggerStudentImportPicker()
            },
            DashboardActionItem(
                activity.getString(R.string.menu_database_import),
                R.drawable.ic_import
            ) {
                triggerDatabaseImportPicker()
            },
            DashboardActionItem(
                activity.getString(R.string.menu_database_export),
                R.drawable.ic_export
            ) {
                triggerDatabaseExportPicker()
            },

            // Page 2 Elements
            DashboardActionItem(
                activity.getString(R.string.menu_cloud_student_import),
                R.drawable.ic_person
            ) {
                triggerStudentImportCloudPicker()
            },
            DashboardActionItem(
                activity.getString(R.string.menu_cloud_database_import),
                R.drawable.ic_import
            ) {
                triggerDatabaseImportCloudPicker()
            },
            DashboardActionItem(
                activity.getString(R.string.menu_cloud_database_export),
                R.drawable.ic_export
            ) {
                triggerDatabaseExportCloudPicker()
            }
        )

        viewPager.adapter = DashboardActionsPagerAdapter(actionItems)

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
                    ImportStudentController.importFromLocal(
                        activity,
                        uri
                    )
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

    private fun triggerStudentImportPicker() {
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

    private fun triggerDatabaseExportPicker() {
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = "Presensor_Backup_$timestamp.csv"

        // Exact match to your standard open/create document picker pattern
        databaseExportLauncher.launch(fileName)
    }


    private fun handleDumpUriSelected(uri: Uri) {
        activity.lifecycleScope.launch {
            // Open the stream context via ContentResolver safely on a background worker thread
            val success = withContext(Dispatchers.IO) {
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

    private fun triggerDatabaseImportPicker() {
        // Limits the picker visibility to CSV files cleanly
        databaseImportLauncher.launch("text/comma-separated-values")
    }

    private fun handleImportUriSelected(uri: Uri) {
        activity.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
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


    fun triggerCustomImportFlow() {
        activity.toggleLoadingOverlay(true)

        val action = {
            activity.cloudSyncController.fetchAvailableBackups { files ->
                activity.toggleLoadingOverlay(false)

                if (files.isEmpty()) {
                    with(DialogFactory) {
                        AlertDialog.Builder(activity)
                            .setTitle("No Backups Found")
                            .setMessage("No valid database backups were discovered on your Google Drive account.")
                            .setPositiveButton("OK", null)
                            .showWithSmartNfcReading()
                    }
                    return@fetchAvailableBackups
                }

                // Call the unified dialog framework
                activity.cloudSyncController.showCloudFileDialog(
                    title = activity.getString(R.string.dialog_cloud_import_title),
                    subtitle = activity.getString(R.string.dialog_cloud_import_subtitle),
                    driveItems = files,
                    getName = { it.name }
                ) { selectedFile ->

                    // Handle download sync restoration
                    activity.cloudSyncController.downloadAndRestoreBackup(
                        selectedFile.id,
                        onLoadingToggle = { isLoading -> activity.toggleLoadingOverlay(isLoading) },
                        onComplete = { success -> if (success) refreshDashboard() }
                    )
                }
            }
        }

        activity.setPendingAction(action)
        activity.cloudSyncController.runWithCloudAuthentication(
            activity.cloudSignInLauncher,
            action
        )
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
            .setBackgroundColor(getColorForAccent(course.name))
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

    private fun getColorForAccent(courseName: String): Int {
        val typedArray = activity.resources.obtainTypedArray(R.array.chalk_colors_list)
        val colors = IntArray(typedArray.length())
        for (i in 0 until typedArray.length()) {
            colors[i] = typedArray.getColor(i, 0)
        }
        typedArray.recycle()
        return colors[Math.abs(courseName.hashCode()) % colors.size]
    }
}