package com.example.presensor.services

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.example.presensor.R
import com.example.presensor.communication.ReaderOrchestrator
import org.junit.Assert.*
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
        
        // Set the instance for static call
        ReaderStatusService.instance = service
        
        ReaderStatusService.updateStatus(ReaderOrchestrator.ConnectionState.CONNECTED)
        
        val shadowNotificationManager = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val notification = shadowNotificationManager.getNotification(1001)
        
        assertNotNull(notification)
        assertEquals("Presensor Reader: Ready", shadowOf(notification).contentText)
    }

    @Test
    fun `startService triggers correct intent`() {
        ReaderStatusService.startService(context)
        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNotNull(nextIntent)
        assertEquals(ReaderStatusService::class.java.name, nextIntent.component?.className)
    }

    @Test
    fun `stopService triggers correct intent with action stop`() {
        ReaderStatusService.stopService(context)
        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNotNull(nextIntent)
        assertEquals(ReaderStatusService::class.java.name, nextIntent.component?.className)
        assertEquals("STOP_SERVICE", nextIntent.action)
    }

    @Test
    fun `onStartCommand with ACTION_STOP stops service`() {
        val controller = Robolectric.buildService(ReaderStatusService::class.java)
        val service = controller.create().get()
        val shadowService = shadowOf(service)

        val intent = Intent(context, ReaderStatusService::class.java).apply {
            action = "STOP_SERVICE"
        }
        service.onStartCommand(intent, 0, 1)

        assertTrue(shadowService.isStoppedBySelf)
    }

    @Test
    fun `onStartCommand without action returns START_STICKY`() {
        val controller = Robolectric.buildService(ReaderStatusService::class.java)
        val service = controller.create().get()
        
        val result = service.onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)
    }

    @Test
    fun `updateNotification connected sets correct icon and text`() {
        verifyNotification(
            ReaderOrchestrator.ConnectionState.CONNECTED,
            R.drawable.ic_reader_connected,
            "Presensor Reader: Ready"
        )
    }

    @Test
    fun `updateNotification scanning sets correct icon and text`() {
        verifyNotification(
            ReaderOrchestrator.ConnectionState.SCANNING,
            R.drawable.ic_reader_disconnected,
            "Presensor Reader: Scanning..."
        )
    }

    @Test
    fun `updateNotification connecting sets correct icon and text`() {
        verifyNotification(
            ReaderOrchestrator.ConnectionState.CONNECTING,
            R.drawable.ic_reader_disconnected,
            "Presensor Reader: Connecting/Authenticating..."
        )
    }

    @Test
    fun `updateNotification disconnected sets correct icon and text`() {
        verifyNotification(
            ReaderOrchestrator.ConnectionState.DISCONNECTED,
            R.drawable.ic_reader_disconnected,
            "Presensor Reader: Disconnected"
        )
    }

    @Test
    fun `onBind returns null`() {
        val controller = Robolectric.buildService(ReaderStatusService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(null))
    }

    @Test
    fun `stopService clears instance`() {
        val controller = Robolectric.buildService(ReaderStatusService::class.java)
        val service = controller.create().get()
        ReaderStatusService.instance = service
        
        controller.destroy()
        assertEquals(null, ReaderStatusService.instance)
    }

    private fun verifyNotification(
        state: ReaderOrchestrator.ConnectionState,
        expectedIcon: Int,
        expectedText: String
    ) {
        val controller = Robolectric.buildService(ReaderStatusService::class.java)
        val service = controller.create().get()
        
        service.updateNotification(state)
        
        val shadowNotificationManager = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        val notification = shadowNotificationManager.getNotification(1001)
        
        assertNotNull(notification)
        assertEquals(expectedIcon, notification.smallIcon.resId)
        assertEquals(expectedText, shadowOf(notification).contentText)
        
        // Verify startForeground was called (notification is persistent/foreground)
        val shadowService = shadowOf(service)
        assertNotNull(shadowService.lastForegroundNotification)
        assertEquals(1001, shadowService.lastForegroundNotificationId)
    }
}

