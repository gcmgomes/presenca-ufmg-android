package com.example.presensor

import android.view.View
import androidx.core.view.isVisible
import com.example.presensor.MainActivity.Companion.AppState
import com.example.presensor.communication.core.AppMode
import com.example.presensor.controllers.TagController
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityLifecycleTest {

    private lateinit var controller: ActivityController<MainActivityForTest>

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(MainActivityForTest::class.java)
    }

    @Test
    fun `onCreate initializes controllers and db`() {
        val activity = controller.create().get()
        
        assertNotNull(activity.dashboardController)
        assertNotNull(activity.courseController)
        assertNotNull(activity.sessionController)
        assertNotNull(activity.tagController)
        assertNotNull(activity.getDb())
    }

    @Test
    fun `onResume and onPause handle tagController initialization states`() {
        // Case 1: NOT initialized
        val activity1 = controller.get()
        activity1.skipTagControllerInit = true
        controller.create().start().resume()
        controller.pause()
        
        // Case 2: IS initialized
        val controller2 = Robolectric.buildActivity(MainActivityForTest::class.java)
        val activity2 = controller2.create().get()
        val mockTagController = mock<TagController>()
        activity2.tagController = mockTagController
        
        controller2.resume()
        verify(mockTagController).resumeNfcScanning()
        
        controller2.pause()
        verify(mockTagController).pauseNfcScanning()
    }

    @Test
    fun `onDestroy disconnects reader orchestrator`() {
        val activity = controller.create().get()
        activity.readerOrchestrator = mock()
        
        controller.destroy()
        verify(activity.readerOrchestrator!!).disconnect()
    }

    @Test
    fun `toggleLoadingOverlay updates visibility and cancels job`() {
        val activity = controller.get() // Not created yet, loadingOverlay not initialized
        activity.toggleLoadingOverlay(true)
        
        controller.create()
        val activityCreated = controller.get()
        val mockJob = mock<kotlinx.coroutines.Job>()
        activityCreated.setCurrentOverlayJob(mockJob)
        
        activityCreated.toggleLoadingOverlay(true)
        assertTrue(activityCreated.loadingOverlay.isVisible)
        
        activityCreated.toggleLoadingOverlay(false)
        assertFalse(activityCreated.loadingOverlay.isVisible)
        verify(mockJob).cancel()
    }

    @Test
    fun `handleOnBackPressed when loading cancels operation`() {
        val activity = controller.create().get()
        activity.toggleLoadingOverlay(true)
        activity.pendingCloudAction = {}
        
        activity.handleBack()
        
        verify(activity.cloudSyncController).cancelActiveOperation()
        assertFalse(activity.loadingOverlay.isVisible)
        assertNull(activity.pendingCloudAction)
    }

    @Test
    fun `handleOnBackPressed SESSION transitions to COURSE`() {
        val activity = controller.create().get()
        activity.setAppState(AppState.SESSION)
        activity.readerOrchestrator = mock()
        
        activity.handleBack()
        
        verify(activity.readerOrchestrator!!).setAppMode(eq(AppMode.IDLE), any())
        verify(activity.sessionController).clearActiveSession()
        assertEquals(AppState.COURSE, activity.getAppState())
        assertTrue(activity.findViewById<View>(R.id.layoutCourseView).isVisible)
        verify(activity.courseController).refreshCourseUI()
    }

    @Test
    fun `handleOnBackPressed COURSE transitions to DASHBOARD`() {
        val activity = controller.create().get()
        activity.setAppState(AppState.COURSE)
        
        activity.handleBack()
        
        verify(activity.courseController).clear()
        assertEquals(AppState.DASHBOARD, activity.getAppState())
        assertTrue(activity.findViewById<View>(R.id.layoutDashboardView).isVisible)
        verify(activity.dashboardController).refreshDashboard()
    }

    @Test
    fun `handleOnBackPressed COURSE_STATS transitions to COURSE`() {
        val activity = controller.create().get()
        activity.setAppState(AppState.COURSE_STATS)
        
        activity.handleBack()
        
        verify(activity.detailedCourseController).clear()
        assertEquals(AppState.COURSE, activity.getAppState())
        assertTrue(activity.findViewById<View>(R.id.layoutCourseView).isVisible)
        verify(activity.courseController).refreshCourseUI()
    }

    @Test
    fun `handleOnBackPressed READER_MANAGEMENT transitions to DASHBOARD`() {
        val activity = controller.create().get()
        activity.setAppState(AppState.READER_MANAGEMENT)
        
        activity.handleBack()
        
        verify(activity.readerDiscoveryController).teardownDiscovery()
        assertEquals(AppState.DASHBOARD, activity.getAppState())
        assertTrue(activity.findViewById<View>(R.id.layoutDashboardView).isVisible)
        verify(activity.dashboardController).refreshDashboard()
    }

    @Test
    fun `handleOnBackPressed DEVICE_MANAGER transitions to READER_MANAGEMENT`() {
        val activity = controller.create().get()
        activity.setAppState(AppState.DEVICE_MANAGER)
        
        activity.handleBack()
        
        verify(activity.readerManagementController).teardownView()
        assertEquals(AppState.READER_MANAGEMENT, activity.getAppState())
        assertTrue(activity.findViewById<View>(R.id.layoutReaderManagementView).isVisible)
    }

    @Test
    fun `handleOnBackPressed DASHBOARD finishes activity`() {
        val activity = controller.create().get()
        activity.setAppState(AppState.DASHBOARD)
        
        activity.handleBack()
        
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `openReaderManagement transitions state and calls controller`() {
        val activity = controller.create().get()
        activity.openReaderManagement()
        
        assertEquals(AppState.READER_MANAGEMENT, activity.getAppState())
        assertTrue(activity.findViewById<View>(R.id.layoutReaderManagementView).isVisible)
        verify(activity.readerDiscoveryController).setupReaderList()
    }

    @Test
    fun `openDeviceManager transitions state and calls controller`() {
        val activity = controller.create().get()
        activity.openDeviceManager("ADDR")
        
        assertEquals(AppState.DEVICE_MANAGER, activity.getAppState())
        assertTrue(activity.findViewById<View>(R.id.layoutDeviceManagerView).isVisible)
        verify(activity.readerManagementController).setupReaderManagementView(eq("ADDR"))
    }

    @Test
    fun `runWithCloudAuthentication sets pending action and calls controller`() {
        val activity = controller.create().get()
        activity.runWithCloudAuthentication { }
        
        assertNotNull(activity.pendingCloudAction)
        verify(activity.cloudSyncController).runWithCloudAuthentication(any())
    }

    @Test
    fun `onWindowFocusChanged branch coverage`() {
        val activity = controller.create().get()
        val field = MainActivity::class.java.getDeclaredField("isCloudAuthSuccessPendingRun")
        field.isAccessible = true
        
        // case: focus gained, pending run FALSE
        field.set(activity, false)
        activity.onWindowFocusChanged(true)
        assertFalse(field.get(activity) as Boolean)
        
        // case: focus lost but pending run true
        field.set(activity, true)
        activity.onWindowFocusChanged(false)
        assertTrue(field.get(activity) as Boolean)
        
        // Case: everything true
        val spyActivity = spy(activity)
        doReturn(true).whenever(spyActivity).hasWindowFocus()
        spyActivity.onWindowFocusChanged(true)
        assertFalse(field.get(spyActivity) as Boolean) // Reset to false
    }
}
