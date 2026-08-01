package com.example.presensor.controllers.providers

import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.BaseControllerTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowToast

class BaseAndroidInteractionProviderTest : BaseControllerTest() {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: ConcreteInteractionProvider

    class ConcreteInteractionProvider(activity: MainActivityForTest) : BaseAndroidInteractionProvider(activity)

    @Before
    override fun setup() {
        super.setup()
        testActivity = spy(Robolectric.buildActivity(MainActivityForTest::class.java).create().get())
        provider = ConcreteInteractionProvider(testActivity)
    }

    @Test
    fun `showToast with String displays toast`() {
        provider.showToast("Test Toast", true)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assert(ShadowToast.getTextOfLatestToast() == "Test Toast")
    }

    @Test
    fun `toggleLoading delegates to activity`() {
        provider.toggleLoading(true)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(testActivity).toggleLoadingOverlay(true)
    }
}
