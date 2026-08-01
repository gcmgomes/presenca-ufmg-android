package com.example.presensor.controllers.providers

import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.BaseControllerTest
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.data.entities.Session
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.Robolectric

class AndroidSessionInteractionProviderTest : BaseControllerTest() {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidSessionInteractionProvider
    private val mockSessionDialogFactory: SessionControllerDialogFactory = mock()

    @Before
    override fun setup() {
        super.setup()
        testActivity = Robolectric.buildActivity(MainActivityForTest::class.java).create().get()
        provider = AndroidSessionInteractionProvider(testActivity, mockSessionDialogFactory)
    }

    @Test
    fun `showEditSessionDialog delegates to factory`() {
        val session = Session(courseId = 1, name = "S1", date = 1000L)
        provider.showEditSessionDialog(session, { _, _ -> })
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(mockSessionDialogFactory).showEditSessionDialog(eq(session), any())
    }
}
