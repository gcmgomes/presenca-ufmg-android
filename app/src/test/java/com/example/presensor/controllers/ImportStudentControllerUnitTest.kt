package com.example.presensor.controllers

import android.net.Uri
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.DataProcessor
import com.example.presensor.tools.providers.DataProcessorProvider
import com.example.presensor.controllers.providers.StudentInteractionProvider
import com.google.api.services.sheets.v4.Sheets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImportStudentControllerUnitTest : BaseControllerTest() {

    private val dataProcessorProvider: DataProcessorProvider = mock()
    private val interactionProvider: StudentInteractionProvider = mock()

    private lateinit var controller: ImportStudentController
    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    @Before
    override fun setup() {
        super.setup()
        
        whenever(interactionProvider.getContext()).thenReturn(activity)
        whenever(interactionProvider.getContentResolver()).thenReturn(activity.contentResolver)

        controller = ImportStudentController(
            interactionProvider = interactionProvider,
            db = db,
            scope = testScope,
            dataProcessorProvider = dataProcessorProvider,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @Test
    fun importFromLocal_success_showsMappingDialog() = runTest {
        val uri: Uri = mock()
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        whenever(dataProcessorProvider.ingestFromCsv(any(), eq(uri), any())).thenReturn(table)

        controller.importFromLocal(uri)
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
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
        whenever(interactionProvider.getString(any())).thenReturn("Error")

        controller.importFromLocal(uri)
        advanceUntilIdle()

        verify(interactionProvider).toggleLoading(false)
        verify(interactionProvider).showToast(any<String>(), any())
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

        controller.importFromCloud(sheets, "id", "tab")
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
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
        whenever(interactionProvider.getString(any())).thenReturn("Error")

        controller.importFromCloud(sheets, "id", "tab")
        advanceUntilIdle()

        verify(interactionProvider).toggleLoading(false)
        verify(interactionProvider).showToast(any<String>(), any())
    }

    @Test
    fun importFromCloud_nullService_hidesOverlay() = runTest {
        controller.importFromCloud(null, "id", "tab")
        advanceUntilIdle()
        verify(interactionProvider).toggleLoading(false)
    }

    @Test
    fun handleMappingConfirmed_withErrors_showsToast() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val students = listOf(Student(name = "John", email = "john@example.com"))
        val result = DataProcessor.ImportResult(students, listOf("Parse error"))

        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)
        whenever(dataProcessorProvider.parseStudentsFromTable(any(), any(), any())).thenReturn(
            result
        )
        whenever(interactionProvider.getString(any(), any(), any())).thenReturn("Error")

        val onConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()

        controller.importFromLocal(mock())
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            any(),
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
        val result = DataProcessor.ImportResult(emptyList<Student>(), listOf("Critical error"))

        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)
        whenever(dataProcessorProvider.parseStudentsFromTable(any(), any(), any())).thenReturn(
            result
        )
        whenever(interactionProvider.getString(any(), any())).thenReturn("Error")

        val onConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()

        controller.importFromLocal(mock())
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            any(),
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
        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)

        val onDismissedCaptor = argumentCaptor<() -> Unit>()
        controller.importFromLocal(mock())
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            any(),
            any(),
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
        val students = listOf(Student(name = "John", email = "john@example.com"))
        val result = DataProcessor.ImportResult(students, emptyList<String>())

        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)
        whenever(dataProcessorProvider.parseStudentsFromTable(any(), any(), any())).thenReturn(
            result
        )

        val onMappingConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()
        val onPreviewConfirmCaptor = argumentCaptor<(List<Student>) -> Unit>()

        controller.importFromLocal(mock())
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            any(),
            any(),
            any(),
            any(),
            onMappingConfirmedCaptor.capture()
        )
        onMappingConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(interactionProvider).showStudentImportPreview(
            eq(students),
            onPreviewConfirmCaptor.capture(),
            any()
        )
        
        whenever(interactionProvider.getString(any(), any())).thenReturn("Success")
        
        onPreviewConfirmCaptor.firstValue.invoke(students)
        advanceUntilIdle()

        val saved = db.getAllStudents()
        assert(saved.isNotEmpty())
        verify(interactionProvider, atLeastOnce()).showToast(any<String>(), any())
    }

    @Test
    fun preview_dismiss_hidesOverlay() = runTest {
        val table = InternalDataTable(headers = listOf("H1"), rows = listOf(listOf("R1")))
        val result = DataProcessor.ImportResult(
            listOf(Student(name = "John", email = "john@example.com")),
            emptyList<String>()
        )

        whenever(dataProcessorProvider.ingestFromCsv(any(), any(), any())).thenReturn(table)
        whenever(dataProcessorProvider.parseStudentsFromTable(any(), any(), any())).thenReturn(
            result
        )

        val onMappingConfirmedCaptor = argumentCaptor<(Map<String, String>) -> Unit>()
        val onPreviewDismissCaptor = argumentCaptor<() -> Unit>()

        controller.importFromLocal(mock())
        advanceUntilIdle()

        verify(interactionProvider).showMappingDialog(
            any(),
            any(),
            any(),
            any(),
            onMappingConfirmedCaptor.capture()
        )
        onMappingConfirmedCaptor.firstValue.invoke(mapOf("name" to "H1"))
        advanceUntilIdle()

        verify(interactionProvider).showStudentImportPreview(
            any(),
            any(),
            onPreviewDismissCaptor.capture()
        )
        onPreviewDismissCaptor.firstValue.invoke()
        advanceUntilIdle()

        verify(interactionProvider, atLeastOnce()).toggleLoading(false)
    }
}
