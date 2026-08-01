package com.example.presensor.controllers

import android.net.Uri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.R
import com.example.presensor.controllers.items.ActionItem
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.controllers.providers.CourseInteractionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class CourseController(
    private val lifecycleOwner: LifecycleOwner,
    private var selectedCourse: Course?,
    private val db: AppDatabase,
    private val interactionProvider: CourseInteractionProvider,
    private val onSessionSelected: (Session) -> Unit,
    private val onToggleLockRequested: (Session) -> Unit,
    private val onEditSessionRequested: (Session) -> Unit,
    private val onOpenStatistics: () -> Unit
) {

    init {
        interactionProvider.setupCourseUtilsAccordion { isExpanded ->
            interactionProvider.setUtilsExpandIconRotation(if (isExpanded) 180f else 0f)
            interactionProvider.setUtilsContentVisibility(!isExpanded)
        }
        setupOnClickListeners()
        
        interactionProvider.registerImportSessionLauncher { uri ->
            selectedCourse?.let { importSessionsFromCsv(uri, it.id) }
        }
        
        interactionProvider.registerExportLauncher { uri ->
            performExport(uri)
        }
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

        // Return the Job reference to the caller
        return lifecycleOwner.lifecycleScope.launch {
            db.loadSessionsForCourse(course)
        }
    }

    fun clear() {
        selectedCourse = null
    }

    fun showCreateSessionDialog() {
        selectedCourse?.let { course ->
            interactionProvider.showCreateSessionDialog(course.id) { courseId, sessionName, date ->
                addSession(courseId, sessionName, date)
            }
        }
    }

    private fun setupOnClickListeners() {
        val allActions = listOf(
            // PAGE 1 Items
            ActionItem(
                text = interactionProvider.getString(R.string.menu_course_statistics),
                iconResId = R.drawable.ic_view,
                onClick = { onOpenStatistics() }
            ),

            ActionItem(
                text = interactionProvider.getString(R.string.menu_course_postpone),
                iconResId = R.drawable.ic_postpone,
                onClick = { showMassDateChangeDialog() }
            ),

            // PAGE 2 Items
            ActionItem(
                text = interactionProvider.getString(R.string.menu_course_export),
                iconResId = R.drawable.ic_export,
                onClick = {
                    val courseName = selectedCourse?.name ?: interactionProvider.getString(R.string.filename_attendance_fallback)
                    val fileName = "Attendance_${courseName.replace(" ", "_")}.csv"
                    interactionProvider.launchExportPicker(fileName)
                }
            ),
            ActionItem(
                text = interactionProvider.getString(R.string.menu_course_import),
                iconResId = R.drawable.ic_import,
                onClick = { interactionProvider.launchImportPicker() }
            ),


            // PAGE 3 Items
            ActionItem(
                text = interactionProvider.getString(R.string.menu_course_export),
                iconResId = R.drawable.ic_export,
                onClick = { interactionProvider.triggerCloudAttendanceExport() }
            ),
            ActionItem(
                text = interactionProvider.getString(R.string.menu_course_import),
                iconResId = R.drawable.ic_import,
                onClick = {
                    interactionProvider.triggerCloudScheduleImport { refreshCourseUI() }
                }
            ),
        )


        val pageTitles = listOf(
            interactionProvider.getString(R.string.menu_title_course_management),
            interactionProvider.getString(R.string.category_local_operations),
            interactionProvider.getString(R.string.category_cloud_operations)
        )

        interactionProvider.setupQuickActions(allActions, pageTitles)
    }

    fun addSession(courseId: Long, sessionName: String, date: Long) {
        lifecycleOwner.lifecycleScope.launch {
            db.insertSession(courseId, sessionName, date)
            refreshCourseUI()
        }
    }

    fun refreshCourseUI() {
        selectedCourse?.let { course ->
            val sessionList = runBlocking {
                db.getSessionsByCourse(course.id)
            }
            val attendanceList = runBlocking {
                db.getAllAttendanceForCourse(course.id)
            }
            
            interactionProvider.refreshSessionsList(
                sessions = sessionList,
                onSessionSelected = onSessionSelected,
                onToggleLockRequested = onToggleLockRequested,
                onEditSessionRequested = onEditSessionRequested,
                onDeleteSessionRequested = { session -> interactionProvider.showDeleteSessionDialog(session) }
            )
            
            interactionProvider.updateCourseHeader(
                course,
                sessionList.map { it.id }.toSet(),
                attendanceList.map { it.studentEmail }.toSet(),
                attendanceList
            )
        }
    }

    fun showMassDateChangeDialog() {
        selectedCourse?.let {
            interactionProvider.showMassDateChangeDialog(it.id)
        }
    }

    fun showCreateCourseDialog(onCourseCreated: () -> Unit) {
        interactionProvider.showCreateCourseDialog(onCourseCreated)
    }

    fun showEditCourseDialog(course: Course, onCourseEdited: () -> Unit) {
        interactionProvider.showEditCourseDialog(course, onCourseEdited)
    }

    private fun importSessionsFromCsv(uri: Uri, courseId: Long) {
        interactionProvider.importSessionsFromCsv(uri, courseId) { refreshCourseUI() }
    }

    private fun performExport(uri: Uri) {
        val course = selectedCourse ?: return
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val sessions = db.getSessionsByCourse(course.id).sortedBy { it.date }
            val allAttendance = db.getAllAttendanceForCourse(course.id)
            val allStudents = db.getAllStudents()

            val csvData = com.example.presensor.tools.DataProcessor.generateCsvString(
                interactionProvider.getContext(),
                course,
                sessions,
                allAttendance,
                allStudents
            )
            try {
                interactionProvider.openOutputStream(uri)
                    ?.use { it.write(csvData.toByteArray()) }
                interactionProvider.showToast(R.string.toast_export_success)
            } catch (e: Exception) {
                interactionProvider.showToast(R.string.toast_export_failed)
            }
        }
    }
}
