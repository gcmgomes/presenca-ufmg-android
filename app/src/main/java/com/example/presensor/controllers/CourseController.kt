package com.example.presensor.controllers

import android.app.Activity.RESULT_OK
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.R
import com.example.presensor.CourseUtilities
import com.example.presensor.DialogFactory
import com.example.presensor.MainActivity
import com.example.presensor.MainUiBinder
import com.example.presensor.adapters.ImportPreviewAdapter
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.AttendanceRecord
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class CourseController(
    private val activity: MainActivity,
    private val lifecycleOwner: LifecycleOwner,
    private var selectedCourse: Course?,
    private val db: AppDatabase,
    private val onSessionSelected: (Session) -> Unit,
    private val onToggleLockRequested: (Session, ImageView) -> Unit,
    private val onEditSessionRequested: (Session, ImageView) -> Unit,
    private val onOpenStatistics: () -> Unit,
) {
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

    // Layout view boundaries inside layoutCourseView
    private val sessionContainer: LinearLayout = activity.findViewById(R.id.sessionContainer)
    private val utilsViewPager: androidx.viewpager2.widget.ViewPager2 = activity.findViewById(R.id.utilsViewPager)
    private val utilsPageIndicator: com.google.android.material.tabs.TabLayout = activity.findViewById(R.id.utilsPageIndicator)
    private val btnCourseStats: View? = null // Safely delete or decouple raw button reference tracking lines
    private val btnImportSchedule: View? = null
    private val btnExportCourse: View? = null
    private val btnMassDateChange: View? = null

    private val btnEditCourse: ImageView = activity.findViewById<ImageView>(R.id.btnEditCourse)

    private val importSessionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri -> importSessionsFromCsv(uri, selectedCourse!!.id) }
            }
        }

    private val exportLauncher =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { performExport(it) }
        }

    init {
        setupCourseUtilsAccordion()
        setupOnClickListeners()
    }

    fun getSelectedCourse(): Course? {
        return selectedCourse
    }

    private fun setSelectedCourse(course: Course?) {
        selectedCourse = course
    }

    fun prepare(course: Course?): Job? {
        if (course == null) return null

        setSelectedCourse(course)

        btnEditCourse.setOnClickListener {
            showEditCourseDialog(selectedCourse!!) {
                refreshCourseUI()
            }
        }

        // Return the Job reference to the caller
        return lifecycleOwner.lifecycleScope.launch {
            db.loadSessionsForCourse(course)
        }
    }

    fun clear() {
        selectedCourse = null
    }

    fun showCreateSessionDialog() {
        val courseId = getSelectedCourse()!!.id
        lifecycleOwner.lifecycleScope.launch {
            val count = db.getSessionsByCourse(courseId).size + 1
            withContext(Dispatchers.Main) {
                // Resolved using layout string formatting token parameters securely
                val sessionPlaceholder = activity.getString(R.string.session_text) + " $count"
                DialogFactory.showCreateSessionDialog(
                    context = activity,
                    layoutInflater = layoutInflater,
                    fragmentManager = activity.supportFragmentManager,
                    defaultSessionName = sessionPlaceholder,
                    onSessionCreated = { sessionName, dateMillis ->
                        addSession(
                            courseId, sessionName, CourseUtilities.fromMillisToLocalDate(dateMillis)
                                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        )
                    }
                )
            }
        }
    }


    private fun setupOnClickListeners() {
        // A single sequential list containing all 5 operations distributed in pairs
        val allActions = listOf(
            // PAGE 1 Items
            com.example.presensor.adapters.CourseUtilActionItem(
                text = activity.getString(R.string.menu_course_statistics),
                iconResId = R.drawable.ic_view,
                onClick = { onOpenStatistics() }
            ),
            com.example.presensor.adapters.CourseUtilActionItem(
                text = activity.getString(R.string.menu_course_import),
                iconResId = R.drawable.ic_import,
                onClick = { triggerImportSessionPicker() }
            ),

            // PAGE 2 Items
            com.example.presensor.adapters.CourseUtilActionItem(
                text = "Save Attendance as Local CSV",
                iconResId = R.drawable.ic_export,
                onClick = {
                    val courseName = txtDetailCourseNameText()
                    val fileName = "Attendance_${courseName.replace(" ", "_")}.csv"
                    exportLauncher.launch(fileName)
                }
            ),
            com.example.presensor.adapters.CourseUtilActionItem(
                text = "Sync Matrix to Google Sheet",
                iconResId = R.drawable.ic_export, // or your cloud icon
                onClick = { triggerCloudAttendanceExport() }
            ),

            // PAGE 3 Items
            com.example.presensor.adapters.CourseUtilActionItem(
                text = activity.getString(R.string.menu_course_postpone),
                iconResId = R.drawable.ic_postpone,
                onClick = { showMassDateChangeDialog() }
            )
        )

        // Bind your actions to the updated adapter
        utilsViewPager.adapter = com.example.presensor.adapters.CourseUtilsPagerAdapter(allActions)

        // Re-attach mediator to seamlessly update the 3 dots indicator
        com.google.android.material.tabs.TabLayoutMediator(utilsPageIndicator, utilsViewPager) { _, _ -> }.attach()
    }

    private fun txtDetailCourseNameText(): String {
        val txtName = activity.findViewById<TextView>(R.id.txtDetailCourseName)
        return txtName?.text?.toString()
            ?: activity.getString(R.string.filename_attendance_fallback)
    }

    fun addSession(courseId: Long, sessionName: String, date: Long) {
        lifecycleOwner.lifecycleScope.launch {
            db.insertSession(courseId, sessionName, date)
            refreshCourseUI()
        }
    }

    fun refreshCourseUI() {
        selectedCourse?.let {
            val sessionList = runBlocking {
                db.getSessionsByCourse(selectedCourse!!.id)
            }
            val attendanceList = runBlocking {
                db.getAllAttendanceForCourse(selectedCourse!!.id)
            }
            refreshSessionsList(sessionList)
            val layoutCourseView = activity.findViewById<View>(R.id.layoutCourseView)
            CourseUtilities.fillCourseDetailedCardStatistics(
                activity,
                layoutCourseView,
                selectedCourse!!,
                sessionList.map { it.id }.toSet(),
                attendanceList.map{it.studentEmail}.toSet(),
                attendanceList
            )
        }
    }

    private fun refreshSessionsList(sessions: List<Session>) {
        sessionContainer.removeAllViews()

        val thisWeekSessions = sessions.filter {
            CourseUtilities.isDateInCurrentWeek(
                CourseUtilities.fromMillisToLocalDate(it.date)
            )
        }
        val upcomingSessions = sessions.filter {
            !CourseUtilities.isDateInCurrentWeek(
                CourseUtilities.fromMillisToLocalDate(it.date)
            ) && CourseUtilities.fromMillisToLocalDate(it.date).isAfter(LocalDate.now())
        }
        val pastSessions = sessions.filter {
            !CourseUtilities.isDateInCurrentWeek(
                CourseUtilities.fromMillisToLocalDate(it.date)
            ) && CourseUtilities.fromMillisToLocalDate(it.date).isBefore(LocalDate.now())
        }.sortedBy { it.date }

        // Localized structural header elements matching layout boundaries cleanly
        addSessionsToCourseView(
            activity.getString(R.string.current_week_session_head_text),
            thisWeekSessions
        )
        addSessionsToCourseView(
            activity.getString(R.string.upcoming_sessions_head_text),
            upcomingSessions
        )
        addSessionsToCourseView(
            activity.getString(R.string.previous_sessions_head_text),
            pastSessions
        )
    }

    private fun addSessionsToCourseView(title: String, sessions: List<Session>) {
        if (sessions.isNotEmpty()) {
            MainUiBinder.addSectionHeader(sessionContainer, title)
            sessions.forEach { addSessionCardToContainer(it) }
        }
    }

    fun showMassDateChangeDialog() {
        val context = activity
        val dialogView = layoutInflater.inflate(R.layout.dialog_date_change_sessions, null)

        val edtThresholdDate = dialogView.findViewById<EditText>(R.id.edtThresholdDate)
        val edtNewStartDate = dialogView.findViewById<EditText>(R.id.edtNewStartDate)

        var thresholdTimestamp: Long? = null
        var newStartTimestamp: Long? = null

        val dateFormatter = CourseUtilities.makeSessionTimeFormatter(activity)

        // Helper to launch standard Material Date Picker natively
        val attachDatePicker = { editText: EditText, onDateSelected: (Long) -> Unit ->
            editText.setOnClickListener {
                val builder =
                    com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
                builder.setTitleText(editText.hint)
                val picker = builder.build()
                picker.addOnPositiveButtonClickListener { selection ->
                    onDateSelected(selection)
                    editText.setText(
                        CourseUtilities.fromMillisToLocalDate(selection).format(dateFormatter)
                    )
                }
                picker.show(activity.supportFragmentManager, "MASS_DATE_PICKER")
            }
        }

        attachDatePicker(edtThresholdDate) { thresholdTimestamp = it }
        attachDatePicker(edtNewStartDate) { newStartTimestamp = it }
        with(DialogFactory) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.menu_course_postpone))
                .setView(dialogView)
                .setPositiveButton(context.getString(R.string.action_save), null)
                .setNegativeButton(context.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()


            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val currentThreshold = thresholdTimestamp
                val currentNewStart = newStartTimestamp

                if (currentThreshold == null) {
                    edtThresholdDate.error = context.getString(R.string.error_empty_date)
                    return@setOnClickListener
                }
                if (currentNewStart == null) {
                    edtNewStartDate.error = context.getString(R.string.error_empty_date)
                    return@setOnClickListener
                }

                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // 1. Fetch all course sessions matching criteria ordered by timeline execution sequence
                        val targetedSessions = db.getSessionsByCourse(selectedCourse!!.id)
                            .filter { it.date >= currentThreshold }
                            .sortedBy { it.date }

                        if (targetedSessions.isNotEmpty()) {
                            // 2. Compute timeline displacement delta using milliseconds interval length differences
                            val originalBaseDate = targetedSessions.first().date
                            val deltaOffset = currentNewStart - originalBaseDate

                            // 3. Mutate timeline states sequentially shifting each matched course session item
                            targetedSessions.forEach { session ->
                                val updatedSession = session.copy(
                                    date = session.date + deltaOffset
                                )
                                db.updateSession(updatedSession)
                            }
                        }
                    }

                    // 4. Update view architecture tree indicators immediately back in the foreground main execution block
                    refreshCourseUI()
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_sessions_updated_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showDeleteSessionDialog(session: Session) {
        DialogFactory.showDestructiveDeleteDialog(
            context = activity,
            title = activity.getString(R.string.dialog_delete_session_title),
            message = activity.getString(R.string.dialog_delete_session_message, session.name),
            onConfirmed = {
                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.deleteSession(session)
                    }
                    refreshCourseUI()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_session_properties_modified),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun addSessionCardToContainer(session: Session) {
        val itemView = layoutInflater.inflate(R.layout.item_session_card, sessionContainer, false)
        val dateFormat = CourseUtilities.makeSessionTimeFormatter(activity)

        val imgLock = itemView.findViewById<ImageView>(R.id.imgSessionLockOnSessionView)
        itemView.findViewById<View>(R.id.viewSessionAccent)
            .setBackgroundColor(
                CourseUtilities.getColorForAccent(
                    session.name,
                    activity.resources.obtainTypedArray(R.array.chalk_colors_list)
                )
            )
        itemView.findViewById<TextView>(R.id.txtSessionName).text = session.name
        itemView.findViewById<TextView>(R.id.txtSessionDetails).text =
            CourseUtilities.fromMillisToLocalDate(session.date).format(dateFormat)

        CourseUtilities.updateLockIconUI(session.isLocked, imgLock)
        imgLock.setOnClickListener { onToggleLockRequested(session, imgLock) }

        val editBtn = itemView.findViewById<ImageView>(R.id.btnEditSession)
        CourseUtilities.updateEditIconUI(session.isLocked, editBtn)
        editBtn.setOnClickListener { onEditSessionRequested(session, editBtn) }

        itemView.setOnClickListener { onSessionSelected(session) }
        itemView.setOnLongClickListener {
            showDeleteSessionDialog(session)
            true
        }
        sessionContainer.addView(itemView)
    }

    private fun setupCourseUtilsAccordion() {
        val cardRoot = activity.findViewById<LinearLayout>(R.id.layoutInnerCourseView)
        val expandableContent = activity.findViewById<LinearLayout>(R.id.layoutUtilsContent)
        val arrowIcon = activity.findViewById<ImageView>(R.id.imgUtilsExpandIcon)
        val headerClickArea = activity.findViewById<RelativeLayout>(R.id.layoutUtilsHeader)

        if (cardRoot == null || arrowIcon == null || expandableContent == null) return

        headerClickArea.setOnClickListener {
            val animationDuration = 300L
            val transition = TransitionSet().apply {
                addTransition(ChangeBounds())
                addTransition(Fade())
                ordering = TransitionSet.ORDERING_TOGETHER
                duration = animationDuration
                interpolator = FastOutSlowInInterpolator()
            }

            TransitionManager.beginDelayedTransition(cardRoot, transition)

            if (expandableContent.isGone) {
                expandableContent.visibility = View.VISIBLE
                arrowIcon.animate().rotation(180f).setDuration(animationDuration).start()
            } else {
                expandableContent.visibility = View.GONE
                arrowIcon.animate().rotation(0f).setDuration(animationDuration).start()
            }
        }
    }

    private fun showImportPreview(sessions: List<Session>) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = layoutInflater.inflate(R.layout.layout_import_session_preview, null)
        bottomSheet.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvImportPreview)
        val txtImportCount = view.findViewById<TextView>(R.id.txtImportCount)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmImport)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = ImportPreviewAdapter(sessions)

        // Maps to: <string name="dialog_import_sessions_hint">Found %1$d sessions. Please verify the dates.</string>
        txtImportCount.text =
            activity.getString(R.string.dialog_import_sessions_hint, sessions.size)
        btnConfirm.text = activity.getString(R.string.dialog_import_sessions_button_text)

        btnConfirm.setOnClickListener {
            activity.lifecycleScope.launch {
                db.insertSessions(sessions)
                bottomSheet.dismiss()
                refreshCourseUI()

                // Maps to: <string name="toast_imported_sessions">Imported %1$d sessions</string>
                val toastMsg = activity.getString(R.string.toast_imported_sessions, sessions.size)
                Toast.makeText(activity, toastMsg, Toast.LENGTH_SHORT).show()
            }
        }
        bottomSheet.show()
    }

    fun showCreateCourseDialog(onCourseCreated: () -> Unit) {
        val context = activity
        // Inflate your newly created layout file
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_add_course, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtCourseName)
        val edtYear = dialogView.findViewById<EditText>(R.id.edtCourseYear)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

        // Pre-fill the current calendar year as a smart default value
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        edtYear.setText(currentYear.toString())

        with(DialogFactory) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.title_new_course))
                .setView(dialogView)
                .setPositiveButton(
                    context.getString(R.string.action_create),
                    null
                ) // Set null to manually override closure behaviour for validation checks
                .setNegativeButton(context.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()


            // Override the Positive Button click directly to handle field data validations
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val courseName = edtName.text.toString().trim()
                val yearRaw = edtYear.text.toString().trim()

                // Safely extract selection directly out of the matched localized resource items string array
                val selectedSemester = spinnerSemester.selectedItem.toString().toIntOrNull() ?: 1

                if (courseName.isEmpty()) {
                    edtName.error = context.getString(R.string.error_empty_name)
                    return@setOnClickListener
                }

                val parsedYear = yearRaw.toIntOrNull()
                if (parsedYear == null || yearRaw.isEmpty()) {
                    edtYear.error = context.getString(R.string.label_year)
                    return@setOnClickListener
                }

                // Run insertion logic on background coroutine worker pool thread safely
                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val newCourse = Course(
                            name = courseName,
                            year = parsedYear,
                            semester = selectedSemester
                        )
                        db.insertCourse(newCourse)
                    }
                    onCourseCreated()
                    dialog.dismiss()
                }
            }
        }
    }


    fun showEditCourseDialog(course: Course, onCourseEdited: () -> Unit) {
        val context = activity
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_add_course, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtCourseName)
        val edtYear = dialogView.findViewById<EditText>(R.id.edtCourseYear)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

        // 1. Pre-populate fields with existing course values
        edtName.setText(course.name)
        edtYear.setText(course.year.toString())

        // Position spinner to match the course's active semester (1 or 2)
        val semesterIndex = if (course.semester == 2) 1 else 0
        spinnerSemester.setSelection(semesterIndex)

        // 2. Build and display the Material / AlertDialog template
        with(DialogFactory) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.title_edit_course))
                .setView(dialogView)
                .setPositiveButton(
                    context.getString(R.string.action_save),
                    null
                ) // Set null here to prevent auto-closing on invalid validation
                .setNegativeButton(context.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()


            // 3. Override the positive button click listener to enforce text validation rules
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updatedName = edtName.text.toString().trim()
                val yearRaw = edtYear.text.toString().trim()

                // Fetch selected integer value out of the configured string array map
                val selectedSemester = spinnerSemester.selectedItem.toString().toIntOrNull() ?: 1

                if (updatedName.isEmpty()) {
                    edtName.error = context.getString(R.string.error_empty_name)
                    return@setOnClickListener
                }

                val updatedYear = yearRaw.toIntOrNull()
                if (updatedYear == null || yearRaw.isEmpty()) {
                    edtYear.error =
                        context.getString(R.string.label_year) // Or a specific invalid year error
                    return@setOnClickListener
                }

                // 4. Input validated; mutate state tree safely inside your Coroutine Dispatcher context
                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val updatedCourse = course.copy(
                            name = updatedName,
                            year = updatedYear,
                            semester = selectedSemester
                        )
                        db.updateCourse(updatedCourse)
                        if (selectedCourse != null && updatedCourse.id == selectedCourse!!.id) {
                            selectedCourse = updatedCourse
                        }
                    }

                    // 5. Provide UI success indicators and refresh foreground layouts
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_course_updated),
                        Toast.LENGTH_SHORT
                    ).show()

                    onCourseEdited()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun importSessionsFromCsv(uri: Uri, courseId: Long) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sessionsToInsert =
                    CourseUtilities.parseSessionsFromCsv(activity.contentResolver, uri, courseId)
                if (sessionsToInsert.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        showImportPreview(sessionsToInsert)
                    }
                }
            } catch (e: Exception) {
                Log.e("Presensor", "CSV Import error", e)
            }
        }
    }

    private fun triggerImportSessionPicker() {
        if (selectedCourse == null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/comma-separated-values"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/csv", "text/comma-separated-values", "text/plain")
            )
        }
        importSessionLauncher.launch(intent)
    }

    private fun performExport(uri: Uri) {
        val course = selectedCourse ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val sessions = db.getSessionsByCourse(course.id).sortedBy { it.date }
            val sessionIds = sessions.map { it.id }
            val allAttendance = mutableListOf<AttendanceRecord>()
            sessionIds.forEach { sid ->
                allAttendance.addAll(
                    db.getAttendanceRecordsForSession(sid)
                )
            }
            val allStudents = db.getAllStudents()

            val csvData =
                CourseUtilities.generateCsvString(
                    activity,
                    course,
                    sessions,
                    allAttendance,
                    allStudents
                )
            try {
                activity.contentResolver.openOutputStream(uri)
                    ?.use { it.write(csvData.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_export_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    /**
     * Initiates the authentication and sheet selection flow for updating attendance matrices.
     */
    fun triggerCloudAttendanceExport() {
        val course = selectedCourse ?: return
        activity.toggleLoadingOverlay(true)

        val action = {
            activity.cloudSyncController.fetchAvailableSpreadsheets { spreadsheets ->
                activity.toggleLoadingOverlay(false)

                if (spreadsheets.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.toast_cloud_sheets_empty), Toast.LENGTH_SHORT).show()
                    return@fetchAvailableSpreadsheets
                }

                // Show file dialog matching the correct Drive File type signature
                showCloudFileDialog(
                    title = "Select Sheet to Export Attendance",
                    subtitle = "Choose the Google Spreadsheet to update with current session records:",
                    driveItems = spreadsheets,
                    getName = { it.name }
                ) { selectedSpreadsheet ->

                    activity.toggleLoadingOverlay(true)

                    // Fetch sheets/tabs inside workbook
                    activity.cloudSyncController.fetchSpreadsheetTabs(selectedSpreadsheet.id) { tabs ->
                        activity.toggleLoadingOverlay(false)

                        if (tabs.isEmpty()) {
                            Toast.makeText(activity, activity.getString(R.string.toast_cloud_sheet_tabs_failed), Toast.LENGTH_SHORT).show()
                            return@fetchSpreadsheetTabs
                        }

                        // Select target tab using the exact same dialog container mapped to Strings
                        showCloudFileDialog(
                            title = "Select Target Sheet Tab",
                            subtitle = "Choose the worksheet tab to merge attendance matrix columns:",
                            driveItems = tabs,
                            getName = { it }
                        ) { selectedTab ->
                            // Execute grid operations on background workers safely
                            performCloudSpreadsheetMatrixSync(selectedSpreadsheet.id, selectedTab)
                        }
                    }
                }
            }
        }

        activity.setPendingAction(action)
        activity.cloudSyncController.runWithCloudAuthentication(activity.cloudSignInLauncher, action)
    }

    /**
     * Downloads the chosen spreadsheet matrix, joins missing students/sessions, and updates cells.
     */
    private fun performCloudSpreadsheetMatrixSync(spreadsheetId: String, tabName: String) {
        val course = selectedCourse ?: return
        activity.toggleLoadingOverlay(true)

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sheetsService = activity.cloudSyncController.getSheetsService()
                    ?: throw IllegalStateException("Sheets service was not initialized properly")

                val dateFormat = CourseUtilities.makeSessionTimeFormatter(activity)

                // 1. Query Local Room DB State Trees
                val localSessions = db.getSessionsByCourse(course.id).sortedBy { it.date }
                val localStudents = db.getStudentsForCourse(course.id).sortedBy { it.email }
                val localAttendanceMap = db.getAllAttendanceForCourse(course.id)
                    .groupBy { it.studentEmail to it.sessionId }

                // 2. Fetch Existing Cloud Layout Bounds
                val response = sheetsService.spreadsheets().values()
                    .get(spreadsheetId, "'$tabName'!A1:Z1000")
                    .execute()

                val currentGrid: MutableList<MutableList<Any>> = response.getValues()?.map {
                    it.map { cell -> cell ?: "" }.toMutableList()
                }?.toMutableList() ?: mutableListOf()

                // Initialize structural headers if sheet tab is completely pristine
                if (currentGrid.isEmpty()) {
                    currentGrid.add(mutableListOf("Student Email", "Student Name"))
                }

                val headerRow = currentGrid[0]

                // 3. Coordinate Columns Alignment (Sessions)
                val sessionToColumnIdx = mutableMapOf<Long, Int>()
                localSessions.forEach { session ->
                    val formattedHeader = "${session.name} (${CourseUtilities.fromMillisToLocalDate(session.date).format(dateFormat)})"
                    var matchIndex = headerRow.indexOfFirst { it.toString().equals(formattedHeader, ignoreCase = true) }
                    if (matchIndex == -1) {
                        headerRow.add(formattedHeader)
                        matchIndex = headerRow.lastIndex
                    }
                    sessionToColumnIdx[session.id] = matchIndex
                }

                // 4. Coordinate Rows Alignment (Students)
                val studentToRowIdx = mutableMapOf<String, Int>()
                for (i in 1 until currentGrid.size) {
                    val emailCell = currentGrid[i].getOrNull(0)?.toString()?.trim() ?: ""
                    if (emailCell.isNotEmpty()) {
                        studentToRowIdx[emailCell] = i
                    }
                }

                localStudents.forEach { student ->
                    if (!studentToRowIdx.containsKey(student.email)) {
                        val newRow = mutableListOf<Any>(student.email, student.name)
                        currentGrid.add(newRow)
                        studentToRowIdx[student.email] = currentGrid.lastIndex
                    }
                }

                // 5. Fill and Update Intersections (Attendance Matrix Joining)
                localStudents.forEach { student ->
                    val rowIdx = studentToRowIdx[student.email] ?: return@forEach
                    val rowData = currentGrid[rowIdx]

                    // Pad list elements out to match expansion columns size safely
                    while (rowData.size < headerRow.size) {
                        rowData.add("")
                    }

                    localSessions.forEach { session ->
                        val colIdx = sessionToColumnIdx[session.id] ?: return@forEach
                        val key = student.email to session.id
                        val wasPresent = localAttendanceMap.containsKey(key)

                        // Assign "P" for Present and "A" for Absent
                        rowData[colIdx] = if (wasPresent) "P" else "A"
                    }
                }

                // 6. Save Matched Matrix State back to Google Drive Context
                val updateBody = com.google.api.services.sheets.v4.model.ValueRange().setValues(currentGrid as List<List<Any>>?)
                sheetsService.spreadsheets().values()
                    .update(spreadsheetId, "'$tabName'!A1", updateBody)
                    .setValueInputOption("USER_ENTERED")
                    .execute()

                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, "Attendance synced successfully to Cloud!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("CourseController", "Cloud spreadsheet matching sync execution crash", e)
                withContext(Dispatchers.Main) {
                    activity.toggleLoadingOverlay(false)
                    Toast.makeText(activity, "Cloud sync failed. Check connectivity configurations.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * A perfectly generic helper layout to handle both raw String lists and Google Drive File collections.
     */
    private fun <T> showCloudFileDialog(
        title: String,
        subtitle: String,
        driveItems: List<T>,
        getName: (T) -> String,
        onItemSelected: (T) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cloud_import, null)
        dialogView.findViewById<TextView>(R.id.dialogSubtitle).text = subtitle
        val listView = dialogView.findViewById<android.widget.ListView>(R.id.backupListView)
        val searchView = dialogView.findViewById<androidx.appcompat.widget.SearchView>(R.id.dialogSearchView)

        val itemMap = driveItems.associateBy { getName(it) }
        val itemNames = driveItems.map { getName(it) }

        val adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_list_item_1, itemNames)
        listView.adapter = adapter

        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter.filter(newText)
                return true
            }
        })

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(dialogView)
            .setNegativeButton(activity.getString(R.string.action_cancel), null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedName = adapter.getItem(position) ?: return@setOnItemClickListener
            val selectedItem = itemMap[selectedName] ?: return@setOnItemClickListener
            dialog.dismiss()
            onItemSelected(selectedItem)
        }
        dialog.show()
    }
}