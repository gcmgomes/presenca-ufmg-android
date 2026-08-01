package com.example.presensor.controllers.providers

import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.BaseControllerTest
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.TagControllerDialogFactory
import com.example.presensor.data.entities.Student
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import org.robolectric.Robolectric

class AndroidTagInteractionProviderTest : BaseControllerTest() {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidTagInteractionProvider
    private val mockTagDialogFactory: TagControllerDialogFactory = mock()
    private val mockSessionDialogFactory: SessionControllerDialogFactory = mock()

    @Before
    override fun setup() {
        super.setup()
        testActivity = Robolectric.buildActivity(MainActivityForTest::class.java).create().get()
        provider = AndroidTagInteractionProvider(
            testActivity,
            mockTagDialogFactory,
            mockSessionDialogFactory
        )
    }

    @Test
    fun `showOverwriteConfirmation delegates to factory`() {
        val student = Student(email = "test@test.com", name = "Test")
        provider.showOverwriteConfirmation(student, "RFID", {})
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        verify(mockTagDialogFactory).showOverwriteConfirmation(eq(student), eq("RFID"), any())
    }
}
