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
import com.example.presensor.R
import com.example.presensor.MainUiBinder
import com.example.presensor.MainActivity
import com.example.presensor.CourseUtilities
import com.example.presensor.DialogFactory
import com.example.presensor.MainActivity.AppState
import com.example.presensor.adapters.ImportStudentAdapter
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Student
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
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


    private fun parseStudentCsv(uri: Uri) {
    }


    fun setupQuickActionsAccordion() {
        val includedDashboard = activity.findViewById<ViewGroup>(R.id.layoutDashboardView) ?: return
        val expandableLayout =
            includedDashboard.findViewById<LinearLayout>(R.id.layoutActionsContent)
        val arrowIcon = includedDashboard.findViewById<ImageView>(R.id.imgExpandArrow)
        val headerClickArea =
            includedDashboard.findViewById<RelativeLayout>(R.id.layoutDashboardActionsHeader)

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
        val action = {
            // 1. Instantly turn on the dimmed overlay and spinning wheel layout
            // BEFORE making the network call. This gives immediate visual feedback.
            activity.toggleLoadingOverlay(true)

            // 2. Query Google Drive for the files in the background
            activity.cloudSyncController.fetchAvailableBackups { files ->
                // 3. Keep the overlay visible or drop it based on data arrival,
                // but introduce a tiny post delay to let the overlay settle before drawing the dialog
                activity.window.decorView.post {
                    activity.toggleLoadingOverlay(false)

                    if (files.isEmpty()) {
                        with(DialogFactory) {
                            AlertDialog.Builder(activity)
                                .setTitle("No Backups Found")
                                .setMessage("No valid database backups were discovered on your Google Drive account.")
                                .setPositiveButton("OK", null)
                                .showWithSmartNfcReading()
                        }
                        return@post
                    }

                    // 4. Inflate and show your custom dialog_cloud_import layout
                    val dialogView = layoutInflater.inflate(R.layout.dialog_cloud_import, null)
                    val listView = dialogView.findViewById<ListView>(R.id.backupListView)

                    val fileNames = files.map { it.name }
                    val adapter =
                        ArrayAdapter(activity, android.R.layout.simple_list_item_1, fileNames)
                    listView.adapter = adapter

                    with(DialogFactory) {

                        val importDialog = AlertDialog.Builder(activity)
                            .setTitle(R.string.dialog_cloud_import_title)
                            .setView(dialogView)
                            .setNegativeButton(R.string.action_cancel, null)
                            .showWithSmartNfcReading()

                        listView.setOnItemClickListener { _, _, position, _ ->
                            val selectedFile = files[position]
                            importDialog.dismiss()

                            // Handle download sync restoration
                            activity.cloudSyncController.downloadAndRestoreBackup(
                                selectedFile.id,
                                onLoadingToggle = { isLoading ->
                                    activity.toggleLoadingOverlay(
                                        isLoading
                                    )
                                },
                                onComplete = { success ->
                                    if (success) {
                                        refreshDashboard()
                                    }
                                }
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

    fun setupOnClickListeners() {

        activity.findViewById<Button>(R.id.btnImportStudents)
            .setOnClickListener { triggerStudentImportPicker() }
        activity.findViewById<Button>(R.id.btnExportDatabase)
            .setOnClickListener { triggerDatabaseExportPicker() }
        activity.findViewById<Button>(R.id.btnImportDatabase)
            .setOnClickListener { triggerDatabaseImportPicker() }
        activity.findViewById<FloatingActionButton>(R.id.btnCreateCourse)
            .setOnClickListener {
                onCourseCreateRequested {
                    refreshDashboard()
                }
            }

        activity.findViewById<Button>(R.id.btnDriveImportStudents).setOnClickListener {
            // 1. Immediately overlay to keep user animations stable
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

                    // Show Spreadsheet selection dialog using layout_cloud_import
                    val dialogView = layoutInflater.inflate(R.layout.dialog_cloud_import, null)
                    val subTitle = dialogView.findViewById<TextView>(R.id.dialogSubtitle)
                    val listView = dialogView.findViewById<ListView>(R.id.backupListView)

                    subTitle.text = activity.getString(R.string.dialog_cloud_import_sheets_subtitle)
                    listView.adapter = ArrayAdapter(
                        activity,
                        android.R.layout.simple_list_item_1,
                        spreadsheets.map { it.name })

                    val spreadsheetDialog = AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.dialog_cloud_import_sheets_title))
                        .setView(dialogView)
                        .setNegativeButton(activity.getString(R.string.action_cancel), null)
                        .create()

                    listView.setOnItemClickListener { _, _, position, _ ->
                        val selectedSpreadsheet = spreadsheets[position]
                        spreadsheetDialog.dismiss()

                        // Chain 2: Fetch tab segments from inside that workbook
                        activity.toggleLoadingOverlay(true)
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

                            // Show tab selection dialog frame
                            val tabsView =
                                layoutInflater.inflate(R.layout.dialog_cloud_import_tabs, null)
                            val tabsListView = tabsView.findViewById<ListView>(R.id.tabsListView)
                            tabsListView.adapter =
                                ArrayAdapter(activity, android.R.layout.simple_list_item_1, tabs)

                            val tabsDialog = AlertDialog.Builder(activity)
                                .setTitle(activity.getString(R.string.dialog_cloud_select_tab_title))
                                .setView(tabsView)
                                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                                .create()

                            tabsListView.setOnItemClickListener { _, _, tabPos, _ ->
                                val selectedTab = tabs[tabPos]
                                tabsDialog.dismiss()

                                // Chain 3: Pull rows data values down securely
                                activity.toggleLoadingOverlay(true)
                                ImportStudentController.importFromCloud(
                                    activity,
                                    activity.cloudSyncController.getSheetsService(),
                                    selectedSpreadsheet.id,
                                    selectedTab
                                )
                            }
                            tabsDialog.show()
                        }
                    }
                    spreadsheetDialog.show()
                }
            }

            activity.setPendingAction(action)
            activity.cloudSyncController.runWithCloudAuthentication(
                activity.cloudSignInLauncher,
                action
            )
        }

        activity.findViewById<Button>(R.id.btnDriveExportDatabase).setOnClickListener {
            val dialogView = layoutInflater.inflate(R.layout.dialog_cloud_export, null)
            val suffixInput = dialogView.findViewById<EditText>(R.id.editExportSuffix)

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


        activity.findViewById<Button>(R.id.btnDriveImportDatabase).setOnClickListener {
            activity.toggleLoadingOverlay(true)
            triggerCustomImportFlow()
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