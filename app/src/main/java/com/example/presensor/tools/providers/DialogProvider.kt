package com.example.presensor.tools.providers

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student

interface DialogProvider {
    fun showMappingDialog(
        context: Context,
        fields: List<String>,
        columns: List<String>,
        sampleRow: List<String>?,
        onDismissed: () -> Unit,
        onConfirmed: (Map<String, String>) -> Unit
    )

    fun showSessionImportPreview(
        activity: AppCompatActivity,
        sessions: List<Session>,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    )

    fun showStudentImportPreview(
        activity: AppCompatActivity,
        students: List<Student>,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    )
}

class AndroidDialogProvider : DialogProvider {
    override fun showMappingDialog(
        context: Context,
        fields: List<String>,
        columns: List<String>,
        sampleRow: List<String>?,
        onDismissed: () -> Unit,
        onConfirmed: (Map<String, String>) -> Unit
    ) {
        DialogFactory.showMappingDialog(context, fields, columns, sampleRow, onDismissed, onConfirmed)
    }

    override fun showSessionImportPreview(
        activity: AppCompatActivity,
        sessions: List<Session>,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        // Implementation will be moved from Controller to here or MainActivity
    }

    override fun showStudentImportPreview(
        activity: AppCompatActivity,
        students: List<Student>,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        // Implementation will be moved from Controller to here or MainActivity
    }
}
