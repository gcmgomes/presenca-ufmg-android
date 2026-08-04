package com.example.presensor.controllers.providers

import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.items.ActionItem
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidCourseInteractionProviderTest {

    private lateinit var controller: ActivityController<MainActivityForTest>
    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidCourseInteractionProvider
    private val mockCourseDialogFactory: CourseControllerDialogFactory = mock()
    private val mockSessionDialogFactory: SessionControllerDialogFactory = mock()

    @Before
    fun setup() {
        controller = Robolectric.buildActivity(MainActivityForTest::class.java)
        testActivity = controller.get()
        provider = AndroidCourseInteractionProvider(testActivity, mockCourseDialogFactory, mockSessionDialogFactory)
        controller.create()
    }

    @Test
    fun `setupCourseUtilsAccordion sets click listener`() {
        val header = testActivity.findViewById<View>(R.id.layoutUtilsHeader)
        assertNotNull(header)

        var clicked = false
        provider.setupCourseUtilsAccordion { clicked = true }
        ShadowLooper.idleMainLooper()

        header?.performClick()
        assertTrue(clicked)
    }

    @Test
    fun `setUtilsExpandIconRotation starts animation`() {
        val icon = testActivity.findViewById<View>(R.id.imgUtilsExpandIcon)
        assertNotNull(icon)

        provider.setUtilsExpandIconRotation(180f)
        ShadowLooper.idleMainLooper()
        assertEquals(180f, icon?.rotation ?: 0f, 0.01f)
    }

    @Test
    fun `setUtilsContentVisibility toggles visibility`() {
        val content = testActivity.findViewById<View>(R.id.layoutUtilsContent)
        assertNotNull(content)

        provider.setUtilsContentVisibility(true)
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, content?.visibility)

        provider.setUtilsContentVisibility(false)
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, content?.visibility)
    }

    @Test
    fun `refreshSessionsList populates all categories and handles clicks`() {
        val container = testActivity.findViewById<LinearLayout>(R.id.sessionContainer)
        assertNotNull(container)

        val now = java.time.Instant.now().toEpochMilli()
        val sessions = listOf(
            Session(id = 1L, courseId = 1L, name = "S_THIS_WEEK", date = now, startTime = 60, endTime = 120),
            Session(id = 2L, courseId = 1L, name = "S_UPCOMING", date = now + 86400000 * 14, isLocked = true),
            Session(id = 3L, courseId = 1L, name = "S_PAST", date = now - 86400000 * 14)
        )

        var selected: Session? = null
        var lockToggled: Session? = null
        provider.refreshSessionsList(sessions, { selected = it }, { lockToggled = it }, {}, {})
        ShadowLooper.idleMainLooper()

        val txtSession = findViewWithText(container!!, "S_THIS_WEEK")
        assertNotNull("Session text not found", txtSession)
        
        val card = findParentOfType(txtSession!!, MaterialCardView::class.java)
        assertNotNull("MaterialCardView not found", card)
        
        card?.performClick()
        assertEquals(sessions[0], selected)

        val lockIcon = card?.findViewById<View>(R.id.imgSessionLockOnSessionView)
        lockIcon?.performClick()
        assertEquals(sessions[0], lockToggled)
    }

    @Test
    fun `refreshSessionsList with empty sessions clears container`() {
        val container = testActivity.findViewById<LinearLayout>(R.id.sessionContainer)
        provider.refreshSessionsList(emptyList(), {}, {}, {}, {})
        ShadowLooper.idleMainLooper()
        assertEquals(0, container!!.childCount)
    }

    @Test
    fun `updateCourseHeader calls UiUtils and sets edit listener`() {
        val courseView = testActivity.findViewById<View>(R.id.layoutCourseView)
        val course = Course(id = 1L, name = "Course 1")
        var editRequested = false
        
        provider.updateCourseHeader(course, emptySet(), emptySet(), emptyList()) { editRequested = true }
        ShadowLooper.idleMainLooper()
        
        val editBtn = courseView?.findViewById<View>(R.id.btnEditCourse)
        editBtn?.performClick()
        assertTrue(editRequested)
    }

    @Test
    fun `setupQuickActions initializes viewpager and tablayout`() {
        val viewPager = testActivity.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.utilsViewPager)
        val action = ActionItem("Action", R.drawable.ic_import) {}
        
        provider.setupQuickActions(listOf(action), listOf("Title"))
        ShadowLooper.idleMainLooper()

        assertNotNull(viewPager?.adapter)
    }

    @Test
    fun `launchers and Picker registration work`() {
        provider.launchExportPicker("test.csv")
        provider.launchImportPicker()
        provider.registerImportSessionLauncher { }
        provider.registerExportLauncher { }
    }

    @Test
    fun `cloud actions and dialogs delegates`() {
        provider.initializeCourseCloudActions({ null }, {})
        provider.triggerCloudScheduleImport {}
        provider.triggerCloudAttendanceExport()
        
        provider.showCreateCourseDialog {}
        verify(mockCourseDialogFactory).showCreateCourseDialog(any())
        
        val course = Course(name = "Test")
        provider.showEditCourseDialog(course, {}, {})
        verify(mockCourseDialogFactory).showEditCourseDialog(eq(course), any(), any())

        val session = Session(id = 1L, courseId = 1L, name = "S1", date = 0L)
        provider.showDeleteSessionDialog(session)
        verify(mockSessionDialogFactory).showDeleteSessionDialog(eq(session))
        
        provider.showMassDateChangeDialog(1L)
        verify(mockSessionDialogFactory).showMassDateChangeDialog(1L)
        
        provider.showCreateSessionDialog(1L) { _, _, _, _, _ -> }
        verify(mockSessionDialogFactory).showCreateSessionDialog(eq(1L), any())
    }

    @Test
    fun `importSessionsFromCsv calls controller`() {
        val uri = mock<Uri>()
        provider.importSessionsFromCsv(uri, 1L) {}
        verify(testActivity.importSessionController).importFromLocal(eq(uri), eq(1L), any())
    }

    @Test
    fun `openOutputStream handles call`() {
        val uri = mock<Uri>()
        provider.openOutputStream(uri)
    }

    private fun findViewWithText(root: android.view.ViewGroup, text: String): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is android.widget.TextView && child.text == text) return child
            if (child is android.view.ViewGroup) {
                val found = findViewWithText(child, text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun <T : View> findParentOfType(view: View, type: Class<T>): T? {
        var current = view.parent
        while (current != null && !type.isInstance(current)) {
            current = current.parent
        }
        @Suppress("UNCHECKED_CAST")
        return current as? T
    }
}
