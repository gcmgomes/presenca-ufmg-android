package com.example.presensor.controllers.dialogs

import android.content.Context
import android.text.InputType
import android.util.Patterns
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.R

class SessionControllerDialogFactory(
    private val context: Context
) {

    fun showManualRegistrationDialog(
        rfid: String,
        onStudentSaved: (name: String, email: String, dialog: AlertDialog) -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val nameInput = EditText(context).apply {
            hint = context.getString(R.string.student_name)
            inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val emailInput = EditText(context).apply {
            hint = context.getString(R.string.student_email)
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        layout.addView(nameInput)
        layout.addView(emailInput)

        with(DialogFactory) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.title_manual_attendance)
                .setMessage(context.getString(R.string.label_tag_id, rfid))
                .setView(layout)
                .setPositiveButton(R.string.action_save, null)
                .setNegativeButton(R.string.action_cancel, null)
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val email = emailInput.text.toString().trim()

                if (name.isEmpty()) {
                    nameInput.error = context.getString(R.string.error_empty_name)
                } else if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email)
                        .matches()
                ) {
                    emailInput.error = context.getString(R.string.error_invalid_email)
                } else {
                    onStudentSaved(name, email, dialog)
                }
            }
        }
    }
}