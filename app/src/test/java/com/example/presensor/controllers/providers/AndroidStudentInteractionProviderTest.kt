package com.example.presensor.controllers.providers

import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.BaseControllerTest
import com.example.presensor.data.entities.Student
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowDialog

class AndroidStudentInteractionProviderTest : BaseControllerTest() {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidStudentInteractionProvider

    @Before
    override fun setup() {
        super.setup()
        testActivity = Robolectric.buildActivity(MainActivityForTest::class.java).create().get()
        provider = AndroidStudentInteractionProvider(testActivity)
    }

    @Test
    fun `showStudentImportPreview inflates and shows bottom sheet`() {
        val students = listOf(Student(email = "e1", name = "N1"))
        provider.showStudentImportPreview(students, {}, {})
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        
        val latestDialog = ShadowDialog.getLatestDialog()
        assert(latestDialog != null)
        assert(latestDialog.isShowing)
    }
}
