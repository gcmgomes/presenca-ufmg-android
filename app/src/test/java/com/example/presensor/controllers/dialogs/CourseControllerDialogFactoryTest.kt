package com.example.presensor.controllers.dialogs

import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.google.android.material.timepicker.MaterialTimePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CourseControllerDialogFactoryTest {

    private lateinit var activity: MainActivityForTest
    private lateinit var factory: CourseControllerDialogFactory
    private val mockDb: AppDatabase = mock()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        activity = Robolectric.buildActivity(MainActivityForTest::class.java).create().start().resume().get()
        factory = CourseControllerDialogFactory(
            activity, activity, mockDb,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )
        DialogFactory.resetForTesting()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `showCreateCourseDialog validation and insertion`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        val onCreated: () -> Unit = mock()
        val dialog = factory.showCreateCourseDialog(onCreated)
        ShadowLooper.idleMainLooper()
        
        val edtName = dialog.findViewById<EditText>(R.id.edtCourseName)
        val edtYear = dialog.findViewById<EditText>(R.id.edtCourseYear)
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        
        // Invalid name
        edtName?.setText("")
        positiveButton.performClick()
        assertNotNull(edtName?.error)
        
        // Valid inputs
        edtName?.setText("New Course")
        edtYear?.setText("2024")
        
        positiveButton.performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        verify(mockDb).insertCourse(argThat { name == "New Course" && year == 2024 })
        verify(onCreated).invoke()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `showEditCourseDialog initial values and update`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        val course = Course(id = 1L, name = "Old Name", year = 2023, semester = 2)
        val onUpdate: (Course) -> Unit = mock()
        val onEdited: () -> Unit = mock()
        
        val dialog = factory.showEditCourseDialog(course, onUpdate, onEdited)
        ShadowLooper.idleMainLooper()
        
        val edtName = dialog.findViewById<EditText>(R.id.edtCourseName)
        assertEquals("Old Name", edtName?.text.toString())
        
        edtName?.setText("Updated Name")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        
        testDispatcher.scheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        verify(mockDb).updateCourse(argThat { name == "Updated Name" && id == 1L })
        verify(onUpdate).invoke(any())
        verify(onEdited).invoke()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `time picker trigger and confirmation in course dialog`() {
        val dialog = factory.showCreateCourseDialog {}
        ShadowLooper.idleMainLooper()
        val edtStartTime = dialog.findViewById<EditText>(R.id.edtCourseStartTime)
        
        edtStartTime?.performClick()
        ShadowLooper.idleMainLooper()
        
        val fragment = activity.supportFragmentManager.findFragmentByTag("COURSE_TIME_PICKER") as? MaterialTimePicker
        assertNotNull(fragment)
        
        // Simulate time selection
        // In Robolectric, we can use reflection or just call the listener if we can get it.
        // MaterialTimePicker uses internal listeners.
        // We'll use a hack to call the positive button click listener.
        fragment?.let { picker ->
            // Trigger the internal positive button click
            // Actually, we can just find the button in the picker's view if it's inflated.
            picker.view?.findViewById<View>(com.google.android.material.R.id.material_timepicker_ok_button)?.performClick()
            ShadowLooper.idleMainLooper()
        }
        
        // Check if the text was updated in the EditText
        assertNotEquals("", edtStartTime?.text.toString())
    }
}
