package com.example.presensor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.isGone
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

import com.example.presensor.data.AppDatabase
import com.example.presensor.adapters.ImportStudentAdapter
import com.example.presensor.adapters.ImportPreviewAdapter
import com.example.presensor.adapters.StudentStatsAdapter
import com.example.presensor.controllers.DashboardController
import com.example.presensor.controllers.CourseController
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Attendance
import com.example.presensor.data.entities.AttendanceRecord

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private enum class AppState { DASHBOARD, COURSE, SESSION, COURSE_STATS }
    private var currentState = AppState.DASHBOARD

    private lateinit var db: AppDatabase
    private lateinit var dashboardController: DashboardController
    private lateinit var courseController: CourseController

    private var isDialogOpen = false
    private var nfcAdapter: NfcAdapter? = null
    private var activeSession: Session? = null

    private lateinit var currentBackCallback: OnBackPressedCallback
    private lateinit var dashboardView: View
    private lateinit var layoutCourseView: View
    private lateinit var layoutSessionView: View


    // Cache storage for the currently active course statistics state
    private var cachedActiveStudents: List<Student> = emptyList()
    private var cachedAllSessions: List<Session> = emptyList()
    private var cachedAllAttendance: List<AttendanceRecord> = emptyList()
    private var cachedSessionIds: List<Long> = emptyList()
    private var currentStatsView: View? = null



    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        val dbCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA cache_size = 4000;")
                db.execSQL("PRAGMA foreign_keys = ON;")
                db.execSQL("PRAGMA optimize;")
            }
        }

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "presensor-db")
            .addCallback(dbCallback)
            .fallbackToDestructiveMigration()
            .build()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val mainRoot = findViewById<LinearLayout>(R.id.layoutUniversalContainer)
        val statusBarBg = findViewById<View>(R.id.statusBarBackground)
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).top ?: 0
            val displayCutout = insets.displayCutout?.safeInsetTop ?: 0

            val bgParams = statusBarBg.layoutParams
            bgParams.height = statusBarHeight
            statusBarBg.layoutParams = bgParams

            mainRoot.setPadding(0, statusBarHeight, 0, maxOf(navBarHeight, displayCutout))
            WindowInsetsCompat.CONSUMED
        }

        dashboardView = findViewById(R.id.layoutDashboardView)
        layoutCourseView = findViewById(R.id.layoutCourseView)
        layoutSessionView = findViewById(R.id.layoutSessionView)

        // Initialize Dashboard Controller
        dashboardController = DashboardController(
            activity = this,
            db = db,
            scope = lifecycleScope,
            onCourseSelected = { course -> selectCourse(course) },
            onCourseLongClicked = { course -> showDeleteCourseDialog(course) },
            onDialogStateChanged = { isDialogOpen = it }
        )
        dashboardController.setupQuickActionsAccordion()
        dashboardController.setupOnClickListeners()

        // Initialize extracted Course Controller
        courseController = CourseController(
            activity = this,
            lifecycleOwner = this,
            selectedCourse = null,
            db = db,
            onSessionSelected = { session -> openSessionView(session) },
            onSessionLongClicked = { session -> showDeleteSessionDialog(session) },
            onToggleLockRequested = { session, imgLock -> handleLockingLogic(session, imgLock) },
            onOpenStatistics = { openCourseStatistics() }
        )

        findViewById<FloatingActionButton>(R.id.btnAddSession).setOnClickListener { showCreateSessionDialog() }

        activeSession = null
        toggleAllViews(layoutDashboardView = true)

        currentBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentState) {
                    AppState.SESSION -> {
                        activeSession = null
                        currentState = AppState.COURSE
                        toggleAllViews(layoutCourseView = true)
                        courseController.refreshCourseUI()
                    }
                    AppState.COURSE -> {
                        courseController.setSelectedCourse(null)
                        currentState = AppState.DASHBOARD
                        toggleAllViews(layoutDashboardView = true)
                        dashboardController.refreshDashboard()
                    }
                    AppState.COURSE_STATS -> {
                        currentState = AppState.COURSE
                        toggleAllViews(layoutCourseView = true)
                        courseController.refreshCourseUI()
                    }
                    AppState.DASHBOARD -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, currentBackCallback)
        dashboardController.refreshDashboard()
    }

    private fun selectCourse(course: Course) {
        courseController.setSelectedCourse(course)
        currentState = AppState.COURSE
        toggleAllViews(layoutCourseView = true)
        courseController.refreshCourseUI()
        loadDetailedCourseData()

    }

    private fun toggleAllViews(layoutDashboardView: Boolean = false, layoutCourseView: Boolean = false, layoutSessionView: Boolean = false, layoutCourseStatisticsView: Boolean = false) {
        findViewById<View>(R.id.layoutDashboardView).isVisible = layoutDashboardView
        findViewById<View>(R.id.layoutCourseView).isVisible = layoutCourseView
        findViewById<View>(R.id.layoutSessionView).isVisible = layoutSessionView
        findViewById<View>(R.id.layoutCourseStatisticsView).isVisible = layoutCourseStatisticsView
    }

    private fun handleLockToggle(sessionId: Long, newStatus: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.dao().updateSessionLock(sessionId, newStatus)
            withContext(Dispatchers.Main) {
                onComplete?.invoke(newStatus)
                if (currentState == AppState.COURSE) { courseController.refreshCourseUI() }
                val msg = if (newStatus) "Session Locked" else "Session Unlocked"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadAttendanceList(sessionId: Long) {
        val container = findViewById<LinearLayout>(R.id.attendanceContainer)

        lifecycleScope.launch {
            val records = db.dao().getAttendanceRecordsForSession(sessionId)
            container.removeAllViews()
            val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

            records.forEach { record ->
                val rowView = layoutInflater.inflate(R.layout.item_attendance_row, container, false)
                rowView.findViewById<TextView>(R.id.txtStudentInfo).text = record.studentName
                rowView.findViewById<TextView>(R.id.txtTimestamp).text = CourseUtilities.fromMillisToLocalDateTime(record.timestamp).format(timeFormat)
                container.addView(rowView)
            }
        }
    }

    private fun openSessionView(session: Session) {
        activeSession = session
        currentState = AppState.SESSION
        toggleAllViews(layoutSessionView = true)

        findViewById<TextView>(R.id.txtSessionTitle).text = session.name
        val dateFormat = CourseUtilities.makeSessionTimeFormatter()
        findViewById<TextView>(R.id.txtSessionSubtitle).text = CourseUtilities.fromMillisToLocalDate(session.date).format(dateFormat)

        val imgMasterLock = findViewById<ImageView>(R.id.imgMasterLock)
        findViewById<View>(R.id.viewSessionDetailAccent).setBackgroundColor(getColorForAccent(session.name))

        courseController.updateLockIconUI(session.isLocked, imgMasterLock)
        imgMasterLock.setOnClickListener { handleLockingLogic(activeSession!!, imgMasterLock) }
        loadAttendanceList(session.id)
    }

    private fun handleLockingLogic(session: Session, imgLock: ImageView) {
        if (session.isLocked) {
            val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }

            val dialog = AlertDialog.Builder(this)
                .setTitle("Unlock Session ${session.name}")
                .setMessage("Enter the session name:")
                .setView(input)
                .setPositiveButton("Unlock") { _, _ ->
                    if (input.text.toString() == session.name) {
                        handleLockToggle(session.id, false) { updatedStatus -> courseController.updateLockIconUI(updatedStatus, imgLock) }
                        if (activeSession != null) { activeSession = session.copy(isLocked = false) }
                    } else {
                        Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()

            dialog.setOnShowListener { isDialogOpen = true }
            dialog.setOnDismissListener { isDialogOpen = false }
            dialog.show()
        } else {
            handleLockToggle(session.id, true) { updatedStatus -> courseController.updateLockIconUI(updatedStatus, imgLock) }
            if (activeSession != null) { activeSession = session.copy(isLocked = true) }
        }
    }

    private fun showDeleteSessionDialog(session: Session) {
        DialogFactory.showDestructiveDeleteDialog(
            context = this,
            title = "Final Confirmation",
            message = "You are about to delete '${session.name}'. This action is permanent. Please type DELETE below:",
            isDialogOpenSetter = { isDialogOpen = it },
            onConfirmed = {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.dao().deleteAttendancesBySessionId(session.id)
                        db.dao().deleteSession(session)
                    }
                    courseController.refreshCourseUI()
                    Toast.makeText(this@MainActivity, "Session permanently removed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun showDeleteCourseDialog(course: Course) {
        DialogFactory.showDestructiveDeleteDialog(
            context = this,
            title = "Final Confirmation",
            message = "You are about to delete '${course.name}'. This action is permanent. Please type DELETE below:",
            isDialogOpenSetter = { isDialogOpen = it },
            onConfirmed = {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val sessions = db.dao().getSessionsByCourse(course.id)
                        sessions.forEach {
                            db.dao().deleteAttendancesBySessionId(it.id)
                            db.dao().deleteSession(it)
                        }
                        db.dao().deleteCourse(course)
                    }
                    dashboardController.refreshDashboard()
                    Toast.makeText(this@MainActivity, "Course permanently removed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    @ColorInt
    fun getColorFromAttr(@AttrRes attrColor: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrColor, typedValue, true)
        return typedValue.data
    }

    private fun loadDetailedCourseData() {
        val course = courseController.getSelectedCourse() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val nowMillis =
                LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            val sessionsDeferred = async(Dispatchers.IO) { db.dao().getSessionsByCourse(course.id) }

            val allSessions = sessionsDeferred.await().filter { it.date <= nowMillis }

            val sessionIds = allSessions.map { it.id }

            val attendanceDeferred = async(Dispatchers.IO) {
                db.dao().getAllAttendanceForCourse(course.id)
            }


            val allAttendance = attendanceDeferred.await()

            val attendeeEmails = allAttendance.map { it.studentEmail }.distinct()
            val activeStudents = db.dao().getAllStudents().filter { it.email in attendeeEmails }

            cachedActiveStudents = activeStudents
            cachedAllSessions = allSessions
            cachedAllAttendance = allAttendance
            cachedSessionIds = sessionIds
        }
    }

    private fun setupDetailedCourseView(statsView: View) {
        val course = courseController.getSelectedCourse() ?: return
        currentStatsView = statsView

        val detailedCourseSearchView = statsView.findViewById<SearchView>(R.id.searchStudentsAttendance)
        detailedCourseSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                refreshCourseAttendanceList(newText ?: "")
                return true
            }
        })

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {


                val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
                rv.layoutManager = LinearLayoutManager(this@MainActivity)

                // Instantiated EXACTLY once
                rv.adapter = StudentStatsAdapter(
                    cachedActiveStudents, cachedAllSessions, cachedAllAttendance, cachedSessionIds,
                    getColorFromAttr = { attr -> getColorFromAttr(attr) },
                    makeSessionTimeFormatter = { CourseUtilities.makeSessionTimeFormatter() },
                    fromMillisToLocalDate = { ms -> CourseUtilities.fromMillisToLocalDate(ms) }
                )
            }
        }
    }

    private fun refreshCourseAttendanceList(filter: String = "") {
        val statsView = currentStatsView ?: return

        val filteredStudents = if (filter.isEmpty()) {
            cachedActiveStudents
        } else {
            cachedActiveStudents.filter { it.name.contains(filter, ignoreCase = true) }
        }

        val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)

        // Safely cast the existing adapter and update its list internal pointers instantly
        (rv.adapter as? StudentStatsAdapter)?.updateData(filteredStudents)
    }

    private fun openCourseStatistics() {
        val course = courseController.getSelectedCourse() ?: return
        currentState = AppState.COURSE_STATS
        val container = findViewById<LinearLayout>(R.id.layoutCourseStatisticsView)
        container.removeAllViews()
        toggleAllViews()

        val statsView = layoutInflater.inflate(R.layout.layout_course_statistics, container, false)
        container.addView(statsView)
        setupDetailedCourseView(statsView)
        refreshCourseAttendanceList()
        courseController.fillCourseDetailedCardStatistics(statsView, course, cachedSessionIds, cachedActiveStudents.map{it.email}, cachedAllAttendance)
        toggleAllViews(layoutCourseStatisticsView = true)
    }

    private fun getColorForAccent(courseName: String): Int {
        val typedArray = resources.obtainTypedArray(R.array.chalk_colors_list)
        val colors = IntArray(typedArray.length())
        for (i in 0 until typedArray.length()) {
            colors[i] = typedArray.getColor(i, 0)
        }
        typedArray.recycle()
        return colors[Math.abs(courseName.hashCode()) % colors.size]
    }

    private fun showCreateSessionDialog() {
        val courseId = courseController.getSelectedCourse()!!.id
        lifecycleScope.launch {
            val count = db.dao().getSessionsByCourse(courseId).size + 1
            withContext(Dispatchers.Main) {
                DialogFactory.showCreateSessionDialog(
                    context = this@MainActivity,
                    layoutInflater = layoutInflater,
                    fragmentManager = supportFragmentManager,
                    defaultSessionName = "Class $count",
                    isDialogOpenSetter = { isDialogOpen = it },
                    onSessionCreated = { sessionName, dateMillis ->
                        lifecycleScope.launch {
                            db.dao().insertSession(
                                Session(
                                    courseId = courseId,
                                    name = sessionName,
                                    date = CourseUtilities.fromMillisToLocalDate(dateMillis)
                                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                )
                            )
                            courseController.refreshCourseUI()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A, null)
    }


    override fun onTagDiscovered(tag: Tag) {
        val rfid = tag.id.joinToString(":") { "%02X".format(it) }
        val time = System.currentTimeMillis()

        if (isDialogOpen) return

        lifecycleScope.launch {
            val student = db.dao().getStudentByRfid(rfid)
            runOnUiThread {
                if (activeSession != null) {
                    if (activeSession!!.isLocked) {
                        Toast.makeText(this@MainActivity, "Session Locked", Toast.LENGTH_SHORT).show()
                    } else if (student != null) {
                        lifecycleScope.launch {
                            saveAndLogAttendance(student.rfid, time)
                            loadAttendanceList(activeSession!!.id)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Tag not registered", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (student != null) {
                        showOverwriteConfirmation(student, rfid)
                    } else {
                        showBindingDialog(rfid)
                    }
                }
            }
        }
    }

    private fun showReassignConfirmation(student: Student, rfid: String, onComplete: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Overwrite Tag?")
            .setMessage("${student.name} is already linked to tag ${student.rfid}.\n\nDo you want to replace it with the new tag $rfid?")
            .setPositiveButton("Replace") { _, _ ->
                bindTag(rfid, student.email)
                onComplete()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener { isDialogOpen = true }
                setOnDismissListener { isDialogOpen = false }
            }.show()
    }

    private fun showBindingDialog(newRfid: String) {
        lifecycleScope.launch {
            val allStudents = db.dao().getAllStudents().sortedBy { it.name }

            withContext(Dispatchers.Main) {
                val dialogView = layoutInflater.inflate(R.layout.dialog_search_student, null)
                val edtSearch = dialogView.findViewById<EditText>(R.id.edtStudentSearch)
                val container = dialogView.findViewById<LinearLayout>(R.id.studentListContainer)
                var bindingDialog: AlertDialog? = null

                fun refreshList(query: String) {
                    container.removeAllViews()
                    val filtered = allStudents.filter {
                        it.name.contains(query, true) || it.email.contains(query, true)
                    }

                    filtered.forEach { student ->
                        val hasTag = !student.rfid.isNullOrEmpty()
                        val row = TextView(this@MainActivity).apply {
                            text = if (hasTag) "${student.name} - ${student.email} - [${student.rfid}]"
                            else "${student.name} - ${student.email} - [No tag]"
                            textSize = 16f
                            setPadding(30, 30, 30, 30)
                            alpha = if (hasTag) 0.6f else 1.0f

                            setOnClickListener {
                                if (hasTag) {
                                    showReassignConfirmation(student, newRfid) { bindingDialog?.dismiss() }
                                } else {
                                    bindTag(newRfid, student.email)
                                    bindingDialog?.dismiss()
                                }
                            }
                        }
                        container.addView(row)

                        val line = View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                            setBackgroundColor(Color.LTGRAY)
                        }
                        container.addView(line)
                    }
                }

                refreshList("")
                bindingDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Assign Tag: $newRfid")
                    .setView(dialogView)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Manual Entry") { _, _ ->
                        runOnUiThread { showRegistrationDialog(newRfid) }
                    }
                    .create()

                bindingDialog.setOnShowListener { isDialogOpen = true }
                bindingDialog.setOnDismissListener { isDialogOpen = false }
                bindingDialog.show()

                edtSearch.addTextChangedListener { refreshList(it.toString()) }
            }
        }
    }

    private fun bindTag(rfid: String, email: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.dao().clearAndBind(rfid, email) }
            withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Tag successfully assigned!", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun showOverwriteConfirmation(existingStudent: Student, newRfid: String) {
        AlertDialog.Builder(this)
            .setTitle("Tag Already Registered")
            .setMessage("This tag is currently assigned to ${existingStudent.name} (${existingStudent.email}).\n\nDo you want to unbind this tag so it can be reassigned?")
            .setPositiveButton("Yes") { _, _ ->
                lifecycleScope.launch {
                    db.dao().bindTagToStudent(null, existingStudent.email)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Tag unbound", Toast.LENGTH_SHORT).show()
                        showBindingDialog(newRfid)
                    }
                }
            }
            .setNegativeButton("No", null)
            .create()
            .apply {
                setOnShowListener { isDialogOpen = true }
                setOnDismissListener { isDialogOpen = false }
            }.show()
    }

    private fun showRegistrationDialog(rfid: String) {
        DialogFactory.showManualRegistrationDialog(
            context = this,
            rfid = rfid,
            isDialogOpenSetter = { isDialogOpen = it },
            onStudentSaved = { name, email, dialog ->
                lifecycleScope.launch {
                    db.dao().insertStudents(listOf(Student(email = email, name = name, rfid = rfid)))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Registered $name", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
        )
    }

    private suspend fun saveAndLogAttendance(rfid: String?, time: Long) {
        if (rfid == null) return
        withContext(Dispatchers.IO) {
            val email = db.dao().getStudentByRfid(rfid)!!.email
            db.dao().recordAttendance(Attendance(rfid = rfid, sessionId = activeSession!!.id, studentEmail = email, timestamp = time))
        }
    }


}