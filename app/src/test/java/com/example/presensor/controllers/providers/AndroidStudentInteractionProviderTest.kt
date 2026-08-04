package com.example.presensor.controllers.providers

import com.example.presensor.MainActivityForTest
import com.example.presensor.data.entities.Student
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidStudentInteractionProviderTest {

    private lateinit var controller: ActivityController<MainActivityForTest>
    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidStudentInteractionProvider

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(MainActivityForTest::class.java)
        testActivity = controller.get()
        provider = AndroidStudentInteractionProvider(testActivity)
        controller.create()
    }

    @Test
    fun `showStudentImportPreview inflates and shows bottom sheet`() {
        var confirmed = false
        val students = listOf(Student(email = "e1", name = "N1"))
        provider.showStudentImportPreview(students, { confirmed = true }, {})
        ShadowLooper.idleMainLooper()
        
        val latestDialog = ShadowDialog.getLatestDialog() as? BottomSheetDialog
        assertNotNull(latestDialog)
        assertTrue(latestDialog!!.isShowing)

        val btnConfirm = latestDialog.findViewById<android.view.View>(com.example.presensor.R.id.btnConfirmAction)
        btnConfirm?.performClick()
        assertTrue(confirmed)
    }
}
