package com.example.presensor

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import org.mockito.kotlin.mock

class MainActivityForTest : MainActivity() {
    var skipTagControllerInit = false

    fun setAppState(state: MainActivity.Companion.AppState) {
        currentState = state
    }

    fun getAppState(): MainActivity.Companion.AppState = currentState

    fun handleBack() {
        handleBackNavigation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun initializeDependenciesAndControllers(
        mainDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) {
        // Skip heavy init
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        
        // Initialize minimal required lateinits
        secureStoreManager = mock()
        appDatabase = mock()
        dashboardController = mock()
        courseController = mock()
        detailedCourseController = mock()
        sessionController = mock()
        if (!skipTagControllerInit) {
            tagController = mock()
        }
        importSessionController = mock()
        importStudentController = mock()
        readerDiscoveryController = mock()
        readerManagementController = mock()
        importBacklogController = mock()
        cloudSyncController = mock()

        currentBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        }
    }
}
