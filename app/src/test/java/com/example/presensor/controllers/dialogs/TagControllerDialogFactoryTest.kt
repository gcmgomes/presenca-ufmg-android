package com.example.presensor.controllers.dialogs

import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.controllers.TagController
import com.example.presensor.data.entities.Student
import com.google.android.material.button.MaterialButton
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
class TagControllerDialogFactoryTest {

    private lateinit var activity: MainActivityForTest
    private lateinit var factory: AndroidTagControllerDialogFactory
    private val mockTagController: TagController = mock()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        activity = Robolectric.buildActivity(MainActivityForTest::class.java).create().start().resume().get()
        factory = AndroidTagControllerDialogFactory(activity, activity.layoutInflater)
        DialogFactory.resetForTesting()
        DialogFactory.tagController = mockTagController
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `showOverwriteConfirmation displays correct message and confirms`() {
        val student = Student("test@example.com", "John Doe")
        val onConfirm: () -> Unit = mock()
        
        val dialog = factory.showOverwriteConfirmation(student, "NEW_RFID", onConfirm)
        ShadowLooper.idleMainLooper()
        
        val txtMessage = dialog.findViewById<TextView>(R.id.txtMessage)
        assertTrue(txtMessage?.text.toString().contains("John Doe"))
        
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        
        verify(onConfirm).invoke()
    }

    @Test
    fun `showBindingDialog handles manual attendance`() {
        val onManual: () -> Unit = mock()
        val dialog = factory.showBindingDialog("RFID", emptyList(), {}, onManual, {})
        ShadowLooper.idleMainLooper()
        
        val btnManual = dialog?.findViewById<MaterialButton>(R.id.btnSecondaryAction)
        btnManual?.performClick()
        ShadowLooper.idleMainLooper()
        
        verify(onManual).invoke()
        assertFalse(dialog!!.isShowing)
    }

    @Test
    fun `showBindingDialog filters student list`() {
        val students = listOf(
            Student("a@test.com", "Alice"),
            Student("b@test.com", "Bob")
        )
        val dialog = factory.showBindingDialog("RFID", students, {}, {}, {})
        ShadowLooper.idleMainLooper()
        
        val edtSearch = dialog?.findViewById<EditText>(R.id.edtStudentSearch)
        val txtHint = dialog?.findViewById<TextView>(R.id.txtSearchStudentHint)
        
        edtSearch?.setText("NonExistent")
        ShadowLooper.idleMainLooper()
        assertEquals(activity.getString(R.string.msg_no_students_found), txtHint?.text.toString())
    }

    @Test
    fun `showBindingDialog handles student selection`() {
        val student = Student("a@test.com", "Alice", rfid = null)
        val onSelected: (Student) -> Unit = mock()
        val dialog = factory.showBindingDialog("RFID", listOf(student), onSelected, {}, {})
        ShadowLooper.idleMainLooper()
        
        val rv = dialog?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvStudentSearch)
        rv?.measure(0, 0)
        rv?.layout(0, 0, 100, 100)
        
        val viewHolder = rv?.findViewHolderForAdapterPosition(0)
        viewHolder?.itemView?.performClick()
        ShadowLooper.idleMainLooper()
        
        verify(onSelected).invoke(student)
        assertFalse(dialog!!.isShowing)
    }

    @Test
    fun `showBindingDialog handles reassign confirmation`() {
        val student = Student("a@test.com", "Alice", rfid = "OLD_RFID")
        val onReassign: (Student) -> Unit = mock()
        val dialog = factory.showBindingDialog("NEW_RFID", listOf(student), {}, {}, onReassign)
        ShadowLooper.idleMainLooper()
        
        val rv = dialog?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvStudentSearch)
        rv?.measure(0, 0)
        rv?.layout(0, 0, 100, 100)
        
        val viewHolder = rv?.findViewHolderForAdapterPosition(0)
        viewHolder?.itemView?.performClick()
        ShadowLooper.idleMainLooper()
        
        val confirmDialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("Confirmation dialog should be shown", confirmDialog)
        
        confirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
        ShadowLooper.idleMainLooper()
        
        verify(onReassign).invoke(student)
        assertFalse(dialog!!.isShowing)
    }

    @Test
    fun `showBindingDialog returns null if another dialog is open`() {
        DialogFactory.setDialogOpenForTesting(true)
        val dialog = factory.showBindingDialog("RFID", emptyList(), {}, {}, {})
        assertNull(dialog)
    }
}
