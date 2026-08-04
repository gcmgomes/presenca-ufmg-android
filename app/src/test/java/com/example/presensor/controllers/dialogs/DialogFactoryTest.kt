package com.example.presensor.controllers.dialogs

import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.controllers.TagController
import com.example.presensor.controllers.dialogs.DialogFactory.showWithSmartNfcReading
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
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
class DialogFactoryTest {

    private lateinit var activity: MainActivityForTest
    private val mockTagController: TagController = mock()

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(MainActivityForTest::class.java).create().start().resume().get()
        DialogFactory.resetForTesting()
        DialogFactory.tagController = mockTagController
    }

    @Test
    fun `isAnyDialogOpen reflects state correctly`() {
        assertFalse(DialogFactory.isAnyDialogOpen())
        DialogFactory.setDialogOpenForTesting(true)
        assertTrue(DialogFactory.isAnyDialogOpen())
    }

    @Test
    fun `showWithSmartNfcReading AlertDialog lifecycle`() {
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("Test")
            .setPositiveButton("OK", null)
        
        val dialog = builder.showWithSmartNfcReading()
        ShadowLooper.idleMainLooper()
        
        assertTrue(DialogFactory.isAnyDialogOpen())
        verify(mockTagController).pauseNfcScanning()
        
        dialog.dismiss()
        ShadowLooper.idleMainLooper()
        
        assertFalse(DialogFactory.isAnyDialogOpen())
        verify(mockTagController).resumeNfcScanning()
    }

    @Test
    fun `showWithSmartNfcReading AlertDialog with resumeReader`() {
        val builder = MaterialAlertDialogBuilder(activity).setTitle("Test")
        val dialog = builder.showWithSmartNfcReading(resumeReader = true)
        ShadowLooper.idleMainLooper()
        
        dialog.dismiss()
        ShadowLooper.idleMainLooper()
        
        verify(mockTagController).resumeReader()
    }

    @Test
    fun `showWithSmartNfcReading BottomSheetDialog lifecycle`() {
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(LinearLayout(activity))
        
        dialog.showWithSmartNfcReading()
        ShadowLooper.idleMainLooper()
        
        assertTrue(DialogFactory.isAnyDialogOpen())
        verify(mockTagController).pauseNfcScanning()
        
        dialog.dismiss()
        ShadowLooper.idleMainLooper()
        
        assertFalse(DialogFactory.isAnyDialogOpen())
        verify(mockTagController).resumeNfcScanning()
    }

    @Test
    fun `showDestructiveDeleteDialog validation failure`() {
        val onConfirmed: () -> Unit = mock()
        val dialog = DialogFactory.showDestructiveDeleteDialog(
            activity, "Title", "Message", onConfirmed
        )
        
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val allViews = mutableListOf<View>()
        findViews(dialog.window?.decorView!!, allViews)
        val inputFound = allViews.filterIsInstance<EditText>().first()

        inputFound.setText("WRONG")
        positiveButton.performClick()
        
        assertNotNull(inputFound.error)
        verify(onConfirmed, never()).invoke()
        assertTrue(dialog.isShowing)
        
        inputFound.setText("DELETE")
        positiveButton.performClick()
        
        verify(onConfirmed).invoke()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `showMappingDialog confirm execution and auto-mapping`() {
        val fields = listOf("name", "email", "date")
        val columns = listOf("Student Name", "User Email", "Session Date")
        val onConfirmed: (Map<String, String>) -> Unit = mock()
        
        val dialog = DialogFactory.showMappingDialog(
            activity, fields, columns, listOf("Alice", "a@t.com", "01/01/2024"), null, onConfirmed
        )
        ShadowLooper.idleMainLooper()
        
        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmMapping)
        btnConfirm?.performClick()
        
        verify(onConfirmed).invoke(any())
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `showMappingDialog dismiss execution`() {
        val onDismissed: () -> Unit = mock()
        val dialog = DialogFactory.showMappingDialog(
            activity, listOf("name"), listOf("Col1"), null, onDismissed, {}
        )
        
        dialog.dismiss()
        ShadowLooper.idleMainLooper()
        
        verify(onDismissed).invoke()
    }

    @Test
    fun `showSessionEntryDialog validation and confirm`() {
        val onConfirmed: (String, Long, Long?, Long?) -> Unit = mock()
        val dialog = DialogFactory.showSessionEntryDialog(
            activity, activity.supportFragmentManager, R.string.title_new_session, 
            R.string.action_create, onConfirmed = onConfirmed
        )
        
        val edtName = dialog.findViewById<EditText>(R.id.edtSessionName)
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        
        // Empty name
        edtName?.setText("")
        positiveButton.performClick()
        verify(onConfirmed, never()).invoke(any(), any(), any(), any())
        assertTrue(dialog.isShowing)
        
        // Valid name
        edtName?.setText("Session A")
        positiveButton.performClick()
        verify(onConfirmed).invoke(eq("Session A"), any(), anyOrNull(), anyOrNull())
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `showSessionEntryDialog trigger date picker`() {
        val dialog = DialogFactory.showSessionEntryDialog(
            activity, activity.supportFragmentManager, R.string.title_new_session, 
            R.string.action_create, onConfirmed = { _, _, _, _ -> }
        )
        ShadowLooper.idleMainLooper()
        
        val edtDate = dialog.findViewById<TextInputEditText>(R.id.edtSessionDate)
        edtDate?.performClick()
        ShadowLooper.idleMainLooper()
        
        assertNotNull(activity.supportFragmentManager.findFragmentByTag("SESSION_DATE_PICKER"))
    }

    @Test
    fun `showSessionEntryDialog trigger time pickers`() {
        val dialog = DialogFactory.showSessionEntryDialog(
            activity, activity.supportFragmentManager, R.string.title_new_session, 
            R.string.action_create, onConfirmed = { _, _, _, _ -> }
        )
        ShadowLooper.idleMainLooper()
        
        val edtStart = dialog.findViewById<TextInputEditText>(R.id.edtSessionStartTime)
        edtStart?.performClick()
        ShadowLooper.idleMainLooper()
        assertNotNull(activity.supportFragmentManager.findFragmentByTag("SESSION_TIME_PICKER"))

        val edtEnd = dialog.findViewById<TextInputEditText>(R.id.edtSessionEndTime)
        edtEnd?.performClick()
        ShadowLooper.idleMainLooper()
        assertNotNull(activity.supportFragmentManager.findFragmentByTag("SESSION_TIME_PICKER"))
    }

    private fun findViews(view: View, out: MutableList<View>) {
        out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findViews(view.getChildAt(i)!!, out)
            }
        }
    }
}
