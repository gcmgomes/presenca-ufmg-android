package com.example.presensor.old

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Attendance
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Locale

class OldMainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private enum class AppState { DASHBOARD, COURSE, SESSION , COURSE_STATS}
    private var currentState = AppState.DASHBOARD

    private lateinit var db: AppDatabase

    private var isDialogOpen = false
    private var nfcAdapter: NfcAdapter? = null
    private var selectedCourse: Course? = null
    private var activeSession: Session? = null

    private lateinit var currentBackCallback: OnBackPressedCallback
    private lateinit var dashboardView: View
    private lateinit var layoutCourseView: View
    private lateinit var layoutSessionView: View
    private lateinit var sessionContainer: LinearLayout
    private lateinit var scanLogContainer: LinearLayout
    private lateinit var btnExportCourse: Button

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        // The 'uri' here is the location the user picked to save the file
        uri?.let {
            performExport(it)
        }
    }



    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        val dbCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Increase the cache size to 4000 pages (approx 16MB)
                // A larger cache keeps more of your Session and Attendance data in RAM
                db.execSQL("PRAGMA cache_size = 4000;")

                // Ensure Foreign Key constraints are enforced
                db.execSQL("PRAGMA foreign_keys = ON;")

                // Optional: Optimize the database on opening
                db.execSQL("PRAGMA optimize;")
            }
        }

        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "presensor-db"
        )
            .addCallback(dbCallback) // Attach your custom logic here
            .fallbackToDestructiveMigration()
            .build()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val mainRoot = findViewById<LinearLayout>(R.id.layoutUniversalContainer)
        val statusBarBg = findViewById<View>(R.id.statusBarBackground)
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).top ?: 0
            val displayCutout = insets.displayCutout?.safeInsetTop ?: 0


            // 1. Set the height of the background filler
            val bgParams = statusBarBg.layoutParams
            bgParams.height = statusBarHeight
            statusBarBg.layoutParams = bgParams

            // 2. Pad the content so it starts BELOW the status bar
            // We apply this to the root or a specific container holding your includes
            mainRoot.setPadding(0, statusBarHeight, 0, maxOf(navBarHeight, displayCutout))

            // Return CONSUMED so the insets don't "bubble up" to child views
            // and cause double-padding issues
            WindowInsetsCompat.CONSUMED
        }

        dashboardView = findViewById(R.id.dashboardView)
        setupQuickActionsAccordion()

        val searchView = findViewById<SearchView>(R.id.courseSearchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                // Refresh the dashboard passing the current search string
                refreshDashboard(newText ?: "")
                return true
            }
        })


        layoutCourseView = findViewById(R.id.layoutCourseView)
        setupCourseUtilsAccordion()

        layoutSessionView = findViewById(R.id.layoutSessionView)

        findViewById<Button>(R.id.btnImportStudents).setOnClickListener {
            triggerStudentImportPicker()
        }


        sessionContainer = findViewById(R.id.sessionContainer)
        btnExportCourse = findViewById(R.id.btnExportCourse)

        selectedCourse = null
        activeSession = null
        findViewById<View>(R.id.dashboardView).visibility = View.VISIBLE
        findViewById<View>(R.id.layoutCourseView).visibility = View.GONE

        findViewById<FloatingActionButton>(R.id.btnAddSession).setOnClickListener { showCreateSessionDialog() }
        btnExportCourse.setOnClickListener {
            val courseName = selectedCourse?.name ?: "Attendance"
            val fileName = "Attendance_${courseName.replace(" ", "_")}.csv"

            // This opens the system file picker to "Save As"
            exportLauncher.launch(fileName)
        }

        findViewById<Button>(R.id.btnCourseStats).setOnClickListener {
            openCourseStatistics()
        }

        findViewById<FloatingActionButton>(R.id.btnCreateCourse).setOnClickListener {
            showCreateCourseDialog()
        }

        // Back Button Logic
        // Inside onCreate
        // Inside your onCreate
        currentBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentState) {
                    AppState.SESSION -> {
                        // Step 1: Update State
                        activeSession = null
                        currentState = AppState.COURSE

                        toggleAllViews(layoutCourseView = true)
                        // Step 2: Re-inflate the main layout because it was destroyed

                        // Step 3: Re-bind all listeners for Dashboard/Course views
                        rebindMainUI()

                        // Step 4: Manually show the Course View

                        loadSessionsFromDb()
                    }

                    AppState.COURSE -> {
                        selectedCourse = null
                        currentState = AppState.DASHBOARD

                        toggleAllViews(layoutDashboardView = true)

                        refreshDashboard()
                    }


                    AppState.COURSE_STATS -> {
                        currentState = AppState.COURSE

                        toggleAllViews(layoutCourseView = true)

                        loadSessionsFromDb()
                    }

                    AppState.DASHBOARD -> {
                        // Standard exit behavior
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        }

        // Add it to the dispatcher
        onBackPressedDispatcher.addCallback(this, currentBackCallback)


        refreshDashboard()

    }


    private fun importSessionsFromCsv(uri: Uri, courseId: Long) {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sessionsToInsert = mutableListOf<Session>()
                val inputStream = contentResolver.openInputStream(uri) ?: return@launch

                inputStream.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val tokens = line.split(Regex("[,;]")).map { it.trim() }

                        if (tokens.size >= 2) {
                            var sessionName = ""
                            var localDate: LocalDate? = null

                            // Try to figure out which column is the date
                            val firstTokenDate = tryParseDate(tokens[0], formatter)
                            val secondTokenDate = tryParseDate(tokens[1], formatter)

                            when {
                                // Scenario: [Date, Name]
                                firstTokenDate != null -> {
                                    localDate = firstTokenDate
                                    sessionName = tokens[1]
                                }
                                // Scenario: [Name, Date]
                                secondTokenDate != null -> {
                                    localDate = secondTokenDate
                                    sessionName = tokens[0]
                                }
                            }

                            if (localDate != null && sessionName.isNotEmpty()) {
                                val timestamp = localDate.atStartOfDay(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()

                                sessionsToInsert.add(
                                    Session(
                                        courseId = courseId,
                                        name = sessionName,
                                        date = timestamp // Using 'date' as per your MainActivity.kt
                                    )
                                )
                            }
                        }
                    }
                }

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

    // Helper function to safely check for a date
    private fun tryParseDate(text: String, formatter: DateTimeFormatter): LocalDate? {
        return try {
            LocalDate.parse(text, formatter)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    class ImportPreviewAdapter(private val sessions: List<Session>) :
        RecyclerView.Adapter<ImportPreviewAdapter.ViewHolder>() {

        private val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            .withZone(ZoneId.systemDefault())

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.txtSessionName)
            val dateText: TextView = view.findViewById(R.id.txtSessionDate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_import_session_preview, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val session = sessions[position]
            holder.nameText.text = session.name

            // Convert the Long timestamp back to a readable date string
            val instant = Instant.ofEpochMilli(session.date)
            holder.dateText.text = formatter.format(instant)
        }

        override fun getItemCount() = sessions.size
    }

    private fun showImportPreview(sessions: List<Session>) {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_import_session_preview, null)
        bottomSheet.setContentView(view)


        val recyclerView = view.findViewById<RecyclerView>(R.id.rvImportPreview)
        val txtImportCount = view.findViewById<TextView>(R.id.txtImportCount)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmImport)

        // Set up a simple adapter to show "Name - DD/MM/YYYY"
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ImportPreviewAdapter(sessions)
        txtImportCount.text = "Found ${sessions.size} sessions."

        btnConfirm.setOnClickListener {
            lifecycleScope.launch {
                db.dao().insertSessions(sessions)
            }
            bottomSheet.dismiss()
            loadSessionsFromDb()
            Toast.makeText(this@OldMainActivity, "Imported ${sessions.size} sessions", Toast.LENGTH_SHORT).show()
        }

        bottomSheet.show()
    }

    private fun toggleAllViews(layoutDashboardView: Boolean = false, layoutCourseView: Boolean = false, layoutSessionView: Boolean = false, layoutCourseStatisticsView: Boolean = false) {
        findViewById<View>(R.id.layoutDashboardView).isVisible = layoutDashboardView
        findViewById<View>(R.id.layoutCourseView).isVisible = layoutCourseView
        findViewById<View>(R.id.layoutSessionView).isVisible = layoutSessionView
        findViewById<View>(R.id.layoutCourseStatisticsView).isVisible = layoutCourseStatisticsView

    }

    private fun AlertDialog.Builder.showWithSmartNfcReading(): AlertDialog {
        val dialog = this.create()
        dialog.setOnShowListener { isDialogOpen = true }
        dialog.setOnDismissListener { isDialogOpen = false }
        dialog.show()
        return dialog
    }

    private fun rebindMainUI() {
        // Re-assign basic view references
        dashboardView = findViewById(R.id.dashboardView)
        layoutCourseView = findViewById(R.id.layoutCourseView)
        layoutSessionView = findViewById(R.id.layoutSessionView)

        // Re-bind buttons
        findViewById<FloatingActionButton>(R.id.btnCreateCourse).setOnClickListener { showCreateCourseDialog() }
        findViewById<Button>(R.id.btnImportStudents).setOnClickListener { triggerStudentImportPicker() }
        findViewById<ImageButton>(R.id.btnAddSession).setOnClickListener { showCreateSessionDialog() }

        // Re-setup Search
        val searchView = findViewById<SearchView>(R.id.courseSearchView)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                refreshDashboard(newText ?: "")
                return true
            }
        })

        setupQuickActionsAccordion()
    }

    // The 'onComplete' parameter is a lambda (a function passed as a variable)
// We set it to 'null' by default so you don't HAVE to provide it every time.
    private fun handleLockToggle(
        sessionId: Long,
        newStatus: Boolean,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Update the Database
            db.dao().updateSessionLock(sessionId, newStatus)

            withContext(Dispatchers.Main) {
                // 2. Run the specific UI update code if it was provided
                onComplete?.invoke(newStatus)

                // 3. Refresh the underlying dashboard data
                if (currentState == AppState.COURSE) {
                    loadSessionsFromDb()
                }

                val msg = if (newStatus) "Session Locked" else "Session Unlocked"
                Toast.makeText(this@OldMainActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSessionsFromDb() {
        val course = selectedCourse ?: return
        toggleAllViews()

        lifecycleScope.launch {
            // 1. Fetch both datasets in parallel/sequentially on IO thread
            val sessionsDeferred = async(Dispatchers.IO) {
                db.dao().getSessionsByCourse(course.id)
            }
            val attendanceDeferred = async(Dispatchers.IO) {
                db.dao().getAllAttendanceForCourse(course.id)
            }

            // Wait for both to finish (this effectively halves your wait time)
            val sessions = sessionsDeferred.await().sortedByDescending { it.date }
            val allAttendance = attendanceDeferred.await()

            // 2. Identify "past" sessions for the stats calculation
            val nowMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pastSessionIds = sessions.filter { it.date <= nowMillis }.map { it.id }.toSet()

            // 3. Get unique students who have attended at least one session in this course

            // 4. Update the UI - No more flickering because data is ready
            refreshSessionsList(sessions)

            val headerCard = findViewById<View>(R.id.layoutCourseView)
            headerCard.visibility = View.GONE
            val sessionIds = sessions.filter{ it.date <= nowMillis }.map{it.id}
            val attendeeEmails = allAttendance.filter { pastSessionIds.contains(it.sessionId)}.map{it.studentEmail}.distinct()

            fillCourseDetailedCardStatistics(headerCard, sessionIds,attendeeEmails, allAttendance)
            toggleAllViews(layoutCourseView = true)
            // Ensure the header card is updated with the pre-calculated lists
        }
    }

    /**
     * Pure UI helper to set the correct icon and alpha
     */
    private fun updateLockIconUI(isLocked: Boolean, lockIcon: ImageView) {
        if (isLocked) {
            lockIcon.setImageResource(R.drawable.status_lock)
            lockIcon.alpha = 1.0f
        } else {
            lockIcon.setImageResource(R.drawable.status_unlock)
            lockIcon.alpha = 0.5f // Dimmed look for 'unlocked'
        }
    }


    private fun setupCourseUtilsAccordion() {
        // 1. Find the views
        // We use the parent container as the root to ensure everything moves in sync
        val cardRoot = findViewById<LinearLayout>(R.id.layoutInnerCourseView)
        val expandableContent = findViewById<LinearLayout>(R.id.layoutUtilsContent)
        val arrowIcon = findViewById<ImageView>(R.id.imgUtilsExpandIcon)
        val headerClickArea = findViewById<RelativeLayout>(R.id.layoutUtilsHeader)

        // Safety check to prevent crashes if IDs are missing
        if (cardRoot == null || arrowIcon == null || expandableContent == null) {
            Log.e("Presensor", "Course Utils Accordion Error: One or more views not found!")
            return
        }

        headerClickArea.setOnClickListener {
            // 2. Define the smooth physics for the transition
            val animationDuration = 300L

            val transition = TransitionSet().apply {
                addTransition(ChangeBounds()) // Animates the height/size change
                addTransition(Fade())         // Animates the buttons appearing/disappearing
                ordering = TransitionSet.ORDERING_TOGETHER
                duration = animationDuration

                // This defines the "rate of change" (Fast start, soft landing)
                interpolator = FastOutSlowInInterpolator()
            }

            // 3. Inform the system to animate changes within this container
            TransitionManager.beginDelayedTransition(cardRoot, transition)

            // 4. Execute the changes
            if (expandableContent.isGone) {
                // Opening
                expandableContent.visibility = View.VISIBLE
                arrowIcon.animate()
                    .rotation(180f)
                    .setDuration(animationDuration)
                    .start()
            } else {
                // Closing
                expandableContent.visibility = View.GONE
                arrowIcon.animate()
                    .rotation(0f)
                    .setDuration(animationDuration)
                    .start()
            }
        }
    }
    private fun setupQuickActionsAccordion() {
        // 1. Find the layouts.
        // Even though they are in an 'include', if you are in the same Activity
        // and used activity_main.xml, they are accessible directly.

        val includedDashboard = findViewById<ViewGroup>(R.id.layoutDashboardView)
        Log.d("", "includedDashboard is null? ans: ${includedDashboard == null}")
// Now look INSIDE the dashboard for the root container
        val expandableLayout = includedDashboard.findViewById<LinearLayout>(R.id.layoutActionsContent)
        val arrowIcon = includedDashboard.findViewById<ImageView>(R.id.imgExpandArrow)
        val headerClickArea = findViewById<RelativeLayout>(R.id.layoutDashboardActionsHeader)

        // SAFETY CHECK: If dashboardRoot is null, we stop here to prevent the crash
        if (includedDashboard == null) {
            Log.e("Presensor", "Accordion Error: rootDashboardContainer not found!")
            return
        }

        headerClickArea.setOnClickListener {
            // 2. Define the smooth physics for the transition
            val animationDuration = 300L

            val transition = TransitionSet().apply {
                addTransition(ChangeBounds()) // Animates the height/size change
                addTransition(Fade())         // Animates the buttons appearing/disappearing
                ordering = TransitionSet.ORDERING_TOGETHER
                duration = animationDuration

                // This defines the "rate of change" (Fast start, soft landing)
                interpolator = FastOutSlowInInterpolator()
            }

            // 3. Inform the system to animate changes within this container
            TransitionManager.beginDelayedTransition(includedDashboard, transition)

            // 4. Execute the changes
            if (expandableLayout.isGone) {
                // Opening
                expandableLayout.visibility = View.VISIBLE
                arrowIcon.animate()
                    .rotation(180f)
                    .setDuration(animationDuration)
                    .start()
            } else {
                // Closing
                expandableLayout.visibility = View.GONE
                arrowIcon.animate()
                    .rotation(0f)
                    .setDuration(animationDuration)
                    .start()
            }
        }
    }

    private fun loadAttendanceList(sessionId: Long) {
        val container = findViewById<LinearLayout>(R.id.attendanceContainer)

        lifecycleScope.launch {
            // 1. Fetch the logs
            val records = db.dao().getAttendanceRecordsForSession(sessionId)

            // 2. Clear previous views
            container.removeAllViews()

            // 4. Populate the list
            val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())

            records.forEach { record ->
                val rowView = layoutInflater.inflate(R.layout.item_attendance_row, container, false)

                val txtStudentInfo = rowView.findViewById<TextView>(R.id.txtStudentInfo)
                val txtTimestamp = rowView.findViewById<TextView>(R.id.txtTimestamp)

                // If you have student names, you'd look them up here.
                // For now, we display the email or RFID and the time they scanned.
                txtStudentInfo.text = record.studentName // + " - " + record.studentEmail
                txtTimestamp.text = fromMillisToLocalDateTime(record.timestamp).format(timeFormat)

                container.addView(rowView)
            }
        }
    }

    private fun openSessionView(session: Session) {
        activeSession = session
        currentState = AppState.SESSION

        // Inflate the session layout
        toggleAllViews(layoutSessionView = true)

        // Bind views (Title and Master Lock)
        val txtTitle = findViewById<TextView>(R.id.txtSessionTitle)
        val txtSub = findViewById<TextView>(R.id.txtSessionSubtitle)
        val imgMasterLock = findViewById<ImageView>(R.id.imgMasterLock)
        val accent_background = findViewById<View>(R.id.viewSessionDetailAccent)
        val color = getColorForAccent(session.name)
        accent_background.setBackgroundColor(color)



        txtTitle.text = session.name
        val dateFormat = makeSessionTimeFormatter()
        txtSub.text = fromMillisToLocalDate(session.date).format(dateFormat)


        updateLockIconUI(session.isLocked, imgMasterLock)

        imgMasterLock.setOnClickListener {
                handleLockingLogic(activeSession!!, imgMasterLock)
        }

        loadAttendanceList(session.id)
    }

    private fun handleLockingLogic(session: Session, imgLock: ImageView) {
        if (session.isLocked) {
            // If it's locked, ask for a password to unlock
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_TEXT

            AlertDialog.Builder(this)
                .setTitle("Unlock Session ${session.name}")
                .setMessage("Enter the session name:")
                .setView(input)
                .setPositiveButton("Unlock") { _, _ ->
                    val password = input.text.toString()
                    if (password == session.name) { // Replace with your actual logic
                        handleLockToggle(session.id, false) { updatedStatus ->
                            updateLockIconUI(updatedStatus, imgLock)
                        }
                        if(activeSession != null) {
                            activeSession = session.copy(isLocked = false)
                        }
                    } else {
                        Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .showWithSmartNfcReading()
        } else {
            // If it's currently unlocked, just lock it immediately
            handleLockToggle(session.id, true) { updatedStatus ->
                updateLockIconUI(updatedStatus, imgLock)
            }
            if(activeSession != null) {
                activeSession = session.copy(isLocked = true)
            }
        }
    }

    private fun showDeleteSessionDialog(session: Session) {
        // 1. Create a container for the input field
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }

        // 2. Add the text input
        val input = EditText(this).apply {
            hint = "Type 'DELETE' to confirm"
            setSingleLine(true)
            // Use a theme-compliant text color
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Final Confirmation")
            .setMessage("You are about to delete '${session.name}'. This action is permanent. Please type DELETE below:")
            .setView(container)
            .setPositiveButton("Confirm", null) // Set to null first to handle logic manually
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading()

        // 3. Override the "Confirm" button so it only works if the word matches
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val confirmationText = input.text.toString().trim()

            if (confirmationText.equals("DELETE", ignoreCase = false)) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.dao().deleteAttendancesBySessionId(session.id)
                        db.dao().deleteSession(session)
                    }
                    loadSessionsFromDb()
                    dialog.dismiss()
                    Toast.makeText(this@OldMainActivity, "Session permanently removed", Toast.LENGTH_SHORT).show()
                }
            } else {
                input.error = "Text must match exactly"
            }
        }
    }

    private fun showDeleteCourseDialog(course: Course) {
        // 1. Create a container for the input field
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }

        // 2. Add the text input
        val input = EditText(this).apply {
            hint = "Type 'DELETE' to confirm"
            setSingleLine(true)
            // Use a theme-compliant text color
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Final Confirmation")
            .setMessage("You are about to delete '${course.name}'. This action is permanent. Please type DELETE below:")
            .setView(container)
            .setPositiveButton("Confirm", null) // Set to null first to handle logic manually
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading()

        // 3. Override the "Confirm" button so it only works if the word matches
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val confirmationText = input.text.toString().trim()

            if (confirmationText.equals("DELETE", ignoreCase = false)) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val sessions = db.dao().getSessionsByCourse(course.id)
                        sessions.forEach {
                            db.dao().deleteAttendancesBySessionId(it.id)
                            db.dao().deleteSession(it)
                        }
                        db.dao().deleteCourse(course)
                    }
                    refreshDashboard()
                    dialog.dismiss()
                    Toast.makeText(this@OldMainActivity, "Course permanently removed", Toast.LENGTH_SHORT).show()
                }
            } else {
                input.error = "Text must match exactly"
            }
        }
    }

    private fun addSessionsToCourseView(container: LinearLayout, title: String, sessions: List<Session>) {
        if (sessions.isNotEmpty()) {
            addSectionHeader(container, title)
            sessions.forEach { addSessionCardToContainer(container, it) }
        }
    }

    private fun makeSessionTimeFormatter(): DateTimeFormatter {
        return DateTimeFormatter.ofPattern("EEEE, dd 'de' MMMM", Locale.getDefault())
    }

    private fun addSessionCardToContainer(container: LinearLayout, session: Session) {
        val itemView = layoutInflater.inflate(R.layout.item_session_card, container, false)
        val dateFormat = makeSessionTimeFormatter()

        val txtName = itemView.findViewById<TextView>(R.id.txtSessionName)
        val txtDetails = itemView.findViewById<TextView>(R.id.txtSessionDetails)
        val imgLock = itemView.findViewById<ImageView>(R.id.imgSessionLockOnSessionView)

        val accent_background = itemView.findViewById<View>(R.id.viewSessionAccent)

        // Use your pretty color logic
        val color = getColorForAccent(session.name)
        accent_background.setBackgroundColor(color)


        txtName.text = session.name
        txtDetails.text = fromMillisToLocalDate(session.date).format(dateFormat)

        // UI Initial State
        updateLockIconUI(session.isLocked, imgLock)

        // The Click Logic
        imgLock.setOnClickListener {
            handleLockingLogic(session, imgLock)
        }

        itemView.setOnClickListener {
            // This is the function we built in the previous step
            openSessionView(session)
        }

        itemView.setOnLongClickListener {
            showDeleteSessionDialog(session)
            true // returns true to indicate the click was consumed
        }

        container.addView(itemView)
    }


    fun isDateInCurrentWeek(targetDate: LocalDate): Boolean {
        val today = LocalDate.now()

        // Define the start of the week (e.g., Monday)
        val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

        // Define the end of the week (e.g., Sunday)
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))

        // Check if the target date is within the range [startOfWeek, endOfWeek]
        return !targetDate.isBefore(startOfWeek) && !targetDate.isAfter(endOfWeek)
    }

    private fun fromMillisToLocalDate(date: Long): LocalDate {
        return Instant.ofEpochMilli(date)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }

    private fun fromMillisToLocalDateTime(millis: Long): LocalDateTime {
        return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(millis),
            ZoneId.systemDefault()
        )
    }

    private fun refreshSessionsList(sessions: List<Session>) {
        val container = findViewById<LinearLayout>(R.id.sessionContainer)
        container.removeAllViews()

        toggleAllViews()

        val thisWeekSessions = sessions.filter {
            isDateInCurrentWeek(fromMillisToLocalDate(it.date))
        }

        val upcomingSessions = sessions.filter {
            !isDateInCurrentWeek(fromMillisToLocalDate(it.date)) &&
                    fromMillisToLocalDate(it.date).isAfter(LocalDate.now())
        }

        val pastSessions = sessions.filter {
            !isDateInCurrentWeek(fromMillisToLocalDate(it.date)) &&
                    fromMillisToLocalDate(it.date).isBefore(LocalDate.now())
        }.sortedBy { it.date }

        // Build the UI as before
        addSessionsToCourseView(container, "This week", thisWeekSessions)
        addSessionsToCourseView(container, "Upcoming sessions", upcomingSessions)
        addSessionsToCourseView(container, "Past sessions", pastSessions)

    }
    private fun selectCourse(course: Course) {
        selectedCourse = course
        currentState = AppState.COURSE

        toggleAllViews()

        // RE-BIND VIEWS after setContentView
        val btnAdd = findViewById<ImageButton>(R.id.btnAddSession)
        btnAdd.setOnClickListener { showCreateSessionDialog() }
        val btnImport = findViewById<MaterialButton>(R.id.btnImportSchedule)
        btnImport.setOnClickListener {
            triggerImportSessionPicker()
        }

        loadSessionsFromDb()
    }

    @ColorInt
    fun getColorFromAttr(@AttrRes attrColor: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrColor, typedValue, true)
        return typedValue.data
    }

    private fun fillCourseDetailedCardStatistics(card: View, sessionIds: List<Long>, studentEmails: List<String>, courseAttendances: List<AttendanceRecord>) {
        // 1. Basic Course Info
        // These assume selectedCourse is available or you can pull from the lists
        selectedCourse?.let {
            card.findViewById<TextView>(R.id.txtDetailCourseName).text = it.name
            card.findViewById<TextView>(R.id.txtDetailCourseSemester).text = formatYearSemester(it.year, it.semester)

            // Apply the color accent from the course if you have it stored
            val accentView = card.findViewById<View>(R.id.viewCourseDetailAccent)
            accentView.setBackgroundColor(getColorForAccent(it.name))
        }


        // 2. Statistical Calculations
        val studentCount = studentEmails.size
        val sessionCount = sessionIds.size

        // Fetch all attendance for these specific sessions to calculate the average


        // Average Attendance calculation:
        // (Total Logs) / (Total possible Logs [Students * Sessions])
        val avgAttendance = if (studentCount > 0 && sessionCount > 0) {
            val totalPossible = studentCount * sessionCount
            val actualLogs = courseAttendances.map { it.sessionId to it.studentEmail }.distinct().size
            (actualLogs.toFloat() / totalPossible.toFloat() * 100).toInt()
        } else {
            0
        }

        // 3. UI Updates
        card.findViewById<TextView>(R.id.txtStatStudentCount).text = studentCount.toString()
        card.findViewById<TextView>(R.id.txtStatSessionCount).text = sessionCount.toString()
        card.findViewById<TextView>(R.id.txtStatAvgAttendance).text = "$avgAttendance%"
    }

    private fun openCourseStatistics() {
        val course = selectedCourse ?: return
        currentState = AppState.COURSE_STATS
        val container = findViewById<LinearLayout>(R.id.layoutCourseStatisticsView)

        toggleAllViews(layoutCourseStatisticsView = true)

        // 1. Clear the current Course Detail UI
        container.removeAllViews()

        // 2. Inflate the Statistics Layout
        val statsView = layoutInflater.inflate(R.layout.layout_course_statistics, container, false)
        container.addView(statsView)


        // 3. Setup the Header (Using the included header card)


        // 4. Load Data and Setup RecyclerView
        lifecycleScope.launch(Dispatchers.IO) {
            val nowMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val allSessions = db.dao().getSessionsByCourse(course.id).filter { it.date <= nowMillis }
            val sessionIds = allSessions.map { it.id }

            // Get all attendance for these sessions
            val allAttendance = mutableListOf<AttendanceRecord>()
            sessionIds.forEach { sid ->
                allAttendance.addAll(db.dao().getAttendanceRecordsForSession(sid))
            }

            // Filter students who attended at least one session
            val attendeeEmails = allAttendance.map { it.studentEmail }.distinct()
            val activeStudents = db.dao().getAllStudents().filter { it.email in attendeeEmails }

            fillCourseDetailedCardStatistics(statsView, sessionIds,attendeeEmails, allAttendance)

            withContext(Dispatchers.Main) {
                val rv = statsView.findViewById<RecyclerView>(R.id.rvStudentStats)
                rv.layoutManager = LinearLayoutManager(this@OldMainActivity)

                // We use an anonymous implementation of the Adapter to keep it in this file
                rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

                    inner class StudentViewHolder(v: View) : RecyclerView.ViewHolder(v) {
                        val name = v.findViewById<TextView>(R.id.txtStatStudentName)
                        val email = v.findViewById<TextView>(R.id.txtStatStudentEmail)
                        val container = v.findViewById<LinearLayout>(R.id.layoutExpandedSessions)
                        val percentage = v.findViewById<TextView>(R.id.txtStatAttendancePercent)
                        val root = v.findViewById<View>(R.id.cardStudentRoot)
                    }

                    override fun onCreateViewHolder(
                        parent: ViewGroup,
                        viewType: Int
                    ): RecyclerView.ViewHolder {
                        return StudentViewHolder(
                            layoutInflater.inflate(
                                R.layout.item_student_stat_card,
                                parent,
                                false
                            )
                        )
                    }

                    override fun getItemCount() = activeStudents.size

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val student = activeStudents[position]
                        val h = holder as StudentViewHolder
                        h.name.text = student.name
                        h.email.text = student.email

                        val studentAttendanceSet =
                            allAttendance.filter { it.studentEmail == student.email }
                                .map { it.sessionId }.distinct()
                        h.percentage.text =
                            if (sessionIds.isNotEmpty()) "${100 * studentAttendanceSet.size / sessionIds.size}%" else "123%"


                        h.root.setOnLongClickListener {
                            val dateFormat = makeSessionTimeFormatter()

                            if (h.container.isVisible) {
                                h.container.visibility = View.GONE
                            } else {
                                h.container.removeAllViews()
                                allSessions.sortedBy { it.id }.forEach { session ->
                                    val mini = layoutInflater.inflate(
                                        R.layout.item_mini_session_stat_card,
                                        h.container,
                                        false
                                    )
                                    val sessionNameView =
                                        mini.findViewById<TextView>(R.id.txtMiniSessionName)
                                    sessionNameView.text = session.name
                                    if (allAttendance.filter { it.studentEmail == student.email && it.sessionId == session.id }
                                            .isNotEmpty()) {
                                        sessionNameView.setTextColor(getColorFromAttr(R.attr.studentAttendedClassColor))
                                    } else {
                                        sessionNameView.setTextColor(getColorFromAttr(R.attr.studentSkippedClassColor))
                                    }
                                    mini.findViewById<TextView>(R.id.txtMiniSessionDate).text =
                                        fromMillisToLocalDate(session.date).format(dateFormat)
                                    h.container.addView(mini)
                                }
                                h.container.visibility = View.VISIBLE
                            }
                            true
                        }
                    }
                }
            }
        }
    }


    private fun getColorForAccent(courseName: String): Int {
        val typedArray = resources.obtainTypedArray(R.array.chalk_colors_list)
        val colors = IntArray(typedArray.length())
        for (i in 0 until typedArray.length()) {
            colors[i] = typedArray.getColor(i, 0)
        }
        typedArray.recycle()

        // Use the hash of the name to pick a consistent index
        val index = Math.abs(courseName.hashCode()) % colors.size
        return colors[index]
    }

    private fun addSectionHeader(container: LinearLayout, title: String) {
        val header = TextView(this).apply {
            text = title.uppercase()
            // ContextCompat ensures the system checks the current mode first
            setTextColor(ContextCompat.getColor(this@OldMainActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(10, 40, 10, 10)
        }
        container.addView(header)
    }

    private fun addYearDivider(container: LinearLayout, year: String) {
        val dividerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val line = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 2, 1f)
            setBackgroundColor(Color.LTGRAY)
        }

        val yearLabel = TextView(this).apply {
            text = "  $year  "
            textSize = 12f
                    setTextColor(Color.LTGRAY)
        }

        dividerLayout.addView(line)
        dividerLayout.addView(yearLabel)
        dividerLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 2, 1f); setBackgroundColor(
            Color.LTGRAY) })
        container.addView(dividerLayout)
    }

    private fun addCoursesToDashboardSection(container: LinearLayout, title: String, courses: List<Course>) {
        if (courses.isNotEmpty()) {
            addSectionHeader(container, title)
            courses.forEach { addCourseCardToContainer(container, it) }
        }
    }

    private fun addCourseCardToContainer(container: LinearLayout, course: Course) {
        val cardView = layoutInflater.inflate(R.layout.item_course_card, container, false)
        val background = cardView.findViewById<View>(R.id.viewCourseAccent)

        // Use your pretty color logic
        val color = getColorForAccent(course.name)
        background.setBackgroundColor(color)

        cardView.findViewById<TextView>(R.id.txtCourseName).text = course.name
        cardView.findViewById<TextView>(R.id.txtCourseDetails).text = formatYearSemester(course.year, course.semester)

        cardView.setOnClickListener { selectCourse(course) }

        cardView.setOnLongClickListener {
            showDeleteCourseDialog(course)
            true // returns true to indicate the click was consumed
        }
        container.addView(cardView)
    }

    private fun formatYearSemester(year: Int, semester: Int): String {
        return "$year/$semester"
    }
    private fun refreshDashboard(filter: String = "") {
        val container = findViewById<LinearLayout>(R.id.currentCoursesContainer)

        val calendar = Calendar.getInstance()
        val curYear = calendar.get(Calendar.YEAR)
        val curSemester = if (calendar.get(Calendar.MONTH) < 6) 1 else 2

        findViewById<TextView>(R.id.txtCurrentTerm).text = "Current Term: " + formatYearSemester(curYear, curSemester)


        lifecycleScope.launch {
            // Fetch and filter
            var allCourses = db.dao().getAllCourses()
            if (filter.isNotEmpty()) {
                allCourses = allCourses.filter { it.name.contains(filter, ignoreCase = true) }
            }

            container.removeAllViews()



            // Reuse your logic for categorization...
            val thisSemester = allCourses.filter { it.year == curYear && it.semester == curSemester }
            val nextSemester = allCourses.filter {
                (it.year == curYear && it.semester > curSemester) || (it.year > curYear)
            }
            val previousSemesters = allCourses.filter {
                (it.year == curYear && it.semester < curSemester) || (it.year < curYear)
            }

            // Build the UI as before
            addCoursesToDashboardSection(container, "This Semester", thisSemester)
            addCoursesToDashboardSection(container, "Upcoming", nextSemester)

            if (previousSemesters.isNotEmpty()) {
                addSectionHeader(container, "Previous Semesters")
                var lastYear = -1
                previousSemesters.sortedWith(compareByDescending<Course> { it.year }.thenByDescending { it.semester })
                    .forEach { course ->
                        if (course.year != lastYear) {
                            addYearDivider(container, course.year.toString())
                        }
                        lastYear = course.year
                        addCourseCardToContainer(container, course)
                    }
            }
        }
    }


    private fun showCreateCourseDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        // 1. Course Name Input
        val nameInput = EditText(context).apply {
            hint = "Course Name (e.g., Data Structures)"
            setSingleLine(true)
        }
        layout.addView(nameInput)

        // 2. Container for Year and Semester (Horizontal)
        val pickerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 0)
        }

        // Logic for defaults
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentSemester = if (calendar.get(Calendar.MONTH) < 6) 1 else 2

        // Year Picker
        val yearPicker = NumberPicker(context).apply {
            minValue = currentYear - 5
            maxValue = currentYear + 1
            value = currentYear
            wrapSelectorWheel = false
        }

        // Semester Picker
        val semesterPicker = NumberPicker(context).apply {
            minValue = 1
            maxValue = 2
            value = currentSemester
        }

        pickerLayout.addView(yearPicker)
        pickerLayout.addView(semesterPicker)
        layout.addView(pickerLayout)

        AlertDialog.Builder(context)
            .setTitle("New Course")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val newCourse = Course(
                            name = name,
                            year = yearPicker.value,
                            semester = semesterPicker.value
                        )
                        db.dao().insertCourse(newCourse)
                        refreshDashboard()
                    }
                } else {
                    Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
                refreshDashboard()
             }
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading()
    }


    private fun showCreateSessionDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_session, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtSessionName)
        val courseId = selectedCourse!!.id

        // Function to update the name based on type
        fun updateDefaultName(type: String) {
            lifecycleScope.launch {
                val count = db.dao().getSessionsByCourse(courseId).size + 1
                withContext(Dispatchers.Main) {
                    // Example: If count is 2, name becomes "Class 3"
                    edtName.setText("$type $count")
                    edtName.selectAll() // Highlight text for easy editing
                }
            }
        }

        // Initial default (Class)
        updateDefaultName("Class")

        val edtDate = dialogView.findViewById<TextInputEditText>(R.id.edtSessionDate)

        // Default to current date
        var selectedTimestamp = System.currentTimeMillis()
        val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())
        edtDate.setText(fromMillisToLocalDate(selectedTimestamp).format(dateFormat))

        // Date Picker Setup
        edtDate.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select Session Date")
                .setSelection(selectedTimestamp)
                .build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                // The selection is the start of the day in UTC
                selectedTimestamp = selection

                // Fix: Convert the UTC millis to a LocalDate specifically using UTC/GMT
                val localDate = Instant.ofEpochMilli(selection)
                    .atZone(ZoneOffset.UTC) // Use UTC to match the picker's output
                    .toLocalDate()

                edtDate.setText(localDate.format(dateFormat))
            }

            datePicker.show(supportFragmentManager, "DATE_PICKER")
        }

        AlertDialog.Builder(this)
            .setTitle("New Session")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                lifecycleScope.launch {
                    val finalName = edtName.text.toString()
                    db.dao().insertSession(
                        Session(
                            courseId = courseId,
                            name = finalName,
                            date = fromMillisToLocalDate(selectedTimestamp)
                                .atStartOfDay(ZoneId.systemDefault())
                                .toInstant().toEpochMilli()
                        )
                    )
                    loadSessionsFromDb()
                }

            }
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading()
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A, null)
    }


    class StudentImportAdapter(private val students: List<Student>) :
        RecyclerView.Adapter<StudentImportAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.txtPreviewStudentName)
            val emailText: TextView = view.findViewById(R.id.txtPreviewStudentEmail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_import_student_preview, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val student = students[position]
            holder.nameText.text = student.name
            holder.emailText.text = student.email
        }

        override fun getItemCount() = students.size
    }

    private fun showStudentImportPreview(students: List<Student>) {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_import_student_preview, null)
        bottomSheet.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvImportStudentPreview)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirmStudentImport)
        val txtCount = view.findViewById<TextView>(R.id.txtImportStudentCount)

        // Set the descriptive text
        txtCount.text = "We found ${students.size} students. Please verify the roster."

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = StudentImportAdapter(students)

        btnConfirm.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    // Bulk insert into the Student table
                    db.dao().insertStudents(students)
                }
                bottomSheet.dismiss()
                Toast.makeText(this@OldMainActivity, "Successfully imported ${students.size} students", Toast.LENGTH_SHORT).show()

                // Refresh statistics if currently viewing a course
                if (currentState == AppState.COURSE) {
                    loadSessionsFromDb()
                }
            }
        }
        bottomSheet.show()
    }



    // 1. Define the File Picker Launcher
    private val importStudentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> parseStudentCsv(uri) }
        }
    }

    private val importSessionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> importSessionsFromCsv(uri, selectedCourse!!.id) }
        }
    }

    // 2. Trigger the Picker
    private fun triggerStudentImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/comma-separated-values" // Standard CSV
            // Some phones use text/plain for CSVs
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
        }
        importStudentLauncher.launch(intent)
    }

    private fun triggerImportSessionPicker() {
        if(selectedCourse == null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/comma-separated-values" // Standard CSV
            // Some phones use text/plain for CSVs
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
        }
        importSessionLauncher.launch(intent)

    }

    private fun parseStudentCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val students = mutableListOf<Student>()
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            if (line.isBlank() || (index == 0 && line.contains("email", ignoreCase = true))) {
                                return@forEachIndexed
                            }

                            // Handling both comma and semicolon for Brazilian CSVs (common in Excel)
                            val tokens = line.split(Regex("[,;]")).map { it.trim() }
                            if (tokens.size >= 2) {
                                val name = tokens[0]
                                val email = tokens[1]
                                if (name.isNotEmpty() && email.isNotEmpty()) {
                                    students.add(Student(email = email, name = name))
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (students.isNotEmpty()) {
                        showStudentImportPreview(students)
                    } else {
                        Toast.makeText(
                            this@OldMainActivity,
                            "No valid students found in CSV",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OldMainActivity,
                        "Error: Check CSV format",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        val rfid = tag.id.joinToString(":") { "%02X".format(it) }
        val time = System.currentTimeMillis()

        if (isDialogOpen) return

        lifecycleScope.launch {
            val student = db.dao().getStudentByRfid(rfid)

            // CRITICAL: Switch to the Main Thread before touching ANY UI/Dialog logic
            runOnUiThread {
                if (activeSession != null) {
                    if (activeSession!!.isLocked) {
                        Toast.makeText(this@OldMainActivity, "Session Locked", Toast.LENGTH_SHORT).show()
                    } else if (student != null) {
                        // This calls a suspend function, so we must launch a coroutine
                        // or handle it properly. For simplicity, we keep it in the scope.
                        lifecycleScope.launch {
                            saveAndLogAttendance(student.rfid, time)
                            loadAttendanceList(activeSession!!.id)
                        }
                    } else {
                        Toast.makeText(this@OldMainActivity, "Tag not registered", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Registration Mode: Assign tag to an imported student
                    if (student != null) {
                        showOverwriteConfirmation(student, rfid)
                    } else {
                        // This was previously crashing because it was on a background thread
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
            .showWithSmartNfcReading()
    }

    private fun showBindingDialog(newRfid: String) {
        lifecycleScope.launch {
            // Fetch ALL students for this course/system
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

                        val row = TextView(this@OldMainActivity).apply {
                            // Show "Name (TagID)" or just "Name"
                            text =
                                if (hasTag) "${student.name} - ${student.email} - [${student.rfid}]"
                                else "${student.name} - ${student.email} - [No tag]"

                            textSize = 16f
                            setPadding(30, 30, 30, 30)

                            // Visual cue: Dim the students who already have tags
                            alpha = if (hasTag) 0.6f else 1.0f

                            setOnClickListener {
                                if (hasTag) {
                                    // Trigger Confirmation if already bound
                                    showReassignConfirmation(student, newRfid) {
                                        bindingDialog?.dismiss()
                                    }
                                } else {
                                    // Bind directly if empty
                                    bindTag(newRfid, student.email)
                                    bindingDialog?.dismiss()
                                }
                            }
                        }
                        container.addView(row)

                        // Divider
                        val line = View(this@OldMainActivity).apply {
                            layoutParams =
                                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                            setBackgroundColor(Color.LTGRAY)
                        }
                        container.addView(line)
                    }
                }

                refreshList("")
                bindingDialog = AlertDialog.Builder(this@OldMainActivity)
                    .setTitle("Assign Tag: $newRfid")
                    .setView(dialogView)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Manual Entry") { _, _ ->
                        // Wrap in runOnUiThread to prevent the "Can't create handler" crash
                        runOnUiThread {
                            showRegistrationDialog(newRfid)
                        }
                    }
                    .showWithSmartNfcReading()

                edtSearch.addTextChangedListener { refreshList(it.toString()) }
            }
        }
    }

    private fun bindTag(rfid: String, email: String) {
        lifecycleScope.launch {
            // Use the IO dispatcher for database operations
            withContext(Dispatchers.IO) {
                db.dao().clearAndBind(rfid, email)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@OldMainActivity,
                    "Tag successfully assigned!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // --- New Confirmation Dialog ---
    private fun showOverwriteConfirmation(existingStudent: Student, newRfid: String) {
        AlertDialog.Builder(this)
            .setTitle("Tag Already Registered")
            .setMessage("This tag is currently assigned to ${existingStudent.name} (${existingStudent.email}).\n\nDo you want to unbind this tag so it can be reassigned?")
            .setPositiveButton("Yes") { _, _ ->
                lifecycleScope.launch {
                    // 1. Remove the RFID from the current owner
                    db.dao().bindTagToStudent(null, existingStudent.email)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@OldMainActivity, "Tag unbound", Toast.LENGTH_SHORT)
                            .show()
                        // 2. Now show the list to pick the new owner
                        showBindingDialog(newRfid)
                    }
                }
            }
            .setNegativeButton("No", null)
            .showWithSmartNfcReading()
    }

    private suspend fun saveAndLogAttendance(rfid: String?, time: Long) {
        if(rfid == null) return
        withContext(Dispatchers.IO) {
            val email = db.dao().getStudentByRfid(rfid)!!.email
            db.dao().recordAttendance(
                Attendance(
                    rfid = rfid,
                    sessionId = activeSession!!.id,
                    studentEmail = email,
                    timestamp = time
                )
            )
        }
    }

    private fun showRegistrationDialog(rfid: String) {
        // Create a container to hold two input fields
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val nameInput = EditText(this).apply {
            hint = "Student Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        val emailInput = EditText(this).apply {
            hint = "student@university.edu"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        layout.addView(nameInput)
        layout.addView(emailInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Manual Registration")
            .setMessage("Tag ID: $rfid")
            .setView(layout)
            .setPositiveButton("Save", null) // Set to null to override behavior
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading()

        // Persistent Click Listener (Prevents closing on error)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()

            if (name.isEmpty()) {
                nameInput.error = "Name is required"
            } else if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailInput.error = "Enter a valid email"
            } else {
                // SUCCESS: Save to database
                lifecycleScope.launch {
                    db.dao().insertStudents(listOf(Student(email = email, name = name, rfid = rfid)))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@OldMainActivity, "Registered $name", Toast.LENGTH_SHORT)
                            .show()
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    private suspend fun generateCsvString(course: Course): String {
        // 1. Fetch all data needed for the pivot
        val allSessions = db.dao().getSessionsByCourse(course.id).sortedBy { it.date }
        val sessionIds = allSessions.map { it.id }

        // Get all attendance for these sessions
        val allAttendance = mutableListOf<AttendanceRecord>()
        sessionIds.forEach { sid ->
            allAttendance.addAll(db.dao().getAttendanceRecordsForSession(sid))
        }

        // 2. Identify active students (those who attended at least once)
        val activeEmails = allAttendance.map { it.studentEmail }.toSet()
        val activeStudents = db.dao().getAllStudents()
            .filter { it.email in activeEmails }
            .sortedBy { it.name }

        val csvBuilder = StringBuilder()

        // 3. Header: Student Name, Email, RFID, Session 1, Session 2...
        csvBuilder.append("Student Name,Email,RFID")
        allSessions.forEach { session ->
            csvBuilder.append(",${session.name}")
        }
        csvBuilder.append("\n")

        // 4. Data Rows
        activeStudents.forEach { student ->
            // Static Columns
            csvBuilder.append("${student.name},${student.email},${student.rfid ?: "N/A"}")

            // Dynamic Columns (Sessions)
            allSessions.forEach { session ->
                // Check if this student appears in the logs for this specific session
                val wasPresent = allAttendance.any {
                    it.studentEmail == student.email && it.sessionName == session.name
                }
                csvBuilder.append(if (wasPresent) ",P" else ",")
            }
            csvBuilder.append("\n")
        }

        return csvBuilder.toString()
    }
    private fun performExport(uri: Uri) {
        val course = selectedCourse ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val csvData = generateCsvString(course) // Calls the new helper
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(csvData.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OldMainActivity, "Roster exported!", Toast.LENGTH_SHORT)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OldMainActivity, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}