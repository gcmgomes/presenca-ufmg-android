package com.example.presensor.tools.providers

import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.presensor.R
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PreviewProviderTest {

    private val provider = AndroidPreviewProvider()

    @Test
    fun `showSessionImportPreview displays BottomSheetDialog and triggers onConfirm`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        
        var confirmed = false
        val sessions = listOf(Session(name = "Session 1", date = 0L, courseId = 1L))

        provider.showSessionImportPreview(activity, sessions, { confirmed = true }, {})

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)

        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmAction)
        btnConfirm.performClick()

        assertTrue(confirmed)
        assertTrue(!dialog.isShowing)
    }

    @Test
    fun `showStudentImportPreview displays BottomSheetDialog and triggers onConfirm`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)

        var confirmed = false
        val students = listOf(Student(name = "Student 1", email = "student1@example.com"))

        provider.showStudentImportPreview(activity, students, { confirmed = true }, {})

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)

        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmAction)
        btnConfirm.performClick()

        assertTrue(confirmed)
        assertTrue(!dialog.isShowing)
    }

    @Test
    fun `showSessionImportPreview triggers onDismiss when dialog is dismissed without confirmation`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)

        var dismissed = false
        val sessions = listOf(Session(name = "Session 1", date = 0L, courseId = 1L))

        provider.showSessionImportPreview(activity, sessions, {}, { dismissed = true })

        val dialog = ShadowDialog.getLatestDialog()
        dialog.dismiss()
        ShadowLooper.idleMainLooper()

        assertTrue(dismissed)
    }
}
