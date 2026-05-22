package com.example.presensor

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.example.presensor.data.entities.Session
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

object DialogFactory {

    fun AlertDialog.Builder.showWithSmartNfcReading(isDialogOpenSetter: (Boolean) -> Unit): AlertDialog {
        val dialog = this.create()
        dialog.setOnShowListener { isDialogOpenSetter(true) }
        dialog.setOnDismissListener { isDialogOpenSetter(false) }
        dialog.show()
        return dialog
    }

    fun showDestructiveDeleteDialog(
        context: Context,
        title: String,
        message: String,
        isDialogOpenSetter: (Boolean) -> Unit,
        onConfirmed: () -> Unit
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }
        val input = EditText(context).apply {
            hint = "Type 'DELETE' to confirm"
            setSingleLine(true)
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setPositiveButton("Confirm", null)
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading(isDialogOpenSetter)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text.toString().trim() == "DELETE") {
                onConfirmed()
                dialog.dismiss()
            } else {
                input.error = "Text must match exactly"
            }
        }
    }

    fun showCreateCourseDialog(
        context: Context,
        isDialogOpenSetter: (Boolean) -> Unit,
        onCourseCreated: (name: String, year: Int, semester: Int) -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val nameInput = EditText(context).apply {
            hint = "Course Name (e.g., Data Structures)"
            setSingleLine(true)
        }
        layout.addView(nameInput)

        val pickerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 0)
        }

        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentSemester = if (calendar.get(Calendar.MONTH) < 6) 1 else 2

        val yearPicker = NumberPicker(context).apply {
            minValue = currentYear - 5
            maxValue = currentYear + 1
            value = currentYear
            wrapSelectorWheel = false
        }
        val semesterPicker = NumberPicker(context).apply {
            minValue = 1
            maxValue = 2
            value = currentSemester
        }

        pickerLayout.addView(yearPicker)
        pickerLayout.addView(semesterPicker)
        layout.addView(pickerLayout)

        AlertDialog.Builder(context)
            .setTitle("New Course")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    onCourseCreated(name, yearPicker.value, semesterPicker.value)
                } else {
                    Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading(isDialogOpenSetter)
    }

    fun showCreateSessionDialog(
        context: Context,
        layoutInflater: LayoutInflater,
        fragmentManager: androidx.fragment.app.FragmentManager,
        defaultSessionName: String,
        isDialogOpenSetter: (Boolean) -> Unit,
        onSessionCreated: (name: String, dateMillis: Long) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_session, null)
        val edtName = dialogView.findViewById<EditText>(R.id.edtSessionName)
        edtName.setText(defaultSessionName)
        edtName.selectAll()

        val edtDate = dialogView.findViewById<TextInputEditText>(R.id.edtSessionDate)
        var selectedTimestamp = System.currentTimeMillis()
        val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())
        edtDate.setText(CourseUtilities.fromMillisToLocalDate(selectedTimestamp).format(dateFormat))

        edtDate.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select Session Date")
                .setSelection(selectedTimestamp)
                .build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                selectedTimestamp = selection
                val localDate = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
                edtDate.setText(localDate.format(dateFormat))
            }
            datePicker.show(fragmentManager, "DATE_PICKER")
        }

        AlertDialog.Builder(context)
            .setTitle("New Session")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                onSessionCreated(edtName.text.toString(), selectedTimestamp)
            }
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading(isDialogOpenSetter)
    }

    fun showManualRegistrationDialog(
        context: Context,
        rfid: String,
        isDialogOpenSetter: (Boolean) -> Unit,
        onStudentSaved: (name: String, email: String, dialog: AlertDialog) -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val nameInput = EditText(context).apply {
            hint = "Student Name"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val emailInput = EditText(context).apply {
            hint = "student@university.edu"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        layout.addView(nameInput)
        layout.addView(emailInput)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Manual Registration")
            .setMessage("Tag ID: $rfid")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading(isDialogOpenSetter)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()

            if (name.isEmpty()) {
                nameInput.error = "Name is required"
            } else if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailInput.error = "Enter a valid email"
            } else {
                onStudentSaved(name, email, dialog)
            }
        }
    }
}