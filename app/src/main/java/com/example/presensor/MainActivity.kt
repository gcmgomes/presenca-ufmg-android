package com.example.presensor

import android.Manifest
import android.app.Activity
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.AndroidTagControllerDialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.providers.AndroidInteractionProvider
import com.example.presensor.tools.providers.AndroidToastProvider
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
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
import com.example.presensor.tools.providers.AndroidDataProcessorProvider
import com.example.presensor.tools.providers.AndroidDialogProvider
import com.example.presensor.tools.providers.AndroidPreviewProvider
import com.example.presensor.tools.providers.LoadingOverlayProvider
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.controllers.ReaderDiscoveryController
import com.example.presensor.controllers.ReaderManagementController
import com.example.presensor.controllers.AndroidReaderInteractionProvider
import com.example.presensor.controllers.ImportBacklogController
import com.example.presensor.communication.ble.BleTransport
import com.example.presensor.communication.core.AppMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity(), LoadingOverlayProvider {

    companion object {
        const val TAG = "MainActivity"
        const val DATABASE_NAME = "presensor-db"

        enum class AppState { DASHBOARD, COURSE, SESSION, COURSE_STATS, READER_MANAGEMENT, DEVICE_MANAGER }
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
    lateinit var readerDiscoveryController: ReaderDiscoveryController
    lateinit var readerManagementController: ReaderManagementController
    lateinit var importBacklogController: ImportBacklogController

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
    var readerOrchestrator: ReaderOrchestrator? = null


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

        val interactionProvider = AndroidInteractionProvider(
            activity = this,
            secureStoreManager = secureStoreManager,
            tagDialogFactory = AndroidTagControllerDialogFactory(this, layoutInflater),
            sessionDialogFactory = sessionDialogFactory,
            courseDialogFactory = courseDialogFactory
        )

        importSessionController = ImportSessionController(
            interactionProvider = interactionProvider,
            db = db,
            scope = lifecycleScope,
            dataProcessorProvider = dataProcessorProvider
        )
        importStudentController = ImportStudentController(
            interactionProvider = interactionProvider,
            db = db,
            scope = lifecycleScope,
            dataProcessorProvider = dataProcessorProvider
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

        val readerInteractionProvider = AndroidReaderInteractionProvider(this, secureStoreManager)
        
        readerDiscoveryController = ReaderDiscoveryController(
            activity = this,
            secureStoreManager = secureStoreManager,
            interactionProvider = readerInteractionProvider,
            scope = lifecycleScope
        )

        readerManagementController = ReaderManagementController(
            activity = this,
            db = db,
            secureStoreManager = secureStoreManager,
            interactionProvider = readerInteractionProvider,
            scope = lifecycleScope
        )

        // Initialize Session Controller
        sessionController = SessionController(
            activity = this,
            context = this,
            scope = lifecycleScope,
            db = db,
            layoutInflater = layoutInflater,
            rvAttendance = findViewById(R.id.rvAttendance),
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
            onPulldown = { importBacklogController.startImportFlow() },
            onSyncTimeout = { importBacklogController.dismissActiveDialog() }
        )

        importBacklogController = ImportBacklogController(
            interactionProvider = interactionProvider,
            scope = lifecycleScope,
            db = db,
            orchestrator = readerOrchestrator,
            toggleSpinner = { sessionController.showLayoutRefreshSpinner(it) },
            registerAttendance = { student, time -> sessionController.registerAttendance(student, time) },
            refreshAttendanceList = { sessionController.loadAttendanceList() }
        )

        // Initialize Tag Controller
        tagController = TagController(
            interactionProvider = interactionProvider,
            db = db,
            scope = lifecycleScope,
            readerOrchestrator = readerOrchestrator,
            sessionController = sessionController,
            isDialogShowingCheck = { interactionProvider.isAnyDialogOpen() },
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
                readerOrchestrator?.setAppMode(
                    AppMode.ACTIVE,
                    "MainActivity Session Selection"
                )
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
        readerDiscoveryController.setupReaderList(findViewById<View>(R.id.layoutReaderManagementView))
    }

    fun openDeviceManager(address: String? = null) {
        android.util.Log.i(
            "MainActivity",
            "[UI Flow] openDeviceManager() triggered. State -> DEVICE_MANAGER"
        )
        currentState = AppState.DEVICE_MANAGER
        toggleAllViews(layoutDeviceManagerView = true)

        val managerView = findViewById<View>(R.id.layoutDeviceManagerView)
        if (managerView != null) {
            android.util.Log.i(
                "MainActivity",
                "[UI Flow] layoutDeviceManagerView found. ID: ${managerView.id}. Initializing controller..."
            )
            readerManagementController.setupReaderManagementView(managerView, address)
        } else {
            android.util.Log.e(
                "MainActivity",
                "[UI Flow Error] layoutDeviceManagerView NOT FOUND in main layout!"
            )
        }
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
        tagController.resumeNfcScanning()
    }

    override fun onPause() {
        super.onPause()
        tagController.pauseNfcScanning()
    }

    override fun onDestroy() {
        super.onDestroy()
        readerOrchestrator?.disconnect()
    }

}