package com.example.presensor.controllers.providers

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.cloud.CourseCloudActions
import com.example.presensor.controllers.adapters.ActionsPageAdapter
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.items.ActionItem
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.tools.TimeUtils
import com.example.presensor.tools.UiUtils
import java.io.OutputStream

class AndroidCourseInteractionProvider(
    activity: MainActivity,
    private val courseDialogFactory: CourseControllerDialogFactory,
    private val sessionDialogFactory: SessionControllerDialogFactory
) : BaseAndroidInteractionProvider(activity), CourseInteractionProvider {

    private var onImportSessionCallback: ((Uri) -> Unit)? = null
    private var onExportCallback: ((Uri) -> Unit)? = null

    private val importSessionLauncher: ActivityResultLauncher<Intent> =
        activity.activityResultRegistry.register("import_session", activity, ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri -> onImportSessionCallback?.invoke(uri) }
            }
        }

    private val exportLauncher: ActivityResultLauncher<String> =
        activity.activityResultRegistry.register("export_document", activity, ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { onExportCallback?.invoke(it) }
        }

    private var courseCloudActions: CourseCloudActions? = null

    fun initializeCourseCloudActions(getSelectedCourse: () -> Course?, onImportComplete: () -> Unit) {
        courseCloudActions = CourseCloudActions(
            activity = activity,
            lifecycleOwner = activity,
            db = activity.getDb(),
            getSelectedCourse = getSelectedCourse,
            onImportComplete = onImportComplete
        )
    }

    override fun setupCourseUtilsAccordion(onHeaderClicked: (isExpanded: Boolean) -> Unit) {
        activity.runOnUiThread {
            val headerClickArea =
                activity.findViewById<View>(R.id.layoutUtilsHeader) ?: return@runOnUiThread
            val expandableLayout = activity.findViewById<View>(R.id.layoutUtilsContent)
            headerClickArea.setOnClickListener {
                onHeaderClicked(expandableLayout.visibility == View.VISIBLE)
            }
        }
    }

    override fun setUtilsExpandIconRotation(rotation: Float) {
        activity.runOnUiThread {
            activity.findViewById<View>(R.id.imgUtilsExpandIcon)?.animate()?.rotation(rotation)
                ?.setDuration(300L)?.start()
        }
    }

    override fun setUtilsContentVisibility(visible: Boolean) {
        activity.runOnUiThread {
            val cardRoot = activity.findViewById<View>(R.id.layoutInnerCourseView)
            val expandableLayout = activity.findViewById<View>(R.id.layoutUtilsContent)

            if (cardRoot is android.view.ViewGroup) {
                val transition = androidx.transition.TransitionSet().apply {
                    addTransition(androidx.transition.ChangeBounds())
                    addTransition(androidx.transition.Fade())
                    ordering = androidx.transition.TransitionSet.ORDERING_TOGETHER
                    duration = 300L
                    interpolator = androidx.interpolator.view.animation.FastOutSlowInInterpolator()
                }
                androidx.transition.TransitionManager.beginDelayedTransition(cardRoot, transition)
            }
            expandableLayout.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    override fun refreshSessionsList(
        sessions: List<Session>,
        onSessionSelected: (Session) -> Unit,
        onToggleLockRequested: (Session) -> Unit,
        onEditSessionRequested: (Session) -> Unit,
        onDeleteSessionRequested: (Session) -> Unit
    ) {
        activity.runOnUiThread {
            val sessionContainer =
                activity.findViewById<LinearLayout>(R.id.sessionContainer) ?: return@runOnUiThread
            sessionContainer.removeAllViews()

            val thisWeekSessions =
                sessions.filter { TimeUtils.isDateInCurrentWeek(TimeUtils.fromMillisToLocalDate(it.date)) }
            val upcomingSessions = sessions.filter {
                !TimeUtils.isDateInCurrentWeek(TimeUtils.fromMillisToLocalDate(it.date)) && TimeUtils.fromMillisToLocalDate(
                    it.date
                ).isAfter(java.time.LocalDate.now())
            }
            val pastSessions = sessions.filter {
                !TimeUtils.isDateInCurrentWeek(TimeUtils.fromMillisToLocalDate(it.date)) && TimeUtils.fromMillisToLocalDate(
                    it.date
                ).isBefore(java.time.LocalDate.now())
            }.sortedBy { it.date }

            fun addSessions(title: String, list: List<Session>) {
                if (list.isNotEmpty()) {
                    com.example.presensor.MainUiBinder.addSectionHeader(sessionContainer, title)
                    list.forEach { session ->
                        val itemView = activity.layoutInflater.inflate(
                            R.layout.item_session_card,
                            sessionContainer,
                            false
                        )
                        itemView.findViewById<View>(R.id.viewSessionAccent)
                            .setBackgroundColor(
                                UiUtils.getColorForAccent(
                                    session.name,
                                    activity.resources.obtainTypedArray(R.array.chalk_colors_list)
                                )
                            )
                        itemView.findViewById<TextView>(R.id.txtSessionName).text = session.name
                        itemView.findViewById<TextView>(R.id.txtSessionDetails).text =
                            TimeUtils.fromMillisToLocalDate(session.date)
                                .format(TimeUtils.makeSessionTimeFormatter(activity))

                        val imgLock =
                            itemView.findViewById<ImageView>(R.id.imgSessionLockOnSessionView)
                        UiUtils.updateLockIconUI(session.isLocked, imgLock)
                        imgLock.setOnClickListener { onToggleLockRequested(session) }

                        val editBtn = itemView.findViewById<ImageView>(R.id.btnEditSession)
                        UiUtils.updateEditIconUI(session.isLocked, editBtn)
                        editBtn.setOnClickListener { onEditSessionRequested(session) }

                        itemView.setOnClickListener { onSessionSelected(session) }
                        itemView.setOnLongClickListener { onDeleteSessionRequested(session); true }
                        sessionContainer.addView(itemView)
                    }
                }
            }

            addSessions(
                activity.getString(R.string.current_week_session_head_text),
                thisWeekSessions
            )
            addSessions(activity.getString(R.string.upcoming_sessions_head_text), upcomingSessions)
            addSessions(activity.getString(R.string.previous_sessions_head_text), pastSessions)
        }
    }

    override fun updateCourseHeader(
        course: Course,
        sessionIds: Set<Long>,
        studentEmails: Set<String>,
        attendance: List<AttendanceRecord>
    ) {
        activity.runOnUiThread {
            val layoutCourseView =
                activity.findViewById<View>(R.id.layoutCourseView) ?: return@runOnUiThread
            UiUtils.fillCourseDetailedCardStatistics(
                activity,
                layoutCourseView,
                course,
                sessionIds,
                studentEmails,
                attendance
            )
        }
    }

    override fun setupQuickActions(actions: List<ActionItem>, titles: List<String>) {
        activity.runOnUiThread {
            val utilsViewPager =
                activity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.utilsViewPager)
                    ?: return@runOnUiThread
            val utilsPageIndicator =
                activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.utilsPageIndicator)

            utilsViewPager.adapter = ActionsPageAdapter(
                actionItems = actions,
                pageTitles = titles,
                itemsPerPage = 2,
                layoutResId = R.layout.item_course_utils_page,
                buttonIds = listOf(R.id.btnRow1, R.id.btnRow2)
            )
            com.google.android.material.tabs.TabLayoutMediator(
                utilsPageIndicator,
                utilsViewPager
            ) { _, _ -> }.attach()
        }
    }

    override fun launchExportPicker(fileName: String) {
        exportLauncher.launch(fileName)
    }

    override fun launchImportPicker() {
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

    override fun registerImportSessionLauncher(callback: (Uri) -> Unit) {
        this.onImportSessionCallback = callback
    }

    override fun registerExportLauncher(callback: (Uri) -> Unit) {
        this.onExportCallback = callback
    }

    override fun triggerCloudScheduleImport(onImportComplete: () -> Unit) {
        courseCloudActions?.triggerCloudScheduleImport()
    }

    override fun triggerCloudAttendanceExport() {
        courseCloudActions?.triggerCloudAttendanceExport()
    }

    override fun openOutputStream(uri: Uri): OutputStream? =
        activity.contentResolver.openOutputStream(uri)

    override fun showEditCourseDialog(course: Course, onCourseEdited: () -> Unit) {
        activity.runOnUiThread {
            activeAlertDialog =
                courseDialogFactory.showEditCourseDialog(course, { _ -> }, onCourseEdited)
        }
    }

    override fun showCreateCourseDialog(onCourseCreated: () -> Unit) {
        activity.runOnUiThread {
            activeAlertDialog = courseDialogFactory.showCreateCourseDialog(onCourseCreated)
        }
    }

    override fun showCreateSessionDialog(
        courseId: Long,
        onSessionCreated: (Long, String, Long) -> Unit
    ) {
        activity.runOnUiThread {
            sessionDialogFactory.showCreateSessionDialog(courseId, onSessionCreated)
        }
    }

    override fun showMassDateChangeDialog(courseId: Long) {
        activity.runOnUiThread {
            activeAlertDialog = sessionDialogFactory.showMassDateChangeDialog(courseId)
        }
    }

    override fun showDeleteSessionDialog(session: Session) {
        activity.runOnUiThread {
            activeAlertDialog = sessionDialogFactory.showDeleteSessionDialog(session)
        }
    }

    override fun importSessionsFromCsv(
        uri: Uri,
        courseId: Long,
        onImportComplete: () -> Unit
    ) {
        activity.importSessionController.importFromLocal(
            uri = uri,
            courseId = courseId,
            onImportComplete = onImportComplete
        )
    }
}
