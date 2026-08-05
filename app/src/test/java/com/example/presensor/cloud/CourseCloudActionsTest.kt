package com.example.presensor.cloud

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.controllers.CloudSyncController
import com.example.presensor.controllers.ImportSessionController
import com.example.presensor.controllers.providers.InteractionProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.DataLoader
import com.google.api.services.drive.model.File
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CourseCloudActionsTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val mockProvider: InteractionProvider = mock()
    private val mockImportSession: ImportSessionController = mock()
    private val mockDb: AppDatabase = mock()
    private val mockCloudSync: CloudSyncController = mock()
    private val mockDataLoader: DataLoader = mock()
    private val testDispatcher = UnconfinedTestDispatcher()
    
    private lateinit var actions: CourseCloudActions
    private lateinit var activity: MainActivityForTest

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(MainActivityForTest::class.java).setup().get()
        whenever(mockProvider.getContext()).thenReturn(activity)
        whenever(mockProvider.getString(any())).thenReturn("string")
        whenever(mockProvider.getString(any(), anyVararg())).thenReturn("string")
        
        actions = CourseCloudActions(
            uiProvider = mockProvider,
            cloudSyncController = mockCloudSync,
            importSessionController = mockImportSession,
            lifecycleOwner = activity,
            db = mockDb,
            getSelectedCourse = { Course(id = 1L, name = "Test", year = 2024, semester = 1) },
            onImportComplete = {},
            runWithCloudAuthentication = { it() },
            setCurrentOverlayJob = {},
            mainDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
            dataLoader = mockDataLoader
        )
    }

    @Test
    fun `triggerCloudScheduleImport completes full chain`() {
        actions.triggerCloudScheduleImport()
        
        val spreadsheetCallback = argumentCaptor<(List<File>) -> Unit>()
        verify(mockCloudSync).fetchAvailableSpreadsheets(spreadsheetCallback.capture())
        spreadsheetCallback.firstValue(listOf(File().setId("id1").setName("Sheet1")))
        
        val fileDialogCallback = argumentCaptor<(File) -> Unit>()
        verify(mockCloudSync).showCloudFileDialog(any(), any(), any(), any(), fileDialogCallback.capture())
        fileDialogCallback.firstValue(File().setId("id1").setName("Sheet1"))
        
        val tabCallback = argumentCaptor<(List<String>) -> Unit>()
        verify(mockCloudSync).fetchSpreadsheetTabs(eq("id1"), tabCallback.capture())
        tabCallback.firstValue(listOf("Tab1"))
        
        val tabDialogCallback = argumentCaptor<(String) -> Unit>()
        verify(mockCloudSync, atLeastOnce()).showCloudFileDialog(any(), any(), any(), any(), tabDialogCallback.capture())
        tabDialogCallback.lastValue("Tab1")
        
        verify(mockImportSession).importFromCloud(anyOrNull(), eq("id1"), eq("Tab1"), eq(1L), any())
    }

    @Test
    fun `triggerCloudScheduleImport handles empty states`() {
        whenever(mockCloudSync.fetchAvailableSpreadsheets(any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[0] as (List<File>) -> Unit).invoke(emptyList())
        }
        actions.triggerCloudScheduleImport()
        verify(mockProvider).showToast(eq(R.string.toast_cloud_sheets_empty), any())

        whenever(mockCloudSync.fetchAvailableSpreadsheets(any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[0] as (List<File>) -> Unit).invoke(listOf(File().setId("id1")))
        }
        whenever(mockCloudSync.showCloudFileDialog(any(), any(), any<List<File>>(), any(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[4] as (File) -> Unit).invoke(File().setId("id1"))
        }
        whenever(mockCloudSync.fetchSpreadsheetTabs(any(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as (List<String>) -> Unit).invoke(emptyList())
        }
        actions.triggerCloudScheduleImport()
        verify(mockProvider).showToast(eq(R.string.toast_cloud_tab_retrieval_failed), any())
    }

    @Test
    fun `performCloudSpreadsheetMatrixSync logic test`() {
        val mockSheets: Sheets = mock()
        val mockSpreadsheets: Sheets.Spreadsheets = mock()
        val mockValues: Sheets.Spreadsheets.Values = mock()
        val mockGet: Sheets.Spreadsheets.Values.Get = mock()
        val mockUpdate: Sheets.Spreadsheets.Values.Update = mock()

        whenever(mockCloudSync.getSheetsService()).thenReturn(mockSheets)
        whenever(mockSheets.spreadsheets()).thenReturn(mockSpreadsheets)
        whenever(mockSpreadsheets.values()).thenReturn(mockValues)
        whenever(mockValues.get(any(), any())).thenReturn(mockGet)
        whenever(mockValues.update(any(), any(), any())).thenReturn(mockUpdate)
        whenever(mockUpdate.setValueInputOption(any())).thenReturn(mockUpdate)
        
        whenever(mockGet.execute()).thenReturn(ValueRange().setValues(listOf(listOf("Email", "Name"), listOf("s1@test.com", "S1"))))
        
        runBlocking {
            whenever(mockDb.getSessionsByCourse(1L)).thenReturn(listOf(Session(id=10, courseId=1, name="Session", date=0L)))
            whenever(mockDb.getStudentsForCourse(1L)).thenReturn(listOf(Student("s1@test.com", "S1")))
            whenever(mockDb.getAllAttendanceForCourse(1L)).thenReturn(listOf(AttendanceRecord(0L, "S1", null, "s1@test.com", "Session", 10)))
        }

        val table = InternalDataTable(listOf("Email", "Name"), listOf(listOf("s1@test.com", "S1")))
        whenever(mockDataLoader.ingestFromGoogleSheets(any(), any(), any(), any(), any())).thenReturn(table)

        actions.performCloudSpreadsheetMatrixSync("id1", "Tab1")
        
        ShadowLooper.idleMainLooper()
        verify(mockValues).update(eq("id1"), eq("'Tab1'!A1"), any())
        verify(mockProvider).showToast(eq(R.string.toast_cloud_attendance_sync_success), any())
    }

    @Test
    fun `performCloudSpreadsheetMatrixSync handles pristine sheet and crash`() {
        val mockSheets: Sheets = mock()
        val mockSpreadsheets: Sheets.Spreadsheets = mock()
        val mockValues: Sheets.Spreadsheets.Values = mock()
        val mockGet: Sheets.Spreadsheets.Values.Get = mock()
        val mockUpdate: Sheets.Spreadsheets.Values.Update = mock()

        whenever(mockCloudSync.getSheetsService()).thenReturn(mockSheets)
        whenever(mockSheets.spreadsheets()).thenReturn(mockSpreadsheets)
        whenever(mockSpreadsheets.values()).thenReturn(mockValues)
        whenever(mockValues.get(any(), any())).thenReturn(mockGet)
        whenever(mockValues.update(any(), any(), any())).thenReturn(mockUpdate)
        whenever(mockUpdate.setValueInputOption(any())).thenReturn(mockUpdate)
        
        whenever(mockGet.execute()).thenReturn(ValueRange().setValues(emptyList()))
        
        runBlocking {
            whenever(mockDb.getSessionsByCourse(1L)).thenReturn(emptyList())
            whenever(mockDb.getStudentsForCourse(1L)).thenReturn(emptyList())
            whenever(mockDb.getAllAttendanceForCourse(1L)).thenReturn(emptyList())
        }

        val table = InternalDataTable(emptyList(), emptyList())
        whenever(mockDataLoader.ingestFromGoogleSheets(any(), any(), any(), any(), any())).thenReturn(table)

        actions.performCloudSpreadsheetMatrixSync("id1", "Tab1")
        ShadowLooper.idleMainLooper()
        verify(mockProvider).showToast(eq(R.string.toast_cloud_attendance_sync_success), any())

        whenever(mockCloudSync.getSheetsService()).thenReturn(null)
        actions.performCloudSpreadsheetMatrixSync("id1", "Tab1")
        ShadowLooper.idleMainLooper()
        verify(mockProvider).showToast(eq(R.string.toast_cloud_sync_failed), any())
    }

    @Test
    fun `triggerCloudAttendanceExport handles chain and empty states`() {
        actions.triggerCloudAttendanceExport()
        
        val spreadsheetCallback = argumentCaptor<(List<File>) -> Unit>()
        verify(mockCloudSync).fetchAvailableSpreadsheets(spreadsheetCallback.capture())
        spreadsheetCallback.firstValue(emptyList())
        verify(mockProvider).showToast(eq(R.string.toast_cloud_sheets_empty), any())

        actions.triggerCloudAttendanceExport()
        verify(mockCloudSync, times(2)).fetchAvailableSpreadsheets(spreadsheetCallback.capture())
        spreadsheetCallback.lastValue(listOf(File().setId("id1")))
        
        val fileDialogCallback = argumentCaptor<(File) -> Unit>()
        verify(mockCloudSync).showCloudFileDialog(any(), any(), any(), any(), fileDialogCallback.capture())
        fileDialogCallback.firstValue(File().setId("id1"))
        
        val tabCallback = argumentCaptor<(List<String>) -> Unit>()
        verify(mockCloudSync).fetchSpreadsheetTabs(eq("id1"), tabCallback.capture())
        tabCallback.firstValue(emptyList())
        verify(mockProvider).showToast(eq(R.string.toast_cloud_sheet_tabs_failed), any())
    }
}
