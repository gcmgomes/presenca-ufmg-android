package com.example.presensor.controllers

import android.net.Uri
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Session
import com.example.presensor.tools.DataProcessor
import com.example.presensor.tools.providers.DataProcessorProvider
import com.example.presensor.tools.providers.DialogProvider
import com.example.presensor.tools.providers.LoadingOverlayProvider
import com.example.presensor.tools.providers.ToastProvider
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

    private val dataProcessorProvider: DataProcessorProvider = mock()
    private val dialogProvider: DialogProvider = mock()
    private val loadingOverlayProvider: LoadingOverlayProvider = mock()
    private val toastProvider: ToastProvider = mock()

    private lateinit var controller: ImportSessionController
    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    @Before
    override fun setup() {
        super.setup()

        controller = ImportSessionController(
            activity = activity,
            context = activity,
            scope = testScope,
            db = db,
            dataProcessorProvider = dataProcessorProvider,
            dialogProvider = dialogProvider,
            loadingOverlayProvider = loadingOverlayProvider,
            toastProvider = toastProvider,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @Test
    fun importFromLocal_success_showsMappingDialog() = runTest {
        val uri: Uri = mock()
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        whenever(dataProcessorProvider.ingestFromCsv(any(), eq(uri), any())).thenReturn(table)

        controller.importFromLocal(uri, 1L, {})
        advanceUntilIdle()

        verify(dialogProvider).showMappingDialog(
            eq(activity),
            any(),
            eq(table.headers),
            any(),
            any(),
            any()
        )
    }

    @Test
    fun importFromLocal_failure_showsToast() = runTest {
        val uri: Uri = mock()
        whenever(dataProcessorProvider.ingestFromCsv(any(), eq(uri), any())).thenThrow(
            RuntimeException("Error")
        )

        controller.importFromLocal(uri, 1L, {})
        advanceUntilIdle()

        verify(loadingOverlayProvider).toggleLoadingOverlay(false)
        verify(toastProvider).showToast(any(), any())
    }

    @Test
    fun importFromCloud_success_showsMapping() = runTest {
        val sheets: Sheets = mock()
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        whenever(
            dataProcessorProvider.ingestFromGoogleSheets(
                any(),
                eq(sheets),
                any(),
                any(),
                any()
            )
        ).thenReturn(table)

        controller.importFromCloud(sheets, "id", "tab", 1L, {})
        advanceUntilIdle()

        verify(dialogProvider).showMappingDialog(
            eq(activity),
            any(),
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
            dataProcessorProvider.ingestFromGoogleSheets(
                any(),
                eq(sheets),
                any(),
                any(),
                any()
            )
        ).thenThrow(RuntimeException("API Error"))

        controller.importFromCloud(sheets, "id", "tab", 1L, {})
        advanceUntilIdle()

        verify(loadingOverlayProvider).toggleLoadingOverlay(false)
        verify(toastProvider).showToast(any(), any())
    }

    @Test
    fun importFromCloud_nullService_hidesOverlay() = runTest {
        controller.importFromCloud(null, "id", "tab", 1L, {})
        advanceUntilIdle()
        verify(loadingOverlayProvider).toggleLoadingOverlay(false)
    }

    @Test
    fun handleMappingConfirmed_withErrors_showsToast() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val sessions = listOf(Session(id = 1, courseId = 1L, name = "S1", date = 1000L))
        val result = DataProcessor.ImportResult(sessions, listOf("Parse error"))

        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)
        whenever(
            dataProcessorProvider.parseSessionsFromTable(
                any(),
                any(),
                any(),
                any()
            )
        ).thenReturn(result)

        val onConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()

        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(dialogProvider).showMappingDialog(
            any(),
            any(),
            any(),
            any(),
            any(),
            onConfirmedCaptor.capture()
        )
        onConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(toastProvider).showToast(any(), any())
    }

    @Test
    fun handleMappingConfirmed_noItems_showsErrorToast() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val result = DataProcessor.ImportResult(emptyList<Session>(), listOf("Critical error"))

        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)
        whenever(
            dataProcessorProvider.parseSessionsFromTable(
                any(),
                any(),
                any(),
                any()
            )
        ).thenReturn(result)

        val onConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()

        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(dialogProvider).showMappingDialog(
            any(),
            any(),
            any(),
            any(),
            any(),
            onConfirmedCaptor.capture()
        )
        onConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(toastProvider).showToast(any(), any())
        verify(loadingOverlayProvider).toggleLoadingOverlay(false)
    }

    @Test
    fun mappingDialog_dismiss_hidesOverlay() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)

        val onDismissedCaptor = argumentCaptor<() -> Unit>()
        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(dialogProvider).showMappingDialog(
            any(),
            any(),
            any(),
            any(),
            onDismissedCaptor.capture(),
            any()
        )
        onDismissedCaptor.firstValue.invoke()

        verify(loadingOverlayProvider).toggleLoadingOverlay(false)
    }

    @Test
    fun preview_confirm_insertsToDb() = runTest {
        insertTestCourse(1L)

        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val sessions = listOf(Session(courseId = 1L, name = "S1", date = 1000L))
        val result = DataProcessor.ImportResult(sessions, emptyList<String>())

        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)
        whenever(
            dataProcessorProvider.parseSessionsFromTable(
                any(),
                any(),
                any(),
                any()
            )
        ).thenReturn(result)

        val onMappingConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()
        val onPreviewConfirmCaptor = argumentCaptor<(List<Session>) -> Unit>()

        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(dialogProvider).showMappingDialog(
            any(),
            any(),
            any(),
            any(),
            any(),
            onMappingConfirmedCaptor.capture()
        )
        onMappingConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(dialogProvider).showSessionImportPreview(
            any(),
            eq(sessions),
            onPreviewConfirmCaptor.capture(),
            any()
        )
        onPreviewConfirmCaptor.firstValue.invoke(sessions)
        advanceUntilIdle()

        val saved = db.getSessionsByCourse(1L)
        assert(saved.isNotEmpty())
        verify(toastProvider, atLeastOnce()).showToast(any(), any())
    }

    @Test
    fun preview_dismiss_hidesOverlay() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val result = DataProcessor.ImportResult(
            listOf(Session(courseId = 1L, name = "S1", date = 1000L)),
            emptyList<String>()
        )

        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)
        whenever(
            dataProcessorProvider.parseSessionsFromTable(
                any(),
                any(),
                any(),
                any()
            )
        ).thenReturn(result)

        val onMappingConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()
        val onPreviewDismissCaptor = argumentCaptor<() -> Unit>()

        controller.importFromLocal(mock(), 1L, {})
        advanceUntilIdle()

        verify(dialogProvider).showMappingDialog(
            any(),
            any(),
            any(),
            any(),
            any(),
            onMappingConfirmedCaptor.capture()
        )
        onMappingConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(dialogProvider).showSessionImportPreview(
            any(),
            any(),
            any(),
            onPreviewDismissCaptor.capture()
        )
        onPreviewDismissCaptor.firstValue.invoke()
        advanceUntilIdle()

        verify(loadingOverlayProvider, atLeastOnce()).toggleLoadingOverlay(false)
    }
}
