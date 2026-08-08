package com.example.presensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityRealInitTest {

    @Before
    fun setup() {
        val bluetoothManager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val shadowBluetoothAdapter: ShadowBluetoothAdapter = Shadows.shadowOf(bluetoothManager.adapter)
        shadowBluetoothAdapter.setEnabled(true)
    }

    @Test
    fun `test real initialization`() {
        // Use the real MainActivity to cover initializeDependenciesAndControllers
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        
        // This will call onCreate -> initializeDependenciesAndControllers
        val activity = controller.create().get()
        
        assertNotNull(activity.appDatabase)
        assertNotNull(activity.dashboardController)
        assertNotNull(activity.courseController)
        assertNotNull(activity.readerOrchestrator)
        
        // Verify some interactions or state if needed
    }
}
