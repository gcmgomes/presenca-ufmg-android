package com.example.presensor

import android.Manifest
import android.app.Activity
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.AndroidTagControllerDialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.tools.providers.ToastProvider
import com.example.presensor.tools.providers.AndroidToastProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

import com.example.presensor.tools.UiUtils
import com.example.presensor.data.AppDatabase
import com.example.presensor.adapters.ImportStudentAdapter
import com.example.presensor.adapters.ImportPreviewAdapter
import com.example.presensor.adapters.StudentStatsAdapter
import com.example.presensor.ble.ReaderManager
import com.example.presensor.controllers.CloudSyncController
import com.example.presensor.controllers.DashboardController
import com.example.presensor.controllers.CourseController
import com.example.presensor.controllers.DetailedCourseController
import com.example.presensor.controllers.SessionController
import com.example.presensor.controllers.TagController
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Course
import com.example.presensor.controllers.ImportSessionController
import com.example.presensor.controllers.ImportStudentController
import com.example.presensor.services.ReaderStatusService
import com.example.presensor.tools.providers.AndroidDataProcessorProvider
import com.example.presensor.tools.providers.AndroidDialogProvider
import com.example.presensor.tools.providers.AndroidPreviewProvider
import com.example.presensor.tools.providers.DialogProvider
import com.example.presensor.tools.providers.LoadingOverlayProvider
import com.example.presensor.tools.providers.PreviewProvider
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.controllers.ReaderConnectivityController
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity(), LoadingOverlayProvider {

    companion object {
        const val TAG = "MainActivity"
        const val DATABASE_NAME = "presensor-db"
        enum class AppState { DASHBOARD, COURSE, SESSION, COURSE_STATS, READER_MANAGEMENT }
    }

    private var currentState = AppState.DASHBOARD

    private lateinit var db: AppDatabase
    fun getDb(): AppDatabase = db
    private lateinit var dashboardController: DashboardController
    private lateinit var courseController: CourseController
    private lateinit var detailedCourseController: DetailedCourseController
    private lateinit var sessionController: SessionController
    private lateinit var tagController: TagController
    lateinit var importSessionController: ImportSessionController
    lateinit var importStudentController: ImportStudentController
    lateinit var readerConnectivityController: ReaderConnectivityController

    lateinit var cloudSyncController: CloudSyncController

    // Keeps track of the user's intended action if they need to complete a sign-in flow first
    var pendingCloudAction: (() -> Unit)? = null


    private lateinit var currentBackCallback: OnBackPressedCallback
    private lateinit var dashboardView: View
    private lateinit var layoutCourseView: View
    private lateinit var layoutSessionView: View

    lateinit var loadingOverlay: View

    // Tracks any active background job associated with the loading overlay for cancellation
    private var currentOverlayJob: Job? = null

    lateinit var secureStoreManager: SecureStoreManager
    var readerManager: ReaderManager? = null


    override fun toggleLoadingOverlay(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            currentOverlayJob?.cancel()
            currentOverlayJob = null
        }
    }

    override fun setCurrentOverlayJob(job: Job?) {
        currentOverlayJob = job
    }

    // Flag to orchestrate the focus lock state safely
    private var isWaitingForFocus = false
    private var isCloudAuthSuccessPendingRun = false
    val cloudSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                cloudSyncController.handleSignInResult(result.data) {
                    Toast.makeText(this@MainActivity, "Logged in successfully", Toast.LENGTH_SHORT)
                        .show()

                    // Mark that authentication is fully completed and ready to run
                    isCloudAuthSuccessPendingRun = true

                    // Attempt execution immediately if focus is already here
                    checkAndRunPendingCloudAction()
                }
            } else {
                pendingCloudAction = null
                isCloudAuthSuccessPendingRun = false
                toggleLoadingOverlay(false)
            }
        }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Attempt execution if the window regained focus last
            checkAndRunPendingCloudAction()
        }
    }

    /**
     * Gatekeeper method ensuring BOTH layout window focus and token handshakes are verified
     */
    private fun checkAndRunPendingCloudAction() {
        if (hasWindowFocus() && isCloudAuthSuccessPendingRun) {
            // Reset state flags before executing to prevent accidental double-triggers
            isCloudAuthSuccessPendingRun = false

            executePendingActionWithTransitionBreather()
        }
    }

    /**
     * Introduces an explicit hardware rendering delay. This guarantees the
     * Google Sign-In sheet is 100% gone before your layout modifies its views.
     */
    private fun executePendingActionWithTransitionBreather() {
        // Post to the next animation frame, then let the system breathe for 150ms
        window.decorView.postOnAnimation {
            lifecycleScope.launch(Dispatchers.Main) {
                delay(150) // The perfect window transition buffer duration
                pendingCloudAction?.invoke()
                pendingCloudAction = null
            }
        }
    }

    fun setPendingAction(action: () -> Unit) {
        this.pendingCloudAction = action
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d("MainActivity", "All Bluetooth permissions granted. Starting connection...")

            // SAFE ZONE: Initialize the channel right before connecting
            initializeReaderStatusChannel()
            readerManager?.startConnecting()
            readerManager?.setAppActive(false)
        } else {
            Log.e("MainActivity", "Permissions denied by user.")
            Toast.makeText(
                this,
                "Bluetooth permissions are required for Presensor to work!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkAndRequestBluetoothPermissions() {
        val permissionsList = mutableListOf<String>()

        // 1. Bluetooth Core Hardware Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // 2. Add Status Bar Icon Permission (Crucial for Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsList.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            Log.d("MainActivity", "Permissions already granted. Initializing pipeline...")
            initializeReaderStatusChannel()
            readerManager?.startConnecting()
            readerManager?.setAppActive(false)
        } else {
            Log.d("MainActivity", "Requesting missing permissions: $missingPermissions")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }


    /**
     * Isolated helper to safely kick off the top-bar icon synchronization
     */
    private fun initializeReaderStatusChannel() {
        // 1. Fire up the foreground service to anchor the status icon
        ReaderStatusService.startService(this)

        // 2. Start collecting the states to update the top-left area
        lifecycleScope.launch {
            readerManager?.connectionState?.collectLatest { state ->
                ReaderStatusService.updateStatus(state)
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, true)


        val toastProvider = AndroidToastProvider(this)
        secureStoreManager = SecureStoreManager(this)

        readerManager = ReaderManager(
            context = this,
            secureStoreManager = secureStoreManager,
            scope = lifecycleScope,
            mainDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.IO
        )


        val dbCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA cache_size = 4000;")
                db.execSQL("PRAGMA foreign_keys = ON;")
                db.execSQL("PRAGMA optimize;")
            }
        }

        checkAndRequestBluetoothPermissions()


        loadingOverlay = findViewById(R.id.loadingOverlay)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, DATABASE_NAME)
            .addCallback(dbCallback).fallbackToDestructiveMigration().build()

        lifecycleScope.launch { db.preloadStudents() }

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


        cloudSyncController = CloudSyncController(this, this, db)

        // Initialize Course related Dialog Factory
        val courseDialogFactory = CourseControllerDialogFactory(
            activity = this,
            lifecycleOwner = this,
            db = db
        )

        // Initialize Session related Dialog Factory
        val sessionDialogFactory = SessionControllerDialogFactory(
            activity = this,
            lifecycleOwner = this,
            db = db,
            refreshUI = { if (::courseController.isInitialized) courseController.refreshCourseUI() }
        )

        val dataProcessorProvider = AndroidDataProcessorProvider()
        val previewProvider = AndroidPreviewProvider()
        val dialogProvider = AndroidDialogProvider(previewProvider)

        importSessionController = ImportSessionController(
            activity = this,
            context = this,
            scope = lifecycleScope,
            db = db,
            dataProcessorProvider = dataProcessorProvider,
            dialogProvider = dialogProvider,
            loadingOverlayProvider = this,
            toastProvider = toastProvider
        )
        importStudentController = ImportStudentController(
            activity = this,
            context = this,
            scope = lifecycleScope,
            db = db,
            dataProcessorProvider = dataProcessorProvider,
            dialogProvider = dialogProvider,
            loadingOverlayProvider = this,
            toastProvider = toastProvider
        )

        // Initialize Dashboard Controller
        dashboardController = DashboardController(
            activity = this,
            db = db,
            scope = lifecycleScope,
            onCourseSelected = { course -> selectCourse(course) },
            onCourseLongClicked = { course -> showDeleteCourseDialog(course) },
            onCourseCreateRequested = {
                courseController.showCreateCourseDialog {
                    dashboardController.refreshDashboard()
                }
            },
            onCourseEditRequested = { course ->
                // Intercept the pencil click and display your course modification UI
                courseController.showEditCourseDialog(course) {
                    dashboardController.refreshDashboard()
                }
            }
        )
        dashboardController.setupQuickActionsAccordion()
        dashboardController.setupOnClickListeners()

        readerConnectivityController = ReaderConnectivityController(
            activity = this,
            db = db,
            secureStoreManager = secureStoreManager,
            scope = lifecycleScope
        )

        // Initialize Session Controller
        sessionController = SessionController(
            activity = this,
            context = this,
            scope = lifecycleScope,
            db = db,
            layoutInflater = layoutInflater,
            attendanceContainer = findViewById(R.id.attendanceContainer),
            swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout),
            txtSessionTitle = findViewById(R.id.txtSessionTitle),
            txtSessionSubtitle = findViewById(R.id.txtSessionSubtitle),
            viewSessionDetailAccent = findViewById(R.id.viewSessionDetailAccent),
            imgMasterLock = findViewById(R.id.imgMasterLock),
            btnEditSession = findViewById(R.id.btnEditSessionInternal),
            getColorForAccent = { name ->
                UiUtils.getColorForAccent(
                    name,
                    resources.obtainTypedArray(R.array.chalk_colors_list)
                )
            },
            onSessionStateMutated = {
                if (currentState == AppState.COURSE) {
                    courseController.refreshCourseUI()
                }
            },
            dialogFactory = sessionDialogFactory,
            toastProvider = AndroidToastProvider(this),
            onPulldown = { readerManager?.requestBacklogSync() }
        )

        // Initialize Tag Controller
        tagController = TagController(
            activity = this,
            db = db,
            scope = lifecycleScope,
            readerManager = readerManager,
            sessionController = sessionController,
            sessionDialogFactory = sessionDialogFactory,
            tagControllerDialogFactory = AndroidTagControllerDialogFactory(this, layoutInflater),
            toastProvider = AndroidToastProvider(this),
            isDialogShowingCheck = { DialogFactory.isAnyDialogOpen() },
            disableRefreshSpinner = { sessionController.showLayoutRefreshSpinner(false) },
            resetSyncTimeout = { sessionController.resetSyncTimeout() }
        )
        DialogFactory.tagController = tagController
        tagController.startReaderCollection()

        // Initialize Course Controller
        courseController = CourseController(
            activity = this,
            lifecycleOwner = this,
            selectedCourse = null,
            db = db,
            onSessionSelected = { session ->
                openSessionView(session)
                readerManager?.setAppActive(true)
            },
            onToggleLockRequested = { session, _ ->
                sessionController.handleLockToggleSequence(session)
                courseController.refreshCourseUI()
            },
            onEditSessionRequested = { session, _ -> sessionController.showEditSessionDialog(session) },
            onOpenStatistics = { openCourseStatistics() },
            courseDialogFactory = courseDialogFactory,
            sessionDialogFactory = sessionDialogFactory
        )

        detailedCourseController = DetailedCourseController(
            activity = this,
            lifecycleOwner = this,
            db = db,
            courseController = courseController,
            getColorFromAttr = { attr -> getColorFromAttr(attr) },
            mainDispatcher = Dispatchers.Main,
            ioDispatcher = Dispatchers.IO
        )


        findViewById<FloatingActionButton>(R.id.btnAddSession).setOnClickListener { courseController.showCreateSessionDialog() }

        // Bind the manual entry click interaction directly to your extracted controller layer
        findViewById<FloatingActionButton>(R.id.btnRegisterManualAttendance).setOnClickListener {
            sessionController.showManualAttendanceDialog()
        }

        toggleAllViews(layoutDashboardView = true)

        currentBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If loading overlay is visible, it means a cloud or heavy operation is in progress.
                // Revert control to the user and clear any pending cloud actions.
                if (loadingOverlay.isVisible) {
                    cloudSyncController.cancelActiveOperation()
                    toggleLoadingOverlay(false)
                    pendingCloudAction = null
                    isCloudAuthSuccessPendingRun = false
                    return
                }

                when (currentState) {
                    AppState.SESSION -> {
                        readerManager?.setAppActive(false)
                        sessionController.clearActiveSession()
                        currentState = AppState.COURSE
                        toggleAllViews(layoutCourseView = true)
                        courseController.refreshCourseUI()
                    }

                    AppState.COURSE -> {
                        courseController.clear()
                        currentState = AppState.DASHBOARD
                        toggleAllViews(layoutDashboardView = true)
                        dashboardController.refreshDashboard()
                    }

                    AppState.COURSE_STATS -> {
                        detailedCourseController.clear()
                        currentState = AppState.COURSE
                        toggleAllViews(layoutCourseView = true)
                        courseController.refreshCourseUI()
                    }

                    AppState.READER_MANAGEMENT -> {
                        readerConnectivityController.teardownView()
                        currentState = AppState.DASHBOARD
                        toggleAllViews(layoutDashboardView = true)
                        dashboardController.refreshDashboard()
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

    fun openReaderManagement() {
        currentState = AppState.READER_MANAGEMENT
        toggleAllViews(layoutReaderManagementView = true)
        readerConnectivityController.setupReaderManagementView(findViewById<View>(R.id.layoutReaderManagementView))
    }

    private fun selectCourse(course: Course) {
        lifecycleScope.launch {
            courseController.prepare(course)?.join()
            currentState = AppState.COURSE
            toggleAllViews(layoutCourseView = true)
            courseController.refreshCourseUI()
        }
    }

    private fun toggleAllViews(
        layoutDashboardView: Boolean = false,
        layoutCourseView: Boolean = false,
        layoutSessionView: Boolean = false,
        layoutCourseStatisticsView: Boolean = false,
        layoutReaderManagementView: Boolean = false
    ) {
        findViewById<View>(R.id.layoutDashboardView).isVisible = layoutDashboardView
        findViewById<View>(R.id.layoutCourseView).isVisible = layoutCourseView
        findViewById<View>(R.id.layoutSessionView).isVisible = layoutSessionView
        findViewById<View>(R.id.layoutCourseStatisticsView).isVisible = layoutCourseStatisticsView
        findViewById<View>(R.id.layoutReaderManagementView).isVisible = layoutReaderManagementView
    }

    private fun openSessionView(session: Session) {
        currentState = AppState.SESSION
        toggleAllViews(layoutSessionView = true)
        sessionController.openSessionView(session)
    }


    private fun showDeleteCourseDialog(course: Course) {
        DialogFactory.showDestructiveDeleteDialog(
            context = this,
            title = getString(R.string.dialog_delete_course_title),
            message = getString(R.string.dialog_delete_course_message, course.name),
            onConfirmed = {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val sessions = db.getSessionsByCourse(course.id)
                        sessions.forEach {
                            db.deleteAttendancesBySessionId(it.id)
                            db.deleteSession(it)
                        }
                        db.deleteCourse(course)
                    }
                    dashboardController.refreshDashboard()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_course_deleted_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    @ColorInt
    fun getColorFromAttr(@AttrRes attrColor: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrColor, typedValue, true)
        return typedValue.data
    }


    private fun openCourseStatistics() {
        if (courseController.getSelectedCourse() == null) return
        currentState = AppState.COURSE_STATS

        val container = findViewById<LinearLayout>(R.id.layoutCourseStatisticsView)
        container.removeAllViews()
        toggleAllViews() // Clears visible UI spaces

        // Delegate both creation/inflation and configuration to the controller
        val statsView = detailedCourseController.inflateAndSetupStatsView(container)

        // Add the fully-configured view back into the layout tree
        container.addView(statsView)

        // Initial data sync for the student row filtering states
        detailedCourseController.refreshDetailedCourseUI()

        toggleAllViews(layoutCourseStatisticsView = true)
    }


    override fun onResume() {
        super.onResume()
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
        }

        // Determine the hardware reader flags dynamically
        var readerFlags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        // If your custom property says the sound shouldn't play (or a dialog is locking it),
        // we tell Android to silence the platform beep. Otherwise, leave it out so it beeps naturally!
        if (DialogFactory.isAnyDialogOpen()) { // Replace with your exact DialogFactory boolean variable
            readerFlags = readerFlags or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        }

        tagController.getNfcAdapter()?.enableReaderMode(
            this,
            tagController,
            readerFlags,
            options
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        readerManager?.disconnect()
    }

}