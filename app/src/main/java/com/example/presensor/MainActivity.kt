package com.example.presensor

import android.Manifest
import android.app.Activity
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.AndroidTagControllerDialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.providers.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.example.presensor.tools.UiUtils
import com.example.presensor.data.AppDatabase
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.controllers.CloudSyncController
import com.example.presensor.controllers.DashboardController
import com.example.presensor.controllers.CourseController
import com.example.presensor.controllers.DetailedCourseController
import com.example.presensor.controllers.SessionController
import com.example.presensor.controllers.TagController
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Course
import com.example.presensor.controllers.ImportSessionController
import com.example.presensor.controllers.ImportStudentController
import com.example.presensor.services.ReaderStatusService
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.controllers.ReaderDiscoveryController
import com.example.presensor.controllers.ReaderManagementController
import com.example.presensor.controllers.ImportBacklogController
import com.example.presensor.communication.ble.BleTransport
import com.example.presensor.communication.core.AppMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest

open class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val DATABASE_NAME = "presensor-db"

        enum class AppState { DASHBOARD, COURSE, SESSION, COURSE_STATS, READER_MANAGEMENT, DEVICE_MANAGER }
    }

    protected var currentState = AppState.DASHBOARD

    protected lateinit var appDatabase: AppDatabase
    fun getDb(): AppDatabase = appDatabase
    lateinit var dashboardController: DashboardController
    lateinit var courseController: CourseController
    lateinit var detailedCourseController: DetailedCourseController
    lateinit var sessionController: SessionController
    lateinit var tagController: TagController
    lateinit var importSessionController: ImportSessionController
    lateinit var importStudentController: ImportStudentController
    lateinit var readerDiscoveryController: ReaderDiscoveryController
    lateinit var readerManagementController: ReaderManagementController
    lateinit var importBacklogController: ImportBacklogController

    lateinit var cloudSyncController: CloudSyncController

    // Keeps track of the user's intended action if they need to complete a sign-in flow first
    var pendingCloudAction: (() -> Unit)? = null


    protected lateinit var currentBackCallback: OnBackPressedCallback
    protected lateinit var dashboardView: View
    protected lateinit var layoutCourseView: View
    protected lateinit var layoutSessionView: View

    lateinit var loadingOverlay: View

    // Tracks any active background job associated with the loading overlay for cancellation
    private var currentOverlayJob: Job? = null

    lateinit var secureStoreManager: SecureStoreManager
    var readerOrchestrator: ReaderOrchestrator? = null

    internal lateinit var mainDispatcher: kotlinx.coroutines.CoroutineDispatcher
    internal lateinit var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher


    fun toggleLoadingOverlay(show: Boolean) {
        if (!::loadingOverlay.isInitialized) return
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            currentOverlayJob?.cancel()
            currentOverlayJob = null
        }
    }

    fun setCurrentOverlayJob(job: Job?) {
        currentOverlayJob = job
    }

    // Flag to orchestrate the focus lock state safely
    private var isCloudAuthSuccessPendingRun = false

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

    fun runWithCloudAuthentication(action: () -> Unit) {
        setPendingAction(action)
        // Note: This now delegates to the specific cloud controller
        cloudSyncController.runWithCloudAuthentication {
            // Token handshake finished inside controller.
            // Now mark as pending execution for the focus guard.
            isCloudAuthSuccessPendingRun = true
            checkAndRunPendingCloudAction()
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
            if (readerOrchestrator?.isReaderEnabled?.value == true) {
                readerOrchestrator?.startConnecting()
            }
            readerOrchestrator?.setAppMode(AppMode.IDLE, "MainActivity Initial Setup")
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
            if (readerOrchestrator?.isReaderEnabled?.value == true) {
                readerOrchestrator?.startConnecting()
            }
            readerOrchestrator?.setAppMode(AppMode.IDLE, "MainActivity Initial Setup")
        } else {
            Log.d("MainActivity", "Requesting missing permissions: $missingPermissions")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }


    /**
     * Isolated helper to safely kick off the top-bar icon synchronization
     */
    private fun initializeReaderStatusChannel() {
        // 1. Reactive Lifecycle Governance: Service starts/stops based on Manager state
        lifecycleScope.launch {
            readerOrchestrator?.isReaderEnabled?.collect { enabled ->
                if (enabled) {
                    Log.i(TAG, "[Lifecycle] Starting ReaderStatusService...")
                    ReaderStatusService.startService(this@MainActivity)
                } else {
                    Log.i(TAG, "[Lifecycle] Stopping ReaderStatusService...")
                    ReaderStatusService.stopService(this@MainActivity)
                }
            }
        }

        // 2. Start collecting the states to update the top-left area
        lifecycleScope.launch {
            readerOrchestrator!!.connectionState.collectLatest { state ->
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

        loadingOverlay = findViewById(R.id.loadingOverlay)
        dashboardView = findViewById(R.id.layoutDashboardView)
        layoutCourseView = findViewById(R.id.layoutCourseView)
        layoutSessionView = findViewById(R.id.layoutSessionView)

        initializeDependenciesAndControllers()
    }

    /**
     * Isolated initialization logic to allow test subclasses to override and skip heavy init.
     */
    open fun initializeDependenciesAndControllers(
        mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    ) {
        this.mainDispatcher = mainDispatcher
        this.ioDispatcher = ioDispatcher
        
        secureStoreManager = SecureStoreManager(this)
        val transport = BleTransport(this, lifecycleScope)
        readerOrchestrator = ReaderOrchestrator(
            secureStoreManager = secureStoreManager,
            transport = transport,
            scope = lifecycleScope
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

        appDatabase = Room.databaseBuilder(applicationContext, AppDatabase::class.java, DATABASE_NAME)
            .addCallback(dbCallback).fallbackToDestructiveMigration().build()

        lifecycleScope.launch { appDatabase.preloadStudents() }

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

        // Initialize Dialog Factories first
        val tagDialogFactory = AndroidTagControllerDialogFactory(this, layoutInflater)
        val courseDialogFactory = CourseControllerDialogFactory(
            activity = this,
            lifecycleOwner = this,
            db = appDatabase
        )
        val sessionDialogFactory = SessionControllerDialogFactory(
            activity = this,
            lifecycleOwner = this,
            db = appDatabase,
            refreshUI = { if (::courseController.isInitialized) courseController.refreshCourseUI() }
        )

        // Initialize Specialized Providers
        val tagInteractionProvider =
            AndroidTagInteractionProvider(this, tagDialogFactory, sessionDialogFactory)
        val studentInteractionProvider = AndroidStudentInteractionProvider(this)
        val sessionInteractionProvider =
            AndroidSessionInteractionProvider(this, sessionDialogFactory)
        val readerInteractionProvider = AndroidReaderInteractionProvider(this, secureStoreManager)
        val courseInteractionProvider =
            AndroidCourseInteractionProvider(this, courseDialogFactory, sessionDialogFactory)
        val detailedCourseInteractionProvider = AndroidDetailedCourseInteractionProvider(this)
        val cloudInteractionProvider = AndroidCloudInteractionProvider(this)
        val dashboardInteractionProvider = AndroidDashboardInteractionProvider(this)

        // Initialize Cloud Sync Controller
        cloudSyncController = CloudSyncController(
            scope = lifecycleScope,
            db = appDatabase,
            interactionProvider = cloudInteractionProvider,
            mainDispatcher = mainDispatcher,
            ioDispatcher = ioDispatcher
        )

        importSessionController = ImportSessionController(
            interactionProvider = sessionInteractionProvider,
            db = appDatabase,
            scope = lifecycleScope,
            mainDispatcher = mainDispatcher,
            ioDispatcher = ioDispatcher
        )
        importStudentController = ImportStudentController(
            interactionProvider = studentInteractionProvider,
            db = appDatabase,
            scope = lifecycleScope,
            mainDispatcher = mainDispatcher,
            ioDispatcher = ioDispatcher
        )

        // Initialize Dashboard Controller
        dashboardController = DashboardController(
            activity = this,
            db = appDatabase,
            scope = lifecycleScope,
            uiProvider = dashboardInteractionProvider,
            cloudSyncController = cloudSyncController,
            importStudentController = importStudentController,
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
            },
            mainDispatcher = mainDispatcher,
            ioDispatcher = ioDispatcher
        )
        dashboardController.setupQuickActionsAccordion()
        dashboardController.setupOnClickListeners()

        readerDiscoveryController = ReaderDiscoveryController(
            secureStoreManager = secureStoreManager,
            interactionProvider = readerInteractionProvider,
            orchestrator = readerOrchestrator!!,
            scope = lifecycleScope
        )

        readerManagementController = ReaderManagementController(
            db = appDatabase,
            secureStoreManager = secureStoreManager,
            interactionProvider = readerInteractionProvider,
            orchestrator = readerOrchestrator!!,
            scope = lifecycleScope
        )

        // Initialize Session Controller
        sessionController = SessionController(
            interactionProvider = sessionInteractionProvider,
            scope = lifecycleScope,
            db = appDatabase,
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
            onPulldown = { session ->
                importBacklogController.startImportFlow(
                    startTimeMinutes = session?.startTime,
                    endTimeMinutes = session?.endTime,
                    sessionDateMillis = session?.date
                )
            },
            onSyncTimeout = { importBacklogController.dismissActiveDialog() }
        )

        importBacklogController = ImportBacklogController(
            interactionProvider = readerInteractionProvider,
            scope = lifecycleScope,
            db = appDatabase,
            orchestrator = readerOrchestrator,
            toggleSpinner = { sessionController.showLayoutRefreshSpinner(it) },
            registerAttendance = { student, time, skip ->
                sessionController.registerAttendance(
                    student,
                    time,
                    skip
                )
            },
            refreshAttendanceList = { sessionController.loadAttendanceList() }
        )

        // Initialize Tag Controller
        tagController = TagController(
            interactionProvider = tagInteractionProvider,
            db = appDatabase,
            scope = lifecycleScope,
            readerOrchestrator = readerOrchestrator,
            sessionController = sessionController,
            isDialogShowingCheck = { tagInteractionProvider.isAnyDialogOpen() },
            disableRefreshSpinner = { sessionController.showLayoutRefreshSpinner(false) },
            resetSyncTimeout = { sessionController.resetSyncTimeout() },
            getCurrentState = { currentState }
        )
        DialogFactory.tagController = tagController
        tagController.startReaderCollection()

        courseInteractionProvider.initializeCourseCloudActions(
            getSelectedCourse = { if (::courseController.isInitialized) courseController.getSelectedCourse() else null },
            onImportComplete = { if (::courseController.isInitialized) courseController.refreshCourseUI() },
            mainDispatcher = mainDispatcher,
            ioDispatcher = ioDispatcher
        )

        // Initialize Course Controller
        courseController = CourseController(
            lifecycleOwner = this,
            selectedCourse = null,
            db = appDatabase,
            interactionProvider = courseInteractionProvider,
            onSessionSelected = { session ->
                openSessionView(session)
                readerOrchestrator?.setAppMode(
                    AppMode.ACTIVE,
                    "MainActivity Session Selection"
                )
            },
            onToggleLockRequested = { session ->
                sessionController.handleLockToggleSequence(session)
                courseController.refreshCourseUI()
            },
            onEditSessionRequested = { session -> sessionController.showEditSessionDialog(session) },
            onEditCourseRequested = { course ->
                courseController.showEditCourseDialog(course) {
                    courseController.refreshCourseUI()
                    dashboardController.refreshDashboard()
                }
            },
            onOpenStatistics = { openCourseStatistics() }
        )

        detailedCourseController = DetailedCourseController(
            scope = lifecycleScope,
            db = appDatabase,
            courseController = courseController,
            interactionProvider = detailedCourseInteractionProvider,
            getColorFromAttr = { attr ->
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(attr, typedValue, true)
                typedValue.data
            }
        )


        findViewById<FloatingActionButton>(R.id.btnAddSession).setOnClickListener { courseController.showCreateSessionDialog() }

        // Bind the manual entry click interaction directly to your extracted controller layer
        findViewById<FloatingActionButton>(R.id.btnRegisterManualAttendance).setOnClickListener {
            sessionController.showManualAttendanceDialog()
        }

        currentBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        }
        onBackPressedDispatcher.addCallback(this, currentBackCallback)
        dashboardController.refreshDashboard()
    }

    internal fun handleBackNavigation() {
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
                readerOrchestrator?.setAppMode(
                    AppMode.IDLE,
                    "MainActivity back button from session"
                )
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
                readerDiscoveryController.teardownDiscovery()
                currentState = AppState.DASHBOARD
                toggleAllViews(layoutDashboardView = true)
                dashboardController.refreshDashboard()
            }

            AppState.DEVICE_MANAGER -> {
                readerManagementController.teardownView()
                currentState = AppState.READER_MANAGEMENT
                toggleAllViews(layoutReaderManagementView = true)
            }

            AppState.DASHBOARD -> {
                currentBackCallback.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                currentBackCallback.isEnabled = true
            }
        }
    }

    fun openReaderManagement() {
        currentState = AppState.READER_MANAGEMENT
        toggleAllViews(layoutReaderManagementView = true)
        readerDiscoveryController.setupReaderList()
    }

    fun openDeviceManager(address: String? = null) {
        android.util.Log.i(
            "MainActivity",
            "[UI Flow] openDeviceManager() triggered. State -> DEVICE_MANAGER"
        )
        currentState = AppState.DEVICE_MANAGER
        toggleAllViews(layoutDeviceManagerView = true)

        readerManagementController.setupReaderManagementView(address)
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
        layoutReaderManagementView: Boolean = false,
        layoutDeviceManagerView: Boolean = false
    ) {
        findViewById<View>(R.id.layoutDashboardView).isVisible = layoutDashboardView
        findViewById<View>(R.id.layoutCourseView).isVisible = layoutCourseView
        findViewById<View>(R.id.layoutSessionView).isVisible = layoutSessionView
        findViewById<View>(R.id.layoutCourseStatisticsView).isVisible = layoutCourseStatisticsView
        findViewById<View>(R.id.layoutReaderManagementView).isVisible = layoutReaderManagementView
        findViewById<View>(R.id.layoutDeviceManagerView).isVisible = layoutDeviceManagerView
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
                lifecycleScope.launch(mainDispatcher) {
                    withContext(ioDispatcher) {
                        val sessions = appDatabase.getSessionsByCourse(course.id)
                        sessions.forEach {
                            appDatabase.deleteAttendancesBySessionId(it.id)
                            appDatabase.deleteSession(it)
                        }
                        appDatabase.deleteCourse(course)
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


    private fun openCourseStatistics() {
        if (courseController.getSelectedCourse() == null) return
        currentState = AppState.COURSE_STATS

        detailedCourseController.openDetailedCourseView()

        toggleAllViews(layoutCourseStatisticsView = true)
    }


    override fun onResume() {
        super.onResume()
        if (::tagController.isInitialized) {
            tagController.resumeNfcScanning()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::tagController.isInitialized) {
            tagController.pauseNfcScanning()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        readerOrchestrator?.disconnect()
    }

}
