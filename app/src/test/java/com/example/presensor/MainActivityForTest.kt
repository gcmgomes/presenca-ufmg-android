package com.example.presensor

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import org.mockito.kotlin.mock

class MainActivityForTest : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun initializeDependenciesAndControllers() {
        // Skip heavy init
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        
        // Initialize minimal required lateinits
        secureStoreManager = mock()
        appDatabase = mock()
        dashboardController = mock()
        courseController = mock()
        detailedCourseController = mock()
        sessionController = mock()
        tagController = mock()
        importSessionController = mock()
        importStudentController = mock()
        readerDiscoveryController = mock()
        readerManagementController = mock()
        importBacklogController = mock()
        cloudSyncController = mock()

        currentBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        }
    }
}
