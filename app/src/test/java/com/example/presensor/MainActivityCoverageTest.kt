package com.example.presensor

import android.Manifest
import android.content.Context
import android.os.Build
import android.view.View
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.example.presensor.MainActivity.Companion.AppState
import com.example.presensor.communication.core.AppMode
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityCoverageTest {

    private lateinit var controller: ActivityController<MainActivityForTest>

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(MainActivityForTest::class.java)
    }

    @Test
    fun `toggleLoadingOverlay with null overlays does nothing`() {
        val activity = controller.get()
        // loadingOverlay is lateinit, if we don't call create() it might throw if accessed
        // but toggleLoadingOverlay has a ::loadingOverlay.isInitialized check
        activity.toggleLoadingOverlay(true)
    }

    @Test
    fun `handleBackNavigation cases`() {
        val activity = controller.create().get()
        
        // Dashboard state -> finishes
        activity.setAppState(AppState.DASHBOARD)
        activity.handleBack()
        assertTrue(activity.isFinishing)

        // Reset activity for other states
        val controller2 = Robolectric.buildActivity(MainActivityForTest::class.java).create()
        val activity2 = controller2.get()
        
        // COURSE_STATS -> COURSE
        activity2.setAppState(AppState.COURSE_STATS)
        activity2.handleBack()
        assertEquals(AppState.COURSE, activity2.getAppState())

        // READER_MANAGEMENT -> DASHBOARD
        activity2.setAppState(AppState.READER_MANAGEMENT)
        activity2.handleBack()
        assertEquals(AppState.DASHBOARD, activity2.getAppState())

        // DEVICE_MANAGER -> READER_MANAGEMENT
        activity2.setAppState(AppState.DEVICE_MANAGER)
        activity2.handleBack()
        assertEquals(AppState.READER_MANAGEMENT, activity2.getAppState())
    }

    @Test
    @Config(sdk = [30])
    fun `checkAndRequestBluetoothPermissions SDK 30 granted branch`() {
        val activity = controller.create().get()
        // By default Robolectric shadow permissions might return GRANTED or we can mock it
        // The goal is to hit the 'missingPermissions.isEmpty()' branch
        // In Robolectric, we can use shadowOf(activity).grantPermissions(...)
        org.robolectric.Shadows.shadowOf(activity).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        
        val method = MainActivity::class.java.getDeclaredMethod("checkAndRequestBluetoothPermissions")
        method.isAccessible = true
        method.invoke(activity)
    }

    @Test
    @Config(sdk = [33])
    fun `checkAndRequestBluetoothPermissions SDK 33 missing notifications`() {
        val activity = controller.create().get()
        // Ensure POST_NOTIFICATIONS is denied
        org.robolectric.Shadows.shadowOf(activity).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        
        val method = MainActivity::class.java.getDeclaredMethod("checkAndRequestBluetoothPermissions")
        method.isAccessible = true
        method.invoke(activity)
    }

    @Test
    fun `openDeviceManager null address uses orchestrator address`() {
        val activity = controller.create().get()
        activity.readerOrchestrator = mock()
        whenever(activity.readerOrchestrator!!.connectedDeviceAddress).thenReturn("MOCK_ADDR")
        
        activity.openDeviceManager(null)
        verify(activity.readerManagementController).setupReaderManagementView(eq("MOCK_ADDR"))
    }
}
