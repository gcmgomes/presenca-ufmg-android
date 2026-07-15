package com.example.presensor.tools.providers

import android.widget.Toast
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ToastProviderTest {

    @Test
    fun `showToast displays a toast with correct message and duration`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = AndroidToastProvider(context)
        val message = "Test Message"
        val duration = Toast.LENGTH_LONG

        provider.showToast(message, duration)

        val latestToast = ShadowToast.getLatestToast()
        assertEquals(message, ShadowToast.getTextOfLatestToast())
        assertEquals(duration, latestToast.duration)
    }
}
