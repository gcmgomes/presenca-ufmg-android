package com.example.presensor.controllers.providers

import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.items.DeviceItem
import com.example.presensor.data.SecureStoreManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidReaderInteractionProviderTest {

    private lateinit var controller: ActivityController<MainActivityForTest>
    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidReaderInteractionProvider
    private val mockSecureStore: SecureStoreManager = mock()

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(MainActivityForTest::class.java)
        testActivity = controller.get()
        provider = AndroidReaderInteractionProvider(testActivity, mockSecureStore)
        controller.setup()
        // Ensure layouts are inflated and IDs are available
        testActivity.setContentView(R.layout.activity_main)
    }

    @Test
    fun `showPasswordPromptDialog displays dialog and handles blank validation`() {
        var enteredPass = ""
        provider.showPasswordPromptDialog("Reader", { enteredPass = it }, {})
        ShadowLooper.idleMainLooper()
        
        val dialog = ShadowAlertDialog.getLatestDialog() as AlertDialog
        val input = dialog.findViewById<TextInputEditText>(R.id.editReaderPassword)
        
        // Blank validation
        input?.setText("")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(input?.error)
        
        input?.setText("123456")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("123456", enteredPass)
    }

    @Test
    fun `showEditReaderDialog validates old password and mismatch`() {
        var savedName = ""
        var savedPass = ""
        whenever(mockSecureStore.getAuthPasswordFor("Reader")).thenReturn("correct")
        provider.showEditReaderDialog("Reader") { n, p -> savedName = n; savedPass = p }
        ShadowLooper.idleMainLooper()
        
        val dialog = ShadowAlertDialog.getLatestDialog() as AlertDialog
        val inputName = dialog.findViewById<TextInputEditText>(R.id.editReaderName)
        val inputOld = dialog.findViewById<TextInputEditText>(R.id.editOldPassword)
        val inputNew = dialog.findViewById<TextInputEditText>(R.id.editNewPassword)
        val inputConfirm = dialog.findViewById<TextInputEditText>(R.id.editConfirmNewPassword)
        
        // Empty name
        inputName?.setText("")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(inputName?.error)
        
        // Wrong old pass
        inputName?.setText("New")
        inputOld?.setText("wrong")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(inputOld?.error)
        
        // Empty new pass
        inputOld?.setText("correct")
        inputNew?.setText("")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(inputNew?.error)

        // Mismatch
        inputNew?.setText("pass1")
        inputConfirm?.setText("pass2")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(inputConfirm?.error)
        
        // Success
        inputConfirm?.setText("pass1")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("New", savedName)
        assertEquals("pass1", savedPass)
    }

    @Test
    fun `showBacklogImportPreview setup and confirm`() {
        var confirmed = false
        provider.showBacklogImportPreview({ confirmed = true }, {})
        ShadowLooper.idleMainLooper()
        
        val dialog = provider.activeBottomSheet
        assertNotNull(dialog)
        
        val btnConfirm = dialog?.findViewById<MaterialButton>(R.id.btnConfirmAction)
        btnConfirm?.performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `toggleBacklogImportLoading updates UI`() {
        provider.showBacklogImportPreview({}, {})
        ShadowLooper.idleMainLooper()
        
        val dialog = provider.activeBottomSheet
        val pb = dialog?.findViewById<ProgressBar>(R.id.pbPreviewLoading)
        val btn = dialog?.findViewById<MaterialButton>(R.id.btnConfirmAction)
        
        provider.toggleBacklogImportLoading(true)
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, pb?.visibility)
        assertFalse(btn?.isEnabled!!)
        
        provider.toggleBacklogImportLoading(false)
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, pb?.visibility)
        assertTrue(btn.isEnabled)
    }

    @Test
    fun `setupReaderDiscoveryUI sets listeners`() {
        val switch = testActivity.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchUseReader)

        var enabledChanged = false
        provider.setupReaderDiscoveryUI({ enabledChanged = it }, {})
        ShadowLooper.idleMainLooper()
        
        switch?.isChecked = true
        assertTrue(enabledChanged)
    }

    @Test
    fun `updateDeviceList populates recycler and updates callbacks`() {
        val rv = testActivity.findViewById<RecyclerView>(R.id.readerRecyclerView)
        // First call
        provider.updateDeviceList(
            connected = listOf(DeviceItem("C", "A1", -50, isConnected = true, isConnecting = false)), 
            known = emptyList(), unknown = emptyList(), 
            {_,_ ->}, {_,_ ->}
        )
        ShadowLooper.idleMainLooper()
        val adapter1 = rv!!.adapter
        
        // Second call (updates existing adapter)
        provider.updateDeviceList(
            connected = listOf(DeviceItem("C2", "A2", -50, isConnected = true, isConnecting = false)), 
            known = emptyList(), unknown = emptyList(), 
            {_,_ ->}, {_,_ ->}
        )
        ShadowLooper.idleMainLooper()
        assertSame(adapter1, rv.adapter)
    }

    @Test
    fun `updateReaderManagementHeader updates all texts`() {
        val nameText = testActivity.findViewById<TextView>(R.id.txtDeviceName)
        val macText = testActivity.findViewById<TextView>(R.id.txtDeviceMac)
        val batteryText = testActivity.findViewById<TextView>(R.id.txtStatBattery)
        val timeText = testActivity.findViewById<TextView>(R.id.txtStatDeviceTime)
        
        provider.updateReaderManagementHeader("NewName", "MAC:123", "80%", "12:00", "5")
        ShadowLooper.idleMainLooper()
        
        assertEquals("NewName", nameText?.text.toString())
        assertEquals("MAC:123", macText?.text.toString())
        assertEquals("80%", batteryText?.text.toString())
        assertEquals("12:00", timeText?.text.toString())
    }

    @Test
    fun `updateReaderManagementStatus covers all states`() {
        val btn = testActivity.findViewById<LinearLayout>(R.id.btnDisconnect)
        val viewAccent = testActivity.findViewById<View>(R.id.viewDeviceDetailAccent)
        
        var disconnectCalled = false
        var connectCalled = false
        provider.setupReaderManagementUI({}, {}, {}, {}, { disconnectCalled = true }, { connectCalled = true }, {})

        // State: Ready
        provider.updateReaderManagementStatus(isReady = true, isConnecting = false)
        ShadowLooper.idleMainLooper()
        assertEquals(testActivity.getColor(R.color.chalk_green), (viewAccent?.background as? android.graphics.drawable.ColorDrawable)?.color)
        btn?.performClick()
        assertTrue(disconnectCalled)

        // State: Connecting
        disconnectCalled = false
        provider.updateReaderManagementStatus(isReady = false, isConnecting = true)
        ShadowLooper.idleMainLooper()
        assertEquals(testActivity.getColor(R.color.chalk_orange), (viewAccent?.background as? android.graphics.drawable.ColorDrawable)?.color)
        btn?.performClick()
        assertTrue(disconnectCalled)

        // State: Disconnected
        provider.updateReaderManagementStatus(isReady = false, isConnecting = false)
        ShadowLooper.idleMainLooper()
        assertEquals(Color.TRANSPARENT, (viewAccent?.background as? android.graphics.drawable.ColorDrawable)?.color)
        btn?.performClick()
        assertTrue(connectCalled)
    }

    @Test
    fun `openDeviceManager delegates`() {
        val spyActivity = spy(testActivity)
        doNothing().whenever(spyActivity).openDeviceManager(any())
        val providerWithSpy = AndroidReaderInteractionProvider(spyActivity, mockSecureStore)
        
        providerWithSpy.openDeviceManager("Name", "Addr")
        ShadowLooper.idleMainLooper()
        
        verify(mockSecureStore).deviceName = "Name"
        verify(spyActivity).openDeviceManager(eq("Addr"))
    }

    @Test
    fun `backlog item operations work`() {
        provider.showBacklogImportPreview({}, {})
        ShadowLooper.idleMainLooper()
        
        val item = BacklogItem("RFID", null, 0L)
        provider.addBacklogItem(item, true)
        assertEquals(1, provider.getBacklogItemCount())
        
        provider.removeBacklogItem(item)
        assertEquals(0, provider.getBacklogItemCount())
        
        provider.updateBacklogCount(5)
    }

    @Test
    fun `setupReaderManagementUI and management updates work`() {
        provider.setupReaderManagementUI({}, {}, {}, {}, {}, {}, {})
        ShadowLooper.idleMainLooper()
        
        provider.updateReaderManagementBacklog(emptyList())
        provider.setManagementRefreshing(true)
        ShadowLooper.idleMainLooper()
        
        val swipe = testActivity.findViewById<SwipeRefreshLayout>(R.id.layoutDeviceManagerView)
        assertNotNull("SwipeRefreshLayout not found", swipe)
        assertTrue(swipe!!.isRefreshing)
    }

    @Test
    fun `showDestructiveDeleteDialog delegates`() {
        provider.showDestructiveDeleteDialog("Title", "Msg") {}
        ShadowLooper.idleMainLooper()
        assertNotNull(provider.activeAlertDialog)
    }

    @Test
    fun `setReaderEnabledState and setDiscoveryRefreshing work`() {
        provider.setReaderEnabledState(true)
        provider.setDiscoveryRefreshing(true)
        ShadowLooper.idleMainLooper()
        
        val swipe = testActivity.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshReader)
        assertTrue(swipe!!.isRefreshing)
    }
}
