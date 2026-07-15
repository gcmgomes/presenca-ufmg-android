package com.example.presensor.tools.providers

import androidx.appcompat.app.AppCompatActivity
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class DialogProviderTest {

    private val previewProvider: PreviewProvider = mock()
    private val dialogProvider = AndroidDialogProvider(previewProvider)
    private val activity: AppCompatActivity = mock()

    @Test
    fun `showSessionImportPreview delegates to PreviewProvider`() {
        val sessions = listOf<Session>()
        val onConfirm: () -> Unit = {}
        val onDismiss: () -> Unit = {}

        dialogProvider.showSessionImportPreview(activity, sessions, onConfirm, onDismiss)

        verify(previewProvider).showSessionImportPreview(activity, sessions, onConfirm, onDismiss)
    }

    @Test
    fun `showStudentImportPreview delegates to PreviewProvider`() {
        val students = listOf<Student>()
        val onConfirm: () -> Unit = {}
        val onDismiss: () -> Unit = {}

        dialogProvider.showStudentImportPreview(activity, students, onConfirm, onDismiss)

        verify(previewProvider).showStudentImportPreview(activity, students, onConfirm, onDismiss)
    }
}
