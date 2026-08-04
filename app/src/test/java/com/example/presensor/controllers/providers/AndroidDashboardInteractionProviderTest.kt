package com.example.presensor.controllers.providers

import com.example.presensor.MainActivityForTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidDashboardInteractionProviderTest {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidDashboardInteractionProvider

    @Before
    fun setup() {
        testActivity = Robolectric.buildActivity(MainActivityForTest::class.java).setup().get()
        provider = AndroidDashboardInteractionProvider(testActivity)
    }

    @Test
    fun `getLayoutInflater returns activity inflater`() {
        assertNotNull(provider.getLayoutInflater())
    }
}
