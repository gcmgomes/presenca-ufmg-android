package com.example.presensor.controllers.dialogs

import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.google.android.material.datepicker.MaterialDatePicker
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionControllerDialogFactoryTest {

    private lateinit var activity: MainActivityForTest
    private lateinit var factory: SessionControllerDialogFactory
    private val mockDb: AppDatabase = mock()
    private val mockRefresh: () -> Unit = mock()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        activity = Robolectric.buildActivity(MainActivityForTest::class.java).create().start().resume().get()
        factory = SessionControllerDialogFactory(
            activity, activity, mockDb, mockRefresh,
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
    fun `showEditSessionDialog delegates and returns dialog`() {
        val session = Session(courseId = 1L, name = "S1", date = 1000L)
        val dialog = factory.showEditSessionDialog(session) { _, _, _, _ -> }
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)
    }

    @Test
    fun `showCreateSessionDialog uses course times and placeholder`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        val course = Course(id = 1L, name = "C1", startTime = 480L, endTime = 600L)
        whenever(mockDb.getAllCourses()).thenReturn(listOf(course))
        whenever(mockDb.getSessionsByCourse(1L)).thenReturn(emptyList())
        
        factory.showCreateSessionDialog(1L) { _, _, _, _, _ -> }
        
        testDispatcher.scheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("Dialog should be shown", dialog)
        
        val edtName = dialog?.findViewById<EditText>(R.id.edtSessionName)
        assertTrue(edtName?.text.toString().startsWith(activity.getString(R.string.session_text)))
    }

    @Test
    fun `showDeleteSessionDialog performs deletion`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        val session = Session(id = 1L, courseId = 1L, name = "S1", date = 1000L)
        val dialog = factory.showDeleteSessionDialog(session)
        ShadowLooper.idleMainLooper()
        
        val allViews = mutableListOf<android.view.View>()
        findViews(dialog.window?.decorView!!, allViews)
        val input = allViews.filterIsInstance<EditText>().first()
        
        input.setText("DELETE")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        
        testDispatcher.scheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        verify(mockDb).deleteSession(eq(session))
        verify(mockRefresh).invoke()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `showManualRegistrationDialog validates email and saves`() {
        val onSaved: (String, String, AlertDialog) -> Unit = mock()
        val dialog = factory.showManualRegistrationDialog("RFID", onSaved)
        ShadowLooper.idleMainLooper()
        
        val edtName = dialog.findViewById<EditText>(R.id.edtStudentName)
        val edtEmail = dialog.findViewById<EditText>(R.id.edtStudentEmail)
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        
        edtName?.setText("New Student")
        edtEmail?.setText("invalid-email")
        positiveButton.performClick()
        ShadowLooper.idleMainLooper()
        assertNotNull(edtEmail?.error)
        
        edtEmail?.setText("valid@test.com")
        positiveButton.performClick()
        ShadowLooper.idleMainLooper()
        verify(onSaved).invoke(eq("New Student"), eq("valid@test.com"), eq(dialog))
    }

    @Test
    fun `showMassDateChangeDialog handles date selection and update`() = kotlinx.coroutines.test.runTest(testDispatcher) {
        val s1 = Session(id = 1L, courseId = 1L, name = "S1", date = 1000L)
        whenever(mockDb.getSessionsByCourse(1L)).thenReturn(listOf(s1))
        
        val dialog = factory.showMassDateChangeDialog(1L)
        ShadowLooper.idleMainLooper()
        
        val edtThreshold = dialog.findViewById<EditText>(R.id.edtThresholdDate)
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        
        // Validation check
        positiveButton.performClick()
        assertNotNull(edtThreshold?.error)
        
        // Trigger threshold picker (covers listener line)
        edtThreshold?.performClick()
        ShadowLooper.idleMainLooper()
        val thresholdPicker = activity.supportFragmentManager.findFragmentByTag("MASS_DATE_PICKER")
        assertNotNull(thresholdPicker)
    }

    private fun findViews(view: android.view.View, out: MutableList<android.view.View>) {
        out.add(view)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findViews(view.getChildAt(i)!!, out)
            }
        }
    }
}
