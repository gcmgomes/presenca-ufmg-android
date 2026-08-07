package com.example.presensor.controllers

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.adapters.ActionsPageAdapter
import com.example.presensor.controllers.providers.DashboardInteractionProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.InputStream
import java.io.OutputStream
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardControllerTest : BaseControllerTest() {

    @Mock
    private lateinit var mockDb: AppDatabase
    @Mock
    private lateinit var mockUiProvider: DashboardInteractionProvider
    @Mock
    private lateinit var mockCloudSyncController: CloudSyncController
    @Mock
    private lateinit var mockImportStudentController: ImportStudentController

    private lateinit var dashboardController: DashboardController
    private lateinit var mainActivity: MainActivity

    private val onCourseSelected: (Course) -> Unit = mock()
    private val onCourseLongClicked: (Course) -> Unit = mock()
    private val onCourseCreateRequested: (() -> Unit) -> Unit = mock()
    private val onCourseEditRequested: (Course) -> Unit = mock()

    class TestMainActivity : MainActivity() {
        override fun initializeDependenciesAndControllers(
            mainDispatcher: kotlinx.coroutines.CoroutineDispatcher,
            ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
        ) {
            // Skip real initialization to avoid noise
        }
    }

    @Before
    override fun setup() {
        super.setup()
        MockitoAnnotations.openMocks(this)
        
        mainActivity = Robolectric.buildActivity(TestMainActivity::class.java).create().get()
        mainActivity.setContentView(R.layout.activity_main)

        dashboardController = DashboardController(
            activity = mainActivity,
            db = mockDb,
            scope = mainActivity.lifecycleScope,
            uiProvider = mockUiProvider,
            cloudSyncController = mockCloudSyncController,
            importStudentController = mockImportStudentController,
            onCourseSelected = onCourseSelected,
            onCourseLongClicked = onCourseLongClicked,
            onCourseCreateRequested = onCourseCreateRequested,
            onCourseEditRequested = onCourseEditRequested,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @Test
    fun `refreshDashboard with empty database should show current term but no courses`() = runTest {
        whenever(mockDb.getAllCourses()).thenReturn(emptyList())

        dashboardController.refreshDashboard()

        val container = mainActivity.findViewById<LinearLayout>(R.id.currentCoursesContainer)
        assertEquals(0, container.childCount)
        
        val txtCurrentTerm = mainActivity.findViewById<TextView>(R.id.txtCurrentTerm)
        assertNotNull(txtCurrentTerm.text)
    }

    @Test
    fun `refreshDashboard with various courses should group them into sections`() = runTest {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val semester = if (calendar.get(Calendar.MONTH) < 6) 1 else 2
        
        val courseCurrent = Course(id = 1, name = "Current", year = year, semester = semester)
        val courseFuture = Course(id = 2, name = "Future", year = year + 1, semester = 1)
        val coursePast = Course(id = 3, name = "Past", year = year - 1, semester = 1)
        
        whenever(mockDb.getAllCourses()).thenReturn(listOf(courseCurrent, courseFuture, coursePast))

        dashboardController.refreshDashboard()

        val container = mainActivity.findViewById<LinearLayout>(R.id.currentCoursesContainer)
        // Verify we have headers and cards. 
        // At least 3 cards + section headers
        var cardCount = 0
        for (i in 0 until container.childCount) {
            if (container.getChildAt(i).findViewById<TextView>(R.id.txtCourseName) != null) {
                cardCount++
            }
        }
        assertEquals(3, cardCount)
    }

    @Test
    fun `refreshDashboard with filter should filter courses by name`() = runTest {
        val course1 = Course(id = 1, name = "Android Development", year = 2024, semester = 1)
        val course2 = Course(id = 2, name = "iOS Development", year = 2024, semester = 1)
        
        whenever(mockDb.getAllCourses()).thenReturn(listOf(course1, course2))

        dashboardController.refreshDashboard("Android")

        val container = mainActivity.findViewById<LinearLayout>(R.id.currentCoursesContainer)
        var androidFound = false
        var iosFound = false
        for (i in 0 until container.childCount) {
            val name = container.getChildAt(i).findViewById<TextView>(R.id.txtCourseName)?.text?.toString()
            if (name == "Android Development") androidFound = true
            if (name == "iOS Development") iosFound = true
        }
        assertTrue(androidFound)
        assertFalse(iosFound)
    }

    @Test
    fun `setupOnClickListeners should wire up FAB`() {
        dashboardController.setupOnClickListeners()
        
        val fab = mainActivity.findViewById<FloatingActionButton>(R.id.btnCreateCourse)
        fab.performClick()
        
        verify(onCourseCreateRequested).invoke(any())
    }

    @Test
    fun `setupQuickActionsAccordion should expand and collapse the layout`() {
        dashboardController.setupQuickActionsAccordion()
        
        val expandableLayout = mainActivity.findViewById<LinearLayout>(R.id.layoutActionsContent)
        val header = mainActivity.findViewById<RelativeLayout>(R.id.layoutDashboardActionsHeader)
        
        assertTrue("Initially collapsed", expandableLayout.isGone)
        
        header.performClick()
        assertFalse("Expanded after click", expandableLayout.isGone)
        
        header.performClick()
        assertTrue("Collapsed after second click", expandableLayout.isGone)
    }

    @Test
    fun `searchView query changes should trigger refreshDashboard`() = runTest {
        whenever(mockDb.getAllCourses()).thenReturn(emptyList())
        
        val searchView = mainActivity.findViewById<SearchView>(R.id.courseSearchView)
        searchView.setQuery("new query", false)
        
        // SearchView callback might be async or delayed, but Robolectric should handle it
        verify(mockDb, atLeastOnce()).getAllCourses()
    }

    @Test
    fun `course card clicks should trigger proper callbacks`() = runTest {
        val course = Course(id = 1, name = "Test Course", year = 2024, semester = 1)
        whenever(mockDb.getAllCourses()).thenReturn(listOf(course))
        
        dashboardController.refreshDashboard()
        
        val container = mainActivity.findViewById<LinearLayout>(R.id.currentCoursesContainer)
        var card: View? = null
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.findViewById<TextView>(R.id.txtCourseName)?.text == "Test Course") {
                card = v
                break
            }
        }
        
        assertNotNull(card)
        
        // Test normal click
        card?.performClick()
        verify(onCourseSelected).invoke(course)
        
        // Test long click
        card?.performLongClick()
        verify(onCourseLongClicked).invoke(course)
        
        // Test edit button click
        val editBtn = card?.findViewById<ImageView>(R.id.imgEditCourseDashboard)
        editBtn?.performClick()
        verify(onCourseEditRequested).invoke(course)
    }

    @Test
    fun `handleDumpUriSelected success should show success toast`() = runTest {
        val uri = Uri.parse("content://test/dump")
        val outputStream = mock<OutputStream>()
        
        // ShadowContentResolver doesn't easily mock openOutputStream, 
        // so we might need a workaround or just verify it doesn't crash if we can't fully mock it.
        // But since we made it internal, we can test the core logic.
        
        val contextSpy = spy(mainActivity)
        val contentResolverMock = mock<android.content.ContentResolver>()
        whenever(contextSpy.contentResolver).thenReturn(contentResolverMock)
        whenever(contentResolverMock.openOutputStream(uri)).thenReturn(outputStream)
        whenever(mockDb.performFullDatabaseDump(outputStream)).thenReturn(true)
        
        // Re-create controller with spy activity if needed, or just use reflection to set activity field
        // For simplicity, let's use the shadow if possible.
        
        shadowOf(mainActivity.contentResolver).registerOutputStream(uri, outputStream)
        whenever(mockDb.performFullDatabaseDump(any())).thenReturn(true)

        dashboardController.handleDumpUriSelected(uri)
        
        val latestToast = ShadowToast.getLatestToast()
        assertNotNull(latestToast)
        assertEquals(mainActivity.getString(R.string.toast_database_export_success), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `handleDumpUriSelected failure should show error toast`() = runTest {
        val uri = Uri.parse("content://test/dump_fail")
        shadowOf(mainActivity.contentResolver).registerOutputStream(uri, mock())
        whenever(mockDb.performFullDatabaseDump(any())).thenReturn(false)

        dashboardController.handleDumpUriSelected(uri)
        
        val latestToast = ShadowToast.getLatestToast()
        assertNotNull(latestToast)
        assertEquals(mainActivity.getString(R.string.toast_database_export_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `handleImportUriSelected success should show toast and refresh`() = runTest {
        val uri = Uri.parse("content://test/import")
        shadowOf(mainActivity.contentResolver).registerInputStream(uri, mock())
        whenever(mockDb.importFullDatabaseDump(any())).thenReturn(true)
        whenever(mockDb.getAllCourses()).thenReturn(emptyList())

        dashboardController.handleImportUriSelected(uri)
        
        val latestToast = ShadowToast.getLatestToast()
        assertNotNull(latestToast)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("success", ignoreCase = true))
        verify(mockDb, atLeastOnce()).getAllCourses()
    }

    @Test
    fun `handleImportUriSelected failure should show error toast`() = runTest {
        val uri = Uri.parse("content://test/import_fail")
        shadowOf(mainActivity.contentResolver).registerInputStream(uri, mock())
        whenever(mockDb.importFullDatabaseDump(any())).thenReturn(false)

        dashboardController.handleImportUriSelected(uri)
        
        val latestToast = ShadowToast.getLatestToast()
        assertNotNull(latestToast)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("fail", ignoreCase = true))
    }

    @Test
    fun `quick action buttons should be initialized and clickable`() {
        dashboardController.setupOnClickListeners()
        
        val viewPager = mainActivity.findViewById<ViewPager2>(R.id.actionsViewPager)
        val adapter = viewPager.adapter as? ActionsPageAdapter
        assertNotNull(adapter)
        
        // Create a ViewHolder and bind it to test the buttons
        val holder = adapter!!.onCreateViewHolder(viewPager, 0)
        adapter.onBindViewHolder(holder, 0)
        
        // Row 1: Student Import
        val btnRow1 = holder.itemView.findViewById<View>(R.id.btnRow1)
        btnRow1.performClick()
        
        // Row 2: Database Import
        val btnRow2 = holder.itemView.findViewById<View>(R.id.btnRow2)
        btnRow2.performClick()
        
        // Row 3: Database Export
        val btnRow3 = holder.itemView.findViewById<View>(R.id.btnRow3)
        btnRow3.performClick()
    }

    @Test
    fun `triggering student import should not crash`() {
        // Can't easily verify ActivityResultLauncher, but we cover the line
        dashboardController.triggerStudentImportPicker()
    }

    @Test
    fun `triggering database export should not crash`() {
        dashboardController.triggerDatabaseExportPicker()
    }

    @Test
    fun `triggering database import should not crash`() {
        dashboardController.triggerDatabaseImportPicker()
    }
}
