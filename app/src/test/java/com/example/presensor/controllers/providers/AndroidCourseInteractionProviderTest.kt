package com.example.presensor.controllers.providers

import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.BaseControllerTest
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.Robolectric

class AndroidCourseInteractionProviderTest : BaseControllerTest() {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidCourseInteractionProvider
    private val mockCourseDialogFactory: CourseControllerDialogFactory = mock()
    private val mockSessionDialogFactory: SessionControllerDialogFactory = mock()

    @Before
    override fun setup() {
        super.setup()
        testActivity = Robolectric.buildActivity(MainActivityForTest::class.java).create().get()
        provider = AndroidCourseInteractionProvider(
            testActivity,
            mockCourseDialogFactory,
            mockSessionDialogFactory
        )
    }

    @Test
    fun `showMassDateChangeDialog delegates to factory`() {
        provider.showMassDateChangeDialog(1L)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(mockSessionDialogFactory).showMassDateChangeDialog(eq(1L))
    }
}
