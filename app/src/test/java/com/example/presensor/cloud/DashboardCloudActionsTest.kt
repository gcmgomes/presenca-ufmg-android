package com.example.presensor.cloud

import com.example.presensor.controllers.ImportStudentController
import com.example.presensor.controllers.CloudSyncController
import com.example.presensor.controllers.providers.DashboardInteractionProvider
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class DashboardCloudActionsTest {

    private val mockProvider: DashboardInteractionProvider = mock()
    private val mockCloudSync: CloudSyncController = mock()
    private val mockImportStudent: ImportStudentController = mock()
    
    private lateinit var actions: DashboardCloudActions

    @Before
    fun setup() {
        actions = DashboardCloudActions(
            uiProvider = mockProvider,
            cloudSyncController = mockCloudSync,
            importStudentController = mockImportStudent,
            runWithCloudAuthentication = { it() },
            refreshDashboard = {}
        )
    }

    @Test
    fun `triggerStudentImportCloudPicker delegates to cloud controller`() {
        actions.triggerStudentImportCloudPicker()
        verify(mockCloudSync).fetchAvailableSpreadsheets(any())
    }

    @Test
    fun `triggerDatabaseImportCloudPicker delegates to cloud controller`() {
        actions.triggerDatabaseImportCloudPicker()
        verify(mockCloudSync).fetchAvailableBackups(any())
    }
}
