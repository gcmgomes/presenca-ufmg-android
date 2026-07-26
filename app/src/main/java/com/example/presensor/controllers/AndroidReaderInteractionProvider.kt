package com.example.presensor.controllers

import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory.showWithSmartNfcReading
import com.example.presensor.data.SecureStoreManager
import com.google.android.material.textfield.TextInputEditText

/**
 * Android implementation of the ReaderInteractionProvider.
 * Wraps Toast and AlertDialog logic, interacting with the Activity and SecureStoreManager.
 */
class AndroidReaderInteractionProvider(
    private val activity: MainActivity,
    private val secureStoreManager: SecureStoreManager
) : ReaderInteractionProvider {

    override fun showToast(msgResId: Int, isShort: Boolean) {
        val text = activity.getString(msgResId)
        Toast.makeText(activity, text, if (isShort) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
            .show()
    }

    override fun showPasswordPromptDialog(
        readerName: String,
        onPasswordEntered: (String) -> Unit,
        onDismissed: () -> Unit
    ) {
        val dialogView =
            LayoutInflater.from(activity).inflate(R.layout.dialog_reader_password, null)
        val inputField = dialogView.findViewById<TextInputEditText>(R.id.editReaderPassword)
        
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.title_assign_tag, readerName))
            .setView(dialogView)
            .setPositiveButton("Connect", null)
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener { onDismissed() }
            .showWithSmartNfcReading()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typedPassword = inputField.text.toString().trim()
            if (typedPassword.isNotEmpty()) {
                onPasswordEntered(typedPassword)
                dialog.dismiss()
            } else {
                inputField.error = "Password cannot be blank"
            }
        }
    }

    override fun showEditReaderDialog(
        readerName: String,
        onConfigSaved: (newName: String, newPass: String) -> Unit
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_reader, null)
        val inputName = dialogView.findViewById<TextInputEditText>(R.id.editReaderName)
        val inputOldPass = dialogView.findViewById<TextInputEditText>(R.id.editOldPassword)
        val inputNewPass = dialogView.findViewById<TextInputEditText>(R.id.editNewPassword)
        val inputConfirmPass =
            dialogView.findViewById<TextInputEditText>(R.id.editConfirmNewPassword)
        inputName.setText(readerName)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.action_edit)
            .setView(dialogView)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .showWithSmartNfcReading()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = inputName.text.toString().trim()
            val oldPass = inputOldPass.text.toString()
            val newPass = inputNewPass.text.toString()
            val confirmPass = inputConfirmPass.text.toString()
            val storedPass = secureStoreManager.getAuthPasswordFor(readerName) ?: ""

            when {
                newName.isEmpty() -> inputName.error = activity.getString(R.string.error_empty_name)
                oldPass != storedPass -> inputOldPass.error =
                    activity.getString(R.string.error_incorrect_old_password)

                newPass.isEmpty() -> inputNewPass.error =
                    activity.getString(R.string.error_empty_password)

                newPass != confirmPass -> inputConfirmPass.error =
                    activity.getString(R.string.error_passwords_mismatch)

                else -> {
                    onConfigSaved(newName, newPass)
                    dialog.dismiss()
                }
            }
        }
    }

    override fun showDestructiveDeleteDialog(
        title: String,
        message: String,
        onConfirmed: () -> Unit
    ) {
        DialogFactory.showDestructiveDeleteDialog(
            context = activity,
            title = title,
            message = message,
            onConfirmed = onConfirmed
        )
    }
}
