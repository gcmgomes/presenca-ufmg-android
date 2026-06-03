package com.example.presensor.controllers

import android.app.Activity.RESULT_OK
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class CourseController(
    private val activity: AppCompatActivity,
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
    private val btnExportCourse: Button = activity.findViewById(R.id.btnExportCourse)
    private val btnCourseStats: Button = activity.findViewById(R.id.btnCourseStats)
    private val btnImportSchedule: MaterialButton = activity.findViewById(R.id.btnImportSchedule)

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
            val count = db.getCourseCache().allSessions.size + 1
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
        btnCourseStats.setOnClickListener { onOpenStatistics() }
        btnImportSchedule.setOnClickListener { triggerImportSessionPicker() }
        btnExportCourse.setOnClickListener {
            val courseName = txtDetailCourseNameText()
            val fileName = "Attendance_${courseName.replace(" ", "_")}.csv"
            exportLauncher.launch(fileName)
        }
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
            refreshSessionsList(db.getCourseCache().allSessions)
            val cache = db.getCourseCache()
            val layoutCourseView = activity.findViewById<View>(R.id.layoutCourseView)
            fillCourseDetailedCardStatistics(
                layoutCourseView,
                selectedCourse!!,
                cache.sessionIds,
                cache.activeStudentEmails,
                cache.allAttendance
            )
        }
    }

    fun fillCourseDetailedCardStatistics(
        card: View,
        course: Course,
        sessionIds: Set<Long>,
        studentEmails: Set<String>,
        courseAttendances: List<AttendanceRecord>
    ) {
        card.findViewById<TextView>(R.id.txtDetailCourseName).text = course.name

        // Dynamic localized layout ordinal mapping integration ("1st Semester" vs "1º Semestre")
        val semesterOrdinal = if (course.semester == 1) {
            activity.getString(R.string.semester_ordinal_1st)
        } else {
            activity.getString(R.string.semester_ordinal_2nd)
        }
        card.findViewById<TextView>(R.id.txtDetailCourseSemester).text =
            activity.getString(R.string.semester_display_format, course.year, semesterOrdinal)

        card.findViewById<View>(R.id.viewCourseDetailAccent)
            .setBackgroundColor(getColorForAccent(course.name))

        val studentCount = studentEmails.size
        val sessionCount = sessionIds.size

        val avgAttendance = if (studentCount > 0 && sessionCount > 0) {
            val totalPossible = studentCount * sessionCount
            val actualLogs =
                courseAttendances.map { it.sessionId to it.studentEmail }.distinct().size
            (actualLogs.toFloat() / totalPossible.toFloat() * 100).toInt()
        } else {
            0
        }

        card.findViewById<TextView>(R.id.txtStatStudentCount).text = studentCount.toString()
        card.findViewById<TextView>(R.id.txtStatSessionCount).text = sessionCount.toString()
        card.findViewById<TextView>(R.id.txtStatAvgAttendance).text = "$avgAttendance%"
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
            activity.getString(R.string.current_semester_head_text),
            thisWeekSessions
        )
        addSessionsToCourseView(
            activity.getString(R.string.upcoming_semester_head_text),
            upcomingSessions
        )
        addSessionsToCourseView(
            activity.getString(R.string.previous_semester_head_text),
            pastSessions
        )
    }

    private fun addSessionsToCourseView(title: String, sessions: List<Session>) {
        if (sessions.isNotEmpty()) {
            MainUiBinder.addSectionHeader(sessionContainer, title)
            sessions.forEach { addSessionCardToContainer(it) }
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
            .setBackgroundColor(getColorForAccent(session.name))
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

    private fun getColorForAccent(courseName: String): Int {
        val typedArray = activity.resources.obtainTypedArray(R.array.chalk_colors_list)
        val colors = IntArray(typedArray.length())
        for (i in 0 until typedArray.length()) {
            colors[i] = typedArray.getColor(i, 0)
        }
        typedArray.recycle()
        return colors[abs(courseName.hashCode()) % colors.size]
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

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.title_new_course))
            .setView(dialogView)
            .setPositiveButton(
                context.getString(R.string.action_create),
                null
            ) // Set null to manually override closure behaviour for validation checks
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .create()

        dialog.show()

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
                    db.dao().insertCourse(newCourse)
                }
                onCourseCreated()
                dialog.dismiss()
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
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.title_edit_course))
            .setView(dialogView)
            .setPositiveButton(
                context.getString(R.string.action_save),
                null
            ) // Set null here to prevent auto-closing on invalid validation
            .setNegativeButton(context.getString(R.string.action_cancel), null)
            .create()

        dialog.show()

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
                    db.dao().updateCourse(updatedCourse)
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
            val sessions = db.dao().getSessionsByCourse(course.id).sortedBy { it.date }
            val sessionIds = sessions.map { it.id }
            val allAttendance = mutableListOf<AttendanceRecord>()
            sessionIds.forEach { sid ->
                allAttendance.addAll(
                    db.dao().getAttendanceRecordsForSession(sid)
                )
            }
            val allStudents = db.dao().getAllStudents()

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
}