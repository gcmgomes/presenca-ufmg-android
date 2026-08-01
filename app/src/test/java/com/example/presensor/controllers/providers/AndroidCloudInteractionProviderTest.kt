package com.example.presensor.controllers.providers

import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.BaseControllerTest
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog

class AndroidCloudInteractionProviderTest : BaseControllerTest() {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidCloudInteractionProvider

    @Before
    override fun setup() {
        super.setup()
        testActivity = Robolectric.buildActivity(MainActivityForTest::class.java).create().get()
        provider = AndroidCloudInteractionProvider(testActivity)
    }

    @Test
    fun `showCloudFileDialog inflates and shows`() {
        provider.showCloudFileDialog("Title", "Subtitle", listOf("item1"), { it }, {})
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val latestDialog = ShadowDialog.getLatestDialog()
        assert(latestDialog != null)
        assert(latestDialog.isShowing)
    }
}
