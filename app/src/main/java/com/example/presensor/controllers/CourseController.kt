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
import com.example.presensor.MainActivity
import com.example.presensor.MainUiBinder
import com.example.presensor.tools.DataProcessor
import com.example.presensor.tools.TimeUtils
import com.example.presensor.tools.UiUtils
import com.example.presensor.adapters.ImportPreviewAdapter
import com.example.presensor.data.InternalDataTable
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
import com.example.presensor.cloud.CourseCloudActions
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory

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
    private val dialogFactory: CourseControllerDialogFactory
) {
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

    private val cloudActions = CourseCloudActions(
        activity = activity,
        lifecycleOwner = lifecycleOwner,
        db = db,
        getSelectedCourse = { selectedCourse },
        onImportComplete = { refreshCourseUI() }
    )

    // Layout view boundaries inside layoutCourseView
    private val sessionContainer: LinearLayout = activity.findViewById(R.id.sessionContainer)
    private val utilsViewPager: androidx.viewpager2.widget.ViewPager2 = activity.findViewById(R.id.utilsViewPager)
    private val utilsPageIndicator: com.google.android.material.tabs.TabLayout = activity.findViewById(R.id.utilsPageIndicator)

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
            dialogFactory.showEditCourseDialog(selectedCourse!!, { updated ->
                if (selectedCourse != null && updated.id == selectedCourse!!.id) {
                    selectedCourse = updated
                }
            }, {
                refreshCourseUI()
            })
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
        dialogFactory.showCreateSessionDialog()
    }


    private fun setupOnClickListeners() {
        // A single sequential list containing all 6 operations distributed in pairs
        val allActions = listOf(
            // PAGE 1 Items
            com.example.presensor.adapters.ActionItem(
                text = activity.getString(R.string.menu_course_statistics),
                iconResId = R.drawable.ic_view,
                onClick = { onOpenStatistics() }
            ),

            com.example.presensor.adapters.ActionItem(
                text = activity.getString(R.string.menu_course_postpone),
                iconResId = R.drawable.ic_postpone,
                onClick = { dialogFactory.showMassDateChangeDialog() }
            ),

            // PAGE 2 Items
            com.example.presensor.adapters.ActionItem(
                text = activity.getString(R.string.menu_course_export),
                iconResId = R.drawable.ic_export,
                onClick = {
                    val courseName = txtDetailCourseNameText()
                    val fileName = "Attendance_${courseName.replace(" ", "_")}.csv"
                    exportLauncher.launch(fileName)
                }
            ),
            com.example.presensor.adapters.ActionItem(
                text = activity.getString(R.string.menu_course_import),
                iconResId = R.drawable.ic_import,
                onClick = { triggerImportSessionPicker() }
            ),


            // PAGE 3 Items
            com.example.presensor.adapters.ActionItem(
                text = activity.getString(R.string.menu_course_export),
                iconResId = R.drawable.ic_export, // or your cloud icon
                onClick = { cloudActions.triggerCloudAttendanceExport() }
            ),
            com.example.presensor.adapters.ActionItem(
                text = activity.getString(R.string.menu_course_import),
                iconResId = R.drawable.ic_import,
                onClick = {
                    cloudActions.triggerCloudScheduleImport()
                }
            ),
        )


        val pageTitles = listOf(
            activity.getString(R.string.menu_title_course_management),
            activity.getString(R.string.category_local_operations),
            activity.getString(R.string.category_cloud_operations)
        )

        // Bind your actions to the updated adapter
        utilsViewPager.adapter = com.example.presensor.adapters.ActionsPageAdapter(
            actionItems = allActions,
            pageTitles = pageTitles,
            itemsPerPage = 2,
            layoutResId = R.layout.item_course_utils_page,
            buttonIds = listOf(R.id.btnRow1, R.id.btnRow2)
        )

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
            UiUtils.fillCourseDetailedCardStatistics(
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
            TimeUtils.isDateInCurrentWeek(
                TimeUtils.fromMillisToLocalDate(it.date)
            )
        }
        val upcomingSessions = sessions.filter {
            !TimeUtils.isDateInCurrentWeek(
                TimeUtils.fromMillisToLocalDate(it.date)
            ) && TimeUtils.fromMillisToLocalDate(it.date).isAfter(LocalDate.now())
        }
        val pastSessions = sessions.filter {
            !TimeUtils.isDateInCurrentWeek(
                TimeUtils.fromMillisToLocalDate(it.date)
            ) && TimeUtils.fromMillisToLocalDate(it.date).isBefore(LocalDate.now())
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
        dialogFactory.showMassDateChangeDialog()
    }

    fun showCreateCourseDialog(onCourseCreated: () -> Unit) {
        dialogFactory.showCreateCourseDialog(onCourseCreated)
    }

    fun showEditCourseDialog(course: Course, onCourseEdited: () -> Unit) {
        dialogFactory.showEditCourseDialog(course, { updated ->
            if (selectedCourse != null && updated.id == selectedCourse!!.id) {
                selectedCourse = updated
            }
        }, onCourseEdited)
    }

    private fun addSessionCardToContainer(session: Session) {
        val itemView = layoutInflater.inflate(R.layout.item_session_card, sessionContainer, false)
        val dateFormat = TimeUtils.makeSessionTimeFormatter(activity)

        val imgLock = itemView.findViewById<ImageView>(R.id.imgSessionLockOnSessionView)
        itemView.findViewById<View>(R.id.viewSessionAccent)
            .setBackgroundColor(
                UiUtils.getColorForAccent(
                    session.name,
                    activity.resources.obtainTypedArray(R.array.chalk_colors_list)
                )
            )
        itemView.findViewById<TextView>(R.id.txtSessionName).text = session.name
        itemView.findViewById<TextView>(R.id.txtSessionDetails).text =
            TimeUtils.fromMillisToLocalDate(session.date).format(dateFormat)

        UiUtils.updateLockIconUI(session.isLocked, imgLock)
        imgLock.setOnClickListener { onToggleLockRequested(session, imgLock) }

        val editBtn = itemView.findViewById<ImageView>(R.id.btnEditSession)
        UiUtils.updateEditIconUI(session.isLocked, editBtn)
        editBtn.setOnClickListener { onEditSessionRequested(session, editBtn) }

        itemView.setOnClickListener { onSessionSelected(session) }
        itemView.setOnLongClickListener {
            dialogFactory.showDeleteSessionDialog(session)
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

    private fun importSessionsFromCsv(uri: Uri, courseId: Long) {
        ImportSessionController.importFromLocal(
            activity = activity,
            uri = uri,
            courseId = courseId,
            onImportComplete = { refreshCourseUI() }
        )
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
                DataProcessor.generateCsvString(
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
