package com.example.presensor.controllers

import android.net.Uri
import com.example.presensor.R
import com.example.presensor.controllers.providers.SessionInteractionProvider
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.tools.ImportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ImportSessionControllerTest : BaseControllerTest() {

    private lateinit var controller: ImportSessionController
    private val interactionProvider: SessionInteractionProvider = mock()

    @Before
    override fun setup() {
        super.setup()
        controller = ImportSessionController(
            interactionProvider = interactionProvider,
            db = db,
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @Test
    fun `importFromLocal success flow`() = runTest {
        val uri: Uri = mock()
        val table = InternalDataTable(listOf("Col1"), listOf(listOf("Val1")))
        
        whenever(interactionProvider.ingestFromCsv(any(), any())).thenReturn(table)
        
        val courseId = db.insertCourse(Course(name = "Test", year = 2024, semester = 1))
        
        controller.importFromLocal(uri, courseId) {}
        advanceUntilIdle()

        val mappingCaptor = argumentCaptor<(Map<String, String>) -> Unit>()
        verify(interactionProvider).showMappingDialog(any(), any(), any(), any(), mappingCaptor.capture())

        // Simulate confirmation
        val sessions = listOf(Session(courseId = courseId, name = "S1", date = 1000L))
        whenever(interactionProvider.parseSessionsFromTable(any(), any(), any())).thenReturn(ImportResult(sessions, emptyList()))
        
        mappingCaptor.firstValue.invoke(emptyMap())
        advanceUntilIdle()

        val confirmCaptor = argumentCaptor<(List<Session>) -> Unit>()
        verify(interactionProvider).showSessionImportPreview(eq(sessions), confirmCaptor.capture(), any())

        // Execute import
        confirmCaptor.firstValue.invoke(sessions)
        advanceUntilIdle()

        verify(interactionProvider).toggleLoading(false)
        verify(interactionProvider).showToast(any<String>())
        
        val dbSessions = db.getSessionsByCourse(courseId)
        assert(dbSessions.size == 1)
    }

    @Test
    fun `importFromCloud handles null sheets service`() = runTest {
        controller.importFromCloud(null, "id", "tab", 1L) {}
        advanceUntilIdle()
        verify(interactionProvider).toggleLoading(false)
    }

    @Test
    fun `importFromLocal handles error`() = runTest {
        val uri: Uri = mock()
        whenever(interactionProvider.ingestFromCsv(any(), any())).thenThrow(RuntimeException("Error"))

        controller.importFromLocal(uri, 1L) {}
        advanceUntilIdle()

        verify(interactionProvider).showToast(any<String>())
    }
}
