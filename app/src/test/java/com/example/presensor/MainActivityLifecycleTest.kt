package com.example.presensor

import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.controllers.TagController
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

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
    fun `onResume resumes NFC scanning`() {
        val activity = controller.create().get()
        val mockTagController = mock<TagController>()
        activity.tagController = mockTagController
        
        controller.resume()
        verify(mockTagController).resumeNfcScanning()
    }

    @Test
    fun `onPause pauses NFC scanning`() {
        val activity = controller.create().get()
        val mockTagController = mock<TagController>()
        activity.tagController = mockTagController
        
        controller.pause()
        verify(mockTagController).pauseNfcScanning()
    }
}
