package com.example.presensor.controllers.providers

import android.nfc.NfcAdapter
import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.dialogs.TagControllerDialogFactory
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.data.entities.Student
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AndroidTagInteractionProviderTest.ShadowNfcAdapter::class])
class AndroidTagInteractionProviderTest {

    private lateinit var controller: ActivityController<MainActivityForTest>
    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidTagInteractionProvider
    private val mockTagDialogFactory: TagControllerDialogFactory = mock()
    private val mockSessionDialogFactory: SessionControllerDialogFactory = mock()

    @Implements(NfcAdapter::class)
    class ShadowNfcAdapter {
        companion object {
            @JvmStatic
            @Implementation
            fun getDefaultAdapter(context: android.content.Context): NfcAdapter = mock()
        }
    }

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(MainActivityForTest::class.java)
        testActivity = controller.get()
        provider = AndroidTagInteractionProvider(testActivity, mockTagDialogFactory, mockSessionDialogFactory)
        controller.setup()
    }

    @Test
    fun `showOverwriteConfirmation delegates to factory`() {
        val student = Student("test@example.com", "Name")
        provider.showOverwriteConfirmation(student, "RFID", {})
        ShadowLooper.idleMainLooper()
        verify(mockTagDialogFactory).showOverwriteConfirmation(eq(student), eq("RFID"), any())
    }

    @Test
    fun `showBindingDialog delegates to factory`() {
        provider.showBindingDialog("RFID", emptyList(), {}, {}, {})
        ShadowLooper.idleMainLooper()
        verify(mockTagDialogFactory).showBindingDialog(eq("RFID"), any(), any(), any(), any())
    }

    @Test
    fun `showManualRegistrationDialog delegates to session factory`() {
        provider.showManualRegistrationDialog("RFID") { _, _, _ -> }
        ShadowLooper.idleMainLooper()
        verify(mockSessionDialogFactory).showManualRegistrationDialog(eq("RFID"), any())
    }

    @Test
    fun `toggleNfcScanning handles call with and without dialog`() {
        val mockCallback = mock<NfcAdapter.ReaderCallback>()
        
        DialogFactory.resetForTesting()
        provider.toggleNfcScanning(true, mockCallback)
        ShadowLooper.idleMainLooper()
        
        DialogFactory.setDialogOpenForTesting(true)
        provider.toggleNfcScanning(true, mockCallback)
        ShadowLooper.idleMainLooper()

        provider.toggleNfcScanning(false, null)
        ShadowLooper.idleMainLooper()
    }
}
