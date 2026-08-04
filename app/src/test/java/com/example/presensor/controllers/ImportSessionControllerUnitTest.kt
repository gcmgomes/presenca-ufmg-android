package com.example.presensor.controllers

import android.net.Uri
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Session
import com.example.presensor.tools.ImportResult
import com.example.presensor.controllers.providers.SessionInteractionProvider
import com.google.api.services.sheets.v4.Sheets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImportSessionControllerUnitTest : BaseControllerTest() {

    private val interactionProvider: SessionInteractionProvider = mock()

    private lateinit var controller: ImportSessionController
    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    @Before
    override fun setup() {
        super.setup()
        
        whenever(interactionProvider.getContext()).thenReturn(activity)
        whenever(interactionProvider.getContentResolver()).thenReturn(activity.contentResolver)
        whenever(interactionProvider.getString(any())).thenReturn("Mock String")
        whenever(interactionProvider.getString(any(), any())).thenReturn("Mock String")
        whenever(interactionProvider.getString(any(), any(), any())).thenReturn("Mock String")

        controller = ImportSessionController(
            interactionProvider = interactionProvider,
            db = db,
            scope = testScope,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )

        runTest {
            insertTestCourse(1L)
        }
    }

    @Test
    fun importFromLocal_success_showsMappingDialog() = runTest {
        val uri: Uri = mock()
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        whenever(interactionProvider.ingestFromCsv(eq(uri), any())).thenReturn(table)

        controller.importFromLocal(uri, 1L, {})
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            argThat { containsAll(listOf("name", "date", "start_time", "end_time")) },
            eq(table.headers),
            any(),
            any(),
            any()
        )
    }

    @Test
    fun importFromLocal_failure_showsToast() = runTest {
        val uri: Uri = mock()
        whenever(interactionProvider.ingestFromCsv(eq(uri), any())).thenThrow(
            RuntimeException("Error")
        )
        whenever(interactionProvider.getString(any())).thenReturn("Error")

        controller.importFromLocal(uri, 1L, {})
        advanceUntilIdle()

        verify(interactionProvider).toggleLoading(false)
        verify(interactionProvider).showToast(any<String>(), any())
    }

    @Test
    fun importFromCloud_success_showsMapping() = runTest {
        val sheets: Sheets = mock()
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        whenever(
            interactionProvider.ingestFromGoogleSheets(
                eq(sheets),
                any(),
                any(),
                any()
            )
        ).thenReturn(table)

        controller.importFromCloud(sheets, "id", "tab", 1L, {})
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            argThat { containsAll(listOf("name", "date", "start_time", "end_time")) },
            eq(table.headers),
            any(),
            any(),
            any()
        )
    }

    @Test
    fun importFromCloud_failure_showsToast() = runTest {
        val sheets: Sheets = mock()
        whenever(
            interactionProvider.ingestFromGoogleSheets(
                eq(sheets),
                any(),
                any(),
                any()
            )
        ).thenThrow(RuntimeException("API Error"))
        whenever(interactionProvider.getString(any())).thenReturn("Error")

        controller.importFromCloud(sheets, "id", "tab", 1L, {})
        advanceUntilIdle()

        verify(interactionProvider).toggleLoading(false)
        verify(interactionProvider).showToast(any<String>(), any())
    }

    @Test
    fun importFromCloud_nullService_hidesOverlay() = runTest {
        controller.importFromCloud(null, "id", "tab", 1L, {})
        advanceUntilIdle()
        verify(interactionProvider).toggleLoading(false)
    }

    @Test
    fun handleMappingConfirmed_withErrors_showsToast() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val sessions = listOf(Session(id = 1, courseId = 1L, name = "S1", date = 1000L))
        val result = ImportResult(sessions, listOf("Parse error"))

        whenever(interactionProvider.ingestFromCsv(any(), any())).thenReturn(table)
        whenever(
            interactionProvider.parseSessionsFromTable(
                any(),
                any(),
                any()
            )
        ).thenReturn(result)
        whenever(interactionProvider.getString(any(), any(), any())).thenReturn("Error")

        val onConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()

        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            argThat { containsAll(listOf("name", "date", "start_time", "end_time")) },
            any(),
            any(),
            any(),
            onConfirmedCaptor.capture()
        )
        onConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(interactionProvider).showToast(any<String>(), eq(false))
    }

    @Test
    fun handleMappingConfirmed_noItems_showsErrorToast() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val result = ImportResult(emptyList<Session>(), listOf("Critical error"))

        whenever(interactionProvider.ingestFromCsv(any(), any())).thenReturn(table)
        whenever(
            interactionProvider.parseSessionsFromTable(
                any(),
                any(),
                any()
            )
        ).thenReturn(result)
        whenever(interactionProvider.getString(any())).thenReturn("Error")
        whenever(interactionProvider.getString(any(), any())).thenReturn("Error")

        val onConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()

        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            argThat { containsAll(listOf("name", "date", "start_time", "end_time")) },
            any(),
            any(),
            any(),
            onConfirmedCaptor.capture()
        )
        onConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(interactionProvider).showToast(any<String>(), eq(false))
        verify(interactionProvider).toggleLoading(false)
    }

    @Test
    fun mappingDialog_dismiss_hidesOverlay() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        whenever(interactionProvider.ingestFromCsv(any(), any())).thenReturn(table)

        val onDismissedCaptor = argumentCaptor<() -> Unit>()
        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            argThat { containsAll(listOf("name", "date", "start_time", "end_time")) },
            eq(table.headers),
            any(),
            onDismissedCaptor.capture(),
            any()
        )
        onDismissedCaptor.firstValue.invoke()

        verify(interactionProvider).toggleLoading(false)
    }

    @Test
    fun preview_confirm_insertsToDb() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val sessions = listOf(Session(courseId = 1L, name = "S1", date = 1000L))
        val result = ImportResult(sessions, emptyList<String>())

        whenever(interactionProvider.ingestFromCsv(any(), any())).thenReturn(table)
        whenever(
            interactionProvider.parseSessionsFromTable(
                any(),
                any(),
                any()
            )
        ).thenReturn(result)

        val onMappingConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()
        val onPreviewConfirmCaptor = argumentCaptor<(List<Session>) -> Unit>()

        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            argThat { containsAll(listOf("name", "date", "start_time", "end_time")) },
            any(),
            any(),
            any(),
            onMappingConfirmedCaptor.capture()
        )
        onMappingConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(interactionProvider).showSessionImportPreview(
            eq(sessions),
            onPreviewConfirmCaptor.capture(),
            any()
        )
        
        whenever(interactionProvider.getString(any(), any())).thenReturn("Success")
        
        onPreviewConfirmCaptor.firstValue.invoke(sessions)
        advanceUntilIdle()

        val saved = db.getSessionsByCourse(1L)
        assert(saved.isNotEmpty())
        verify(interactionProvider, atLeastOnce()).showToast(any<String>(), any())
    }

    @Test
    fun preview_dismiss_hidesOverlay() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val result = ImportResult(
            listOf(Session(courseId = 1L, name = "S1", date = 1000L)),
            emptyList<String>()
        )

        whenever(interactionProvider.ingestFromCsv(any(), any())).thenReturn(table)
        whenever(
            interactionProvider.parseSessionsFromTable(
                any(),
                any(),
                any()
            )
        ).thenReturn(result)

        val onMappingConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()
        val onPreviewDismissCaptor = argumentCaptor<() -> Unit>()

        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            argThat { containsAll(listOf("name", "date", "start_time", "end_time")) },
            any(),
            any(),
            any(),
            onMappingConfirmedCaptor.capture()
        )
        onMappingConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(interactionProvider).showSessionImportPreview(
            any(),
            any(),
            onPreviewDismissCaptor.capture()
        )
        onPreviewDismissCaptor.firstValue.invoke()
        advanceUntilIdle()

        verify(interactionProvider, atLeastOnce()).toggleLoading(false)
    }
}
