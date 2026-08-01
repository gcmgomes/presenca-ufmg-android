package com.example.presensor.controllers.providers

import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.BaseControllerTest
import com.example.presensor.data.SecureStoreManager
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog

class AndroidReaderInteractionProviderTest : BaseControllerTest() {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidReaderInteractionProvider
    private val mockSecureStoreManager: SecureStoreManager = mock()

    @Before
    override fun setup() {
        super.setup()
        testActivity = Robolectric.buildActivity(MainActivityForTest::class.java).create().get()
        provider = AndroidReaderInteractionProvider(testActivity, mockSecureStoreManager)
    }

    @Test
    fun `showPasswordPromptDialog inflates and shows`() {
        provider.showPasswordPromptDialog("Reader", {}, {})
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val latestDialog = ShadowDialog.getLatestDialog()
        assert(latestDialog != null)
        assert(latestDialog.isShowing)
    }
}
