package com.example.presensor.controllers.dialogs

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.example.presensor.R
import com.example.presensor.controllers.TagController

object DialogFactory {

    private var isDialogOpen = false

    var tagController: TagController? = null



    /**
     * Public getter to let MainActivity or other controllers check if a
     * DialogFactory dialog is currently active before handling an NFC tag swipe.
     */
    fun isAnyDialogOpen(): Boolean = isDialogOpen



    // Private helper extension that manages the state tracker centrally
    fun AlertDialog.Builder.showWithSmartNfcReading(): AlertDialog {
        val dialog = this.create()
        dialog.setOnShowListener { isDialogOpen = true
            tagController?.pauseNfcScanning()}
        dialog.setOnDismissListener { isDialogOpen = false
        tagController?.resumeNfcScanning()}
        dialog.show()
        return dialog
    }

    fun showDestructiveDeleteDialog(
        context: Context,
        title: String,
        message: String,
        onConfirmed: () -> Unit
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }
        val input = EditText(context).apply {
            hint = context.getString(R.string.hint_delete_confirm)
            setSingleLine(true)
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setPositiveButton(R.string.delete_action_text, null)
            .setNegativeButton(R.string.action_cancel, null)
            .showWithSmartNfcReading()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text.toString().trim() == "DELETE") {
                onConfirmed()
                dialog.dismiss()
            } else {
                input.error = context.getString(R.string.error_delete_confirmation_mismatch)
            }
        }
    }



}