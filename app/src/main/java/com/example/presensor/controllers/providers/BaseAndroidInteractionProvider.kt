package com.example.presensor.controllers.providers

import android.content.ContentResolver
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.data.InternalDataTable
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.tools.DataProcessor
import com.example.presensor.tools.ImportResult
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job

abstract class BaseAndroidInteractionProvider(
    protected val activity: MainActivity
) : InteractionProvider {

    protected var activeBottomSheet: BottomSheetDialog? = null
    protected var activeAlertDialog: AlertDialog? = null

    override fun showToast(message: String, isShort: Boolean) {
        activity.runOnUiThread {
            Toast.makeText(
                activity,
                message,
                if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun showToast(resId: Int, isShort: Boolean) {
        activity.runOnUiThread {
            Toast.makeText(activity, resId, if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun toggleLoading(show: Boolean) {
        activity.runOnUiThread {
            activity.toggleLoadingOverlay(show)
        }
    }

    override fun getString(resId: Int): String = activity.getString(resId)

    override fun getString(resId: Int, vararg formatArgs: Any): String =
        activity.getString(resId, *formatArgs)

    override fun getContext(): Context = activity

    override fun getContentResolver(): ContentResolver = activity.contentResolver

    override fun showMappingDialog(
        fields: List<String>,
        columns: List<String>,
        sampleRow: List<String>?,
        onDismissed: () -> Unit,
        onConfirmed: (Map<String, String>) -> Unit
    ) {
        activity.runOnUiThread {
            activeAlertDialog = DialogFactory.showMappingDialog(
                activity,
                fields,
                columns,
                sampleRow,
                onDismissed,
                onConfirmed
            )
        }
    }

    override fun dismissActiveDialog() {
        activity.runOnUiThread {
            activeBottomSheet?.dismiss()
            activeBottomSheet = null
            activeAlertDialog?.dismiss()
            activeAlertDialog = null
        }
    }

    override fun isAnyDialogOpen(): Boolean = DialogFactory.isAnyDialogOpen()

    override fun setLoadingJob(job: Job?) {
        activity.setCurrentOverlayJob(job)
    }

    override suspend fun ingestFromGoogleSheets(
        sheetsService: com.google.api.services.sheets.v4.Sheets,
        spreadsheetId: String,
        range: String,
        caller: String
    ): InternalDataTable {
        return DataProcessor.ingestFromGoogleSheets(activity, sheetsService, spreadsheetId, range, caller)
    }

    override suspend fun ingestFromCsv(
        uri: android.net.Uri,
        caller: String
    ): InternalDataTable {
        return DataProcessor.ingestFromCsv(activity.contentResolver, uri, caller)
    }

    override fun parseSessionsFromTable(
        table: InternalDataTable,
        courseId: Long,
        mapping: Map<String, String>?
    ): ImportResult<Session> {
        return DataProcessor.parseSessionsFromTable(activity, table, courseId, mapping)
    }

    override fun parseStudentsFromTable(
        table: InternalDataTable,
        mapping: Map<String, String>?
    ): ImportResult<Student> {
        return DataProcessor.parseStudentsFromTable(activity, table, mapping)
    }

    // Default implementation if not overridden
    override fun showManualRegistrationDialog(
        rfid: String,
        onStudentSaved: (name: String, email: String, dialog: Any) -> Unit
    ) {
        // To be overridden if needed
    }
}
