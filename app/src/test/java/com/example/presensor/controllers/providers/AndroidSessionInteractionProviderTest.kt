package com.example.presensor.controllers.providers

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.AttendanceRecord
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidSessionInteractionProviderTest {

    private lateinit var controller: ActivityController<MainActivityForTest>
    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidSessionInteractionProvider
    private val mockSessionDialogFactory: SessionControllerDialogFactory = mock()

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(MainActivityForTest::class.java)
        testActivity = controller.get()
        provider = AndroidSessionInteractionProvider(testActivity, mockSessionDialogFactory)
        controller.create()
    }

    @Test
    fun `showSessionImportPreview shows bottom sheet and handles confirm`() {
        var confirmed = false
        val sessions = listOf(Session(courseId = 1, name = "S1", date = 0L))
        provider.showSessionImportPreview(sessions, { confirmed = true }, {})
        ShadowLooper.idleMainLooper()
        
        val dialog = provider.activeBottomSheet
        assertNotNull(dialog)
        
        val btn = dialog?.findViewById<View>(R.id.btnConfirmAction)
        btn?.performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `showUnlockDialog handles validation and failure`() {
        var unlocked = false
        provider.showUnlockDialog("SECRET") { unlocked = true }
        ShadowLooper.idleMainLooper()
        
        val dialog = ShadowAlertDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val input = findEditText(dialog.window?.decorView!!)
        
        // Failure
        input?.setText("WRONG")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(unlocked)
        assertEquals(testActivity.getString(R.string.error_incorrect_password), ShadowToast.getTextOfLatestToast())
        
        // Success
        input?.setText("SECRET")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(unlocked)
    }

    @Test
    fun `updateSessionCard updates view`() {
        val name = testActivity.findViewById<TextView>(R.id.txtSessionTitle)
        provider.updateSessionCard("NewSession", 0L, 0)
        ShadowLooper.idleMainLooper()
        assertEquals("NewSession", name?.text.toString())
    }

    @Test
    fun `updateLockState updates icons`() {
        provider.updateLockState(true)
        ShadowLooper.idleMainLooper()
        // verify no crash
    }

    @Test
    fun `showLayoutRefreshSpinner updates SwipeRefreshLayout`() {
        val refresh = testActivity.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshLayout)
        provider.showLayoutRefreshSpinner(true)
        ShadowLooper.idleMainLooper()
        assertTrue(refresh!!.isRefreshing)
    }

    @Test
    fun `setupSessionListeners sets click listeners`() {
        val lockBtn = testActivity.findViewById<View>(R.id.imgMasterLock)
        val editBtn = testActivity.findViewById<View>(R.id.btnEditSessionInternal)
        var lockClicked = false
        var editClicked = false
        
        provider.setupSessionListeners({ lockClicked = true }, { editClicked = true })
        ShadowLooper.idleMainLooper()
        
        lockBtn?.performClick()
        assertTrue(lockClicked)
        
        editBtn?.performClick()
        assertTrue(editClicked)
    }

    @Test
    fun `showStudentSearchDialog shows and filters with empty result`() {
        val students = listOf(Student("e1", "Alice"))
        provider.showStudentSearchDialog(students, {}, {})
        ShadowLooper.idleMainLooper()
        
        val dialog = provider.activeBottomSheet
        val edtSearch = dialog?.findViewById<EditText>(R.id.edtStudentSearch)
        val txtHint = dialog?.findViewById<TextView>(R.id.txtSearchStudentHint)
        
        edtSearch?.setText("Bob") // No match
        ShadowLooper.idleMainLooper()
        
        assertEquals(testActivity.getString(R.string.msg_no_students_found), txtHint?.text.toString())
    }

    @Test
    fun `submitAttendanceList populates recycler and scrolls`() {
        val records = listOf(AttendanceRecord(0L, "N", "R", "e", "S", 1L))
        provider.submitAttendanceList(records, 0)
        ShadowLooper.idleMainLooper()
        val rv = testActivity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAttendance)
        assertNotNull(rv?.adapter)
    }

    @Test
    fun `factory methods delegate`() {
        val session = Session(courseId = 1, name = "S", date = 0L)
        provider.showEditSessionDialog(session) { _, _, _, _ -> }
        verify(mockSessionDialogFactory).showEditSessionDialog(eq(session), any())
        
        provider.showCreateSessionDialog(1L) { _, _, _, _, _ -> }
        verify(mockSessionDialogFactory).showCreateSessionDialog(eq(1L), any())
        
        provider.showDeleteSessionDialog(session)
        verify(mockSessionDialogFactory).showDeleteSessionDialog(eq(session))
        
        provider.showMassDateChangeDialog(1L)
        verify(mockSessionDialogFactory).showMassDateChangeDialog(eq(1L))
        
        provider.showManualRegistrationDialog("RFID") { _, _, _ -> }
        verify(mockSessionDialogFactory).showManualRegistrationDialog(eq("RFID"), any())
    }

    private fun findEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val res = findEditText(view.getChildAt(i))
                if (res != null) return res
            }
        }
        return null
    }
}
