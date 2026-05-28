package com.example.presensor.controllers

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.presensor.MainUiBinder
import com.example.presensor.adapters.ImportPreviewAdapter
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.AttendanceRecord
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

class CourseController(
    private val activity: AppCompatActivity,
    private val lifecycleOwner: LifecycleOwner,
    private var selectedCourse: Course?,
    private val db: AppDatabase,
    private val onSessionSelected: (Session) -> Unit,
    private val onSessionLongClicked: (Session) -> Unit,
    private val onToggleLockRequested: (Session, ImageView) -> Unit,
    private val onOpenStatistics: () -> Unit,
) {
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

    // Layout view boundaries inside layoutCourseView
    private val sessionContainer: LinearLayout = activity.findViewById(R.id.sessionContainer)
    private val btnExportCourse: Button = activity.findViewById(R.id.btnExportCourse)
    private val btnCourseStats: Button = activity.findViewById(R.id.btnCourseStats)
    private val btnImportSchedule: MaterialButton = activity.findViewById(R.id.btnImportSchedule)

    private val importSessionLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> importSessionsFromCsv(uri, selectedCourse!!.id) }
        }
    }


    private val exportLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { performExport(it) }
    }

    init {
        setupCourseUtilsAccordion()
        setupOnClickListeners()
    }

    fun getSelectedCourse(): Course? {
        return selectedCourse
    }

    fun setSelectedCourse(course: Course?) {
        selectedCourse = course
    }

    private fun setupOnClickListeners() {
        btnCourseStats.setOnClickListener { onOpenStatistics() }
        btnImportSchedule.setOnClickListener { triggerImportSessionPicker() }
        btnExportCourse.setOnClickListener {
            // Replaces the inline anonymous logic from original MainActivity
            val courseName = txtDetailCourseNameText()
            val fileName = "Attendance_${courseName.replace(" ", "_")}.csv"
            exportLauncher.launch(fileName)
        }
    }

    private fun txtDetailCourseNameText(): String {
        val txtName = activity.findViewById<TextView>(R.id.txtDetailCourseName)
        return txtName?.text?.toString() ?: "Attendance"
    }

    // move this to appdatabase.kt
    fun loadSessionsFromDb(course: Course) {
        lifecycleOwner.lifecycleScope.launch {
            val sessionsDeferred = async(Dispatchers.IO) { db.dao().getSessionsByCourse(course.id) }
            val attendanceDeferred = async(Dispatchers.IO) { db.dao().getAllAttendanceForCourse(course.id) }

            val sessions = sessionsDeferred.await().sortedByDescending { it.date }
            val allAttendance = attendanceDeferred.await()

            val nowMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pastSessionIds = sessions.filter { it.date <= nowMillis }.map { it.id }.toSet()

            refreshSessionsList(sessions)

            val sessionIds = sessions.filter { it.date <= nowMillis }.map { it.id }
            val attendeeEmails = allAttendance.filter { pastSessionIds.contains(it.sessionId) }.map { it.studentEmail }.distinct()

            val layoutCourseView = activity.findViewById<View>(R.id.layoutCourseView)
            fillCourseDetailedCardStatistics(layoutCourseView, course, sessionIds.toSet(), attendeeEmails, allAttendance)
        }
    }


    fun refreshCourseUI() {
        selectedCourse?.let { loadSessionsFromDb(it) }
    }

    fun fillCourseDetailedCardStatistics(card: View, course: Course, sessionIds: Set<Long>, studentEmails: List<String>, courseAttendances: List<AttendanceRecord>) {
        card.findViewById<TextView>(R.id.txtDetailCourseName).text = course.name
        card.findViewById<TextView>(R.id.txtDetailCourseSemester).text = CourseUtilities.formatYearSemester(course.year, course.semester)
        card.findViewById<View>(R.id.viewCourseDetailAccent).setBackgroundColor(getColorForAccent(course.name))

        val studentCount = studentEmails.size
        val sessionCount = sessionIds.size

        val avgAttendance = if (studentCount > 0 && sessionCount > 0) {
            val totalPossible = studentCount * sessionCount
            val actualLogs = courseAttendances.map { it.sessionId to it.studentEmail }.distinct().size
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

        val thisWeekSessions = sessions.filter { CourseUtilities.isDateInCurrentWeek(CourseUtilities.fromMillisToLocalDate(it.date)) }
        val upcomingSessions = sessions.filter { !CourseUtilities.isDateInCurrentWeek(CourseUtilities.fromMillisToLocalDate(it.date)) && CourseUtilities.fromMillisToLocalDate(it.date).isAfter(LocalDate.now()) }
        val pastSessions = sessions.filter { !CourseUtilities.isDateInCurrentWeek(CourseUtilities.fromMillisToLocalDate(it.date)) && CourseUtilities.fromMillisToLocalDate(it.date).isBefore(LocalDate.now()) }.sortedBy { it.date }

        addSessionsToCourseView("This week", thisWeekSessions)
        addSessionsToCourseView("Upcoming sessions", upcomingSessions)
        addSessionsToCourseView("Past sessions", pastSessions)
    }

    private fun addSessionsToCourseView(title: String, sessions: List<Session>) {
        if (sessions.isNotEmpty()) {
            MainUiBinder.addSectionHeader(sessionContainer, title)
            sessions.forEach { addSessionCardToContainer(it) }
        }
    }

    private fun addSessionCardToContainer(session: Session) {
        val itemView = layoutInflater.inflate(R.layout.item_session_card, sessionContainer, false)
        val dateFormat = CourseUtilities.makeSessionTimeFormatter()

        val imgLock = itemView.findViewById<ImageView>(R.id.imgSessionLockOnSessionView)
        itemView.findViewById<View>(R.id.viewSessionAccent).setBackgroundColor(getColorForAccent(session.name))
        itemView.findViewById<TextView>(R.id.txtSessionName).text = session.name
        itemView.findViewById<TextView>(R.id.txtSessionDetails).text = CourseUtilities.fromMillisToLocalDate(session.date).format(dateFormat)

        updateLockIconUI(session.isLocked, imgLock)
        imgLock.setOnClickListener { onToggleLockRequested(session, imgLock) }
        itemView.setOnClickListener { onSessionSelected(session) }
        itemView.setOnLongClickListener {
            onSessionLongClicked(session)
            true
        }
        sessionContainer.addView(itemView)
    }

    fun updateLockIconUI(isLocked: Boolean, lockIcon: ImageView) {
        if (isLocked) {
            lockIcon.setImageResource(R.drawable.status_lock)
            lockIcon.alpha = 1.0f
        } else {
            lockIcon.setImageResource(R.drawable.status_unlock)
            lockIcon.alpha = 0.5f
        }
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
        return colors[Math.abs(courseName.hashCode()) % colors.size]
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
        txtImportCount.text = "Found ${sessions.size} sessions."

        btnConfirm.setOnClickListener {
            activity.lifecycleScope.launch { db.dao().insertSessions(sessions) }
            bottomSheet.dismiss()
            refreshCourseUI()
            Toast.makeText(activity, "Imported ${sessions.size} sessions", Toast.LENGTH_SHORT).show()
        }
        bottomSheet.show()
    }



    private fun importSessionsFromCsv(uri: Uri, courseId: Long) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sessionsToInsert = CourseUtilities.parseSessionsFromCsv(activity.contentResolver, uri, courseId)
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
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
        }
        importSessionLauncher.launch(intent)
    }



    private fun performExport(uri: Uri) {
        val course = selectedCourse ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val sessions = db.dao().getSessionsByCourse(course.id).sortedBy { it.date }
            val sessionIds = sessions.map { it.id }
            val allAttendance = mutableListOf<AttendanceRecord>()
            sessionIds.forEach { sid -> allAttendance.addAll(db.dao().getAttendanceRecordsForSession(sid)) }
            val allStudents = db.dao().getAllStudents()

            val csvData = CourseUtilities.generateCsvString(course, sessions, allAttendance, allStudents)
            try {
                activity.contentResolver.openOutputStream(uri)?.use { it.write(csvData.toByteArray()) }
                withContext(Dispatchers.Main) { Toast.makeText(activity, "Roster exported!", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(activity, "Export failed", Toast.LENGTH_SHORT).show() }
            }
        }
    }


}