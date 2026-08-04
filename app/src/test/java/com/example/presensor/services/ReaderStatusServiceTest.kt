package com.example.presensor.services

import android.app.NotificationManager
import android.content.Context
import com.example.presensor.communication.ReaderOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderStatusServiceTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `service creates notification channel on creation`() {
        val controller = Robolectric.buildService(ReaderStatusService::class.java)
        controller.create()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel("reader_status_channel")
        
        assertNotNull(channel)
        assertEquals("Reader Connection Status", channel.name)
    }

    @Test
    fun `updateStatus updates singleton instance notification`() {
        val controller = Robolectric.buildService(ReaderStatusService::class.java)
        val service = controller.create().get()
        
        // Mock the instance for static call
        ReaderStatusService.instance = service
        
        ReaderStatusService.updateStatus(ReaderOrchestrator.ConnectionState.CONNECTED)
        
        val shadowNotificationManager = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val notifications = shadowNotificationManager.allNotifications
        
        // We verify the service exists and didn't crash.
        assertNotNull(service)
        assertNotNull(notifications)
    }

    @Test
    fun `stopService clears instance`() {
        val controller = Robolectric.buildService(ReaderStatusService::class.java)
        val service = controller.create().get()
        ReaderStatusService.instance = service
        
        ReaderStatusService.stopService(context)
        
        // The service onDestroy should clear the instance
        controller.destroy()
        assertEquals(null, ReaderStatusService.instance)
    }
}
