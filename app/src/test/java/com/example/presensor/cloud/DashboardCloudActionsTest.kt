package com.example.presensor.cloud

import android.view.LayoutInflater
import android.widget.EditText
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.controllers.ImportStudentController
import com.example.presensor.controllers.CloudSyncController
import com.example.presensor.controllers.providers.DashboardInteractionProvider
import com.google.api.services.drive.model.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DashboardCloudActionsTest {

    private val mockProvider: DashboardInteractionProvider = mock()
    private val mockCloudSync: CloudSyncController = mock()
    private val mockImportStudent: ImportStudentController = mock()
    private val testDispatcher = UnconfinedTestDispatcher()
    
    private lateinit var actions: DashboardCloudActions
    private lateinit var activity: MainActivityForTest

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(MainActivityForTest::class.java).setup().get()
        whenever(mockProvider.getContext()).thenReturn(activity)
        whenever(mockProvider.getLayoutInflater()).thenReturn(LayoutInflater.from(activity))
        whenever(mockProvider.getString(any())).thenReturn("string")
        whenever(mockProvider.getString(any(), anyVararg())).thenReturn("string")
        
        actions = DashboardCloudActions(
            uiProvider = mockProvider,
            cloudSyncController = mockCloudSync,
            importStudentController = mockImportStudent,
            runWithCloudAuthentication = { it() },
            refreshDashboard = {},
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `triggerStudentImportCloudPicker completes full chain`() {
        val spreadsheet = File().setId("sid").setName("Sheet")
        
        actions.triggerStudentImportCloudPicker()
        
        // 1. Fetch spreadsheets
        val spreadsheetCaptor = argumentCaptor<(List<File>) -> Unit>()
        verify(mockCloudSync).fetchAvailableSpreadsheets(spreadsheetCaptor.capture())
        spreadsheetCaptor.firstValue(listOf(spreadsheet))
        
        // 2. Select spreadsheet
        val fileDialogCaptor = argumentCaptor<(File) -> Unit>()
        verify(mockCloudSync).showCloudFileDialog(any(), any(), any(), any(), fileDialogCaptor.capture())
        fileDialogCaptor.firstValue(spreadsheet)
        
        // 3. Fetch tabs
        val tabCaptor = argumentCaptor<(List<String>) -> Unit>()
        verify(mockCloudSync).fetchSpreadsheetTabs(eq("sid"), tabCaptor.capture())
        tabCaptor.firstValue(listOf("Tab1"))
        
        // 4. Select tab
        val tabDialogCaptor = argumentCaptor<(String) -> Unit>()
        verify(mockCloudSync, times(2)).showCloudFileDialog(any(), any(), any(), any(), tabDialogCaptor.capture())
        tabDialogCaptor.lastValue("Tab1")
        
        verify(mockImportStudent).importFromCloud(anyOrNull(), eq("sid"), eq("Tab1"))
    }

    @Test
    fun `triggerStudentImportCloudPicker handles empty states`() {
        actions.triggerStudentImportCloudPicker()
        val spreadsheetCaptor = argumentCaptor<(List<File>) -> Unit>()
        verify(mockCloudSync).fetchAvailableSpreadsheets(spreadsheetCaptor.capture())
        spreadsheetCaptor.firstValue(emptyList())
        verify(mockProvider).showToast(eq(R.string.toast_cloud_sheets_empty), any())

        actions.triggerStudentImportCloudPicker()
        verify(mockCloudSync, times(2)).fetchAvailableSpreadsheets(spreadsheetCaptor.capture())
        spreadsheetCaptor.lastValue(listOf(File()))
        val fileDialogCaptor = argumentCaptor<(File) -> Unit>()
        verify(mockCloudSync).showCloudFileDialog(any(), any(), any(), any(), fileDialogCaptor.capture())
        fileDialogCaptor.firstValue(File().setId("sid"))
        
        val tabCaptor = argumentCaptor<(List<String>) -> Unit>()
        verify(mockCloudSync).fetchSpreadsheetTabs(any(), tabCaptor.capture())
        tabCaptor.firstValue(emptyList())
        verify(mockProvider).showToast(eq(R.string.toast_cloud_sheet_tabs_failed), any())
    }

    @Test
    fun `triggerDatabaseExportCloudPicker triggers upload on confirm`() {
        actions.triggerDatabaseExportCloudPicker()
        ShadowLooper.idleMainLooper()
        
        val dialog = ShadowAlertDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = dialog.findViewById<EditText>(R.id.editExportSuffix)
        input?.setText("my_suffix")
        
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        
        verify(mockCloudSync).uploadBackupToDrive(eq("my_suffix"))
    }

    @Test
    fun `triggerCustomImportFlow completes full chain`() {
        actions.triggerCustomImportFlow()
        
        // 1. Fetch backups
        val backupCaptor = argumentCaptor<(List<File>) -> Unit>()
        verify(mockCloudSync).fetchAvailableBackups(backupCaptor.capture())
        
        // Case: No backups
        backupCaptor.firstValue(emptyList())
        ShadowLooper.idleMainLooper()
        assertNotNull(ShadowAlertDialog.getLatestDialog())
        (ShadowAlertDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog).dismiss()

        // Case: Has backups
        actions.triggerCustomImportFlow()
        verify(mockCloudSync, times(2)).fetchAvailableBackups(backupCaptor.capture())
        backupCaptor.lastValue(listOf(File().setId("fid").setName("File")))
        
        val fileDialogCaptor = argumentCaptor<(File) -> Unit>()
        verify(mockCloudSync).showCloudFileDialog(any(), any(), any(), any(), fileDialogCaptor.capture())
        fileDialogCaptor.firstValue(File().setId("fid"))
        
        verify(mockCloudSync).downloadAndRestoreBackup(eq("fid"), any())
    }

    @Test
    fun `triggerDatabaseImportCloudPicker simple call`() {
        actions.triggerDatabaseImportCloudPicker()
        verify(mockCloudSync).fetchAvailableBackups(any())
    }
}
