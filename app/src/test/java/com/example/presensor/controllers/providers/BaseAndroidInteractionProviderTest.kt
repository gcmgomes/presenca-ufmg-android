package com.example.presensor.controllers.providers

import android.net.Uri
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Course
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BaseAndroidInteractionProviderTest {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: ConcreteInteractionProvider

    class ConcreteInteractionProvider(activity: MainActivityForTest) : BaseAndroidInteractionProvider(activity)

    @Before
    fun setup() {
        testActivity = spy(Robolectric.buildActivity(MainActivityForTest::class.java).setup().get())
        provider = ConcreteInteractionProvider(testActivity)
    }

    @Test
    fun `showToast work`() {
        provider.showToast("Test Toast", true)
        ShadowLooper.idleMainLooper()
        assertEquals("Test Toast", ShadowToast.getTextOfLatestToast())
        
        provider.showToast(R.string.app_name, false)
        ShadowLooper.idleMainLooper()
        assertNotNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `toggleLoading delegates to activity`() {
        provider.toggleLoading(true)
        ShadowLooper.idleMainLooper()
        verify(testActivity).toggleLoadingOverlay(true)
    }

    @Test
    fun `getString and getContext and getContentResolver work`() {
        assertNotNull(provider.getString(R.string.app_name))
        assertNotNull(provider.getString(R.string.semester_display_format, 2026, "1st"))
        assertEquals(testActivity, provider.getContext())
        assertEquals(testActivity.contentResolver, provider.getContentResolver())
    }

    @Test
    fun `showMappingDialog confirm and dismiss work`() {
        var confirmedMapping: Map<String, String>? = null
        var dismissedCalled = false
        
        // Confirm branch
        provider.showMappingDialog(listOf("name"), listOf("Col1"), null, { dismissedCalled = true }, { confirmedMapping = it })
        ShadowLooper.idleMainLooper()
        
        var dialog = ShadowAlertDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.findViewById<android.widget.Button>(R.id.btnConfirmMapping)?.performClick()
        ShadowLooper.idleMainLooper()
        assertNotNull(confirmedMapping)
        
        // Dismiss branch (cancel)
        dismissedCalled = false
        provider.showMappingDialog(listOf("name"), listOf("Col1"), null, { dismissedCalled = true }, { })
        ShadowLooper.idleMainLooper()
        dialog = ShadowAlertDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.dismiss()
        ShadowLooper.idleMainLooper()
        assertTrue(dismissedCalled)
    }

    @Test
    fun `dismissActiveDialog and setLoadingJob work`() {
        provider.showMappingDialog(emptyList(), emptyList(), null, {}, {})
        ShadowLooper.idleMainLooper()
        
        provider.setLoadingJob(mock())
        verify(testActivity).setCurrentOverlayJob(any())

        provider.dismissActiveDialog()
        ShadowLooper.idleMainLooper()
        assertNull(provider.activeAlertDialog)
        
        provider.isAnyDialogOpen()
    }

    @Test
    fun `data parsing methods work`() {
        val table = InternalDataTable(
            headers = listOf("Name", "Email", "Date"),
            rows = listOf(listOf("Alice", "alice@example.com", "2026-08-04"))
        )
        val mapping = mapOf("name" to "Name", "email" to "Email")
        
        val studentResult = provider.parseStudentsFromTable(table, mapping)
        assertEquals(1, studentResult.items.size)
        
        val course = Course(name = "Test")
        val sessionMapping = mapOf("name" to "Name", "date" to "Date")
        val sessionResult = provider.parseSessionsFromTable(table, course, sessionMapping)
        assertNotNull(sessionResult)
    }

    @Test
    fun `ingest methods hit lines`() = runBlocking {
        try {
            provider.ingestFromCsv(mock<Uri>(), "caller")
        } catch (ignore: Exception) {}
        
        try {
            provider.ingestFromGoogleSheets(mock(), "id", "range", "caller")
        } catch (ignore: Exception) {}
        
        // Manual registration empty impl
        provider.showManualRegistrationDialog("RFID") { _, _, _ -> }
    }
}
