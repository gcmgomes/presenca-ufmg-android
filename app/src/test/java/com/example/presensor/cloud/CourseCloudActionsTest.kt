package com.example.presensor.cloud

import androidx.lifecycle.LifecycleOwner
import com.example.presensor.controllers.ImportSessionController
import com.example.presensor.controllers.CloudSyncController
import com.example.presensor.controllers.providers.InteractionProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class CourseCloudActionsTest {

    private val mockProvider: InteractionProvider = mock()
    private val mockCloudSync: CloudSyncController = mock()
    private val mockImportSession: ImportSessionController = mock()
    private val mockLifecycle: LifecycleOwner = mock()
    private val mockDb: AppDatabase = mock()
    
    private lateinit var actions: CourseCloudActions

    @Before
    fun setup() {
        actions = CourseCloudActions(
            uiProvider = mockProvider,
            cloudSyncController = mockCloudSync,
            importSessionController = mockImportSession,
            lifecycleOwner = mockLifecycle,
            db = mockDb,
            getSelectedCourse = { Course(id = 1L, name = "Test") },
            onImportComplete = {},
            runWithCloudAuthentication = { it() },
            setCurrentOverlayJob = {}
        )
    }

    @Test
    fun `triggerCloudScheduleImport delegates to cloud controller`() {
        actions.triggerCloudScheduleImport()
        verify(mockCloudSync).fetchAvailableSpreadsheets(any())
    }

    @Test
    fun `triggerCloudAttendanceExport delegates to cloud controller`() {
        actions.triggerCloudAttendanceExport()
        verify(mockCloudSync).fetchAvailableSpreadsheets(any())
    }
}
