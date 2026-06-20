package com.example.presensor

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.presensor.controllers.TagController
import com.example.presensor.data.entities.Session
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

object DialogFactory {

    // Unique identification tags for dialog lifecycle operations
    private const val TAG_DATE_PICKER_CREATE = "DATE_PICKER"
    private const val TAG_DATE_PICKER_EDIT = "EDIT_DATE_PICKER"

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



    fun showEditSessionDialog(
        context: Context,
        layoutInflater: LayoutInflater,
        fragmentManager: androidx.fragment.app.FragmentManager,
        session: Session,
        onSessionUpdated: (newName: String, newDateMillis: Long) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_session, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtSessionName)
        edtName.setText(session.name)
        edtName.selectAll()

        val edtDate = dialogView.findViewById<TextInputEditText>(R.id.edtSessionDate)
        var selectedTimestamp = session.date

        // Extracted patterns into layout string resources dynamically matching active configurations
        val pattern = context.getString(R.string.date_picker_display_format)
        val dateFormat = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        edtDate.setText(CourseUtilities.fromMillisToLocalDate(selectedTimestamp).format(dateFormat))

        edtDate.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(context.getString(R.string.select_session_date))
                .setSelection(selectedTimestamp)
                .build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                selectedTimestamp = selection
                val localDate = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
                edtDate.setText(localDate.format(dateFormat))
            }
            datePicker.show(fragmentManager, TAG_DATE_PICKER_EDIT)
        }

        edtName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(edtName.windowToken, 0)
                edtName.clearFocus()
                true
            } else {
                false
            }
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.title_edit_session)
            .setView(dialogView)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val nameText = edtName.text.toString().trim()
                if (nameText.isNotEmpty()) {
                    onSessionUpdated(nameText, selectedTimestamp)
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.error_empty_name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showWithSmartNfcReading()
    }

    fun showCreateSessionDialog(
        context: Context,
        layoutInflater: LayoutInflater,
        fragmentManager: androidx.fragment.app.FragmentManager,
        defaultSessionName: String,
        onSessionCreated: (name: String, dateMillis: Long) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_session, null)
        val edtName = dialogView.findViewById<EditText>(R.id.edtSessionName)
        edtName.setText(defaultSessionName)
        edtName.selectAll()

        val edtDate = dialogView.findViewById<TextInputEditText>(R.id.edtSessionDate)
        var selectedTimestamp = System.currentTimeMillis()

        // Extracted patterns into layout string resources dynamically matching active configurations
        val pattern = context.getString(R.string.date_picker_display_format)
        val dateFormat = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        edtDate.setText(CourseUtilities.fromMillisToLocalDate(selectedTimestamp).format(dateFormat))

        edtDate.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(context.getString(R.string.select_session_date))
                .setSelection(selectedTimestamp)
                .build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                selectedTimestamp = selection
                val localDate = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
                edtDate.setText(localDate.format(dateFormat))
            }
            datePicker.show(fragmentManager, TAG_DATE_PICKER_CREATE)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.title_new_session)
            .setView(dialogView)
            .setPositiveButton(R.string.action_create) { _, _ ->
                val nameText = edtName.text.toString().trim()
                if (nameText.isNotEmpty()) {
                    onSessionCreated(nameText, selectedTimestamp)
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.error_empty_name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showWithSmartNfcReading()
    }

    fun showManualRegistrationDialog(
        context: Context,
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
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val emailInput = EditText(context).apply {
            hint = context.getString(R.string.student_email)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        layout.addView(nameInput)
        layout.addView(emailInput)

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
            } else if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email)
                    .matches()
            ) {
                // Resolved using custom localized email structural validation key reference
                emailInput.error = context.getString(R.string.error_invalid_email)
            } else {
                onStudentSaved(name, email, dialog)
            }
        }
    }
}