package com.example.presensor

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
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
import android.view.inputmethod.InputMethodManager

object DialogFactory {

    // Replacement private state tracking variable
    private var isDialogOpen = false

    /**
     * Public getter to let MainActivity or other controllers check if a
     * DialogFactory dialog is currently active before handling an NFC tag swipe.
     */
    fun isAnyDialogOpen(): Boolean = isDialogOpen

    // Private helper extension that manages the state tracker centrally
    private fun AlertDialog.Builder.showWithSmartNfcReading(): AlertDialog {
        val dialog = this.create()
        dialog.setOnShowListener { isDialogOpen = true }
        dialog.setOnDismissListener { isDialogOpen = false }
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
            .showWithSmartNfcReading() // Uses the internal tracker now

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
        layoutInflater: LayoutInflater, // Pass LayoutInflater from Activity/Fragment
        onCourseCreated: (name: String, year: Int, semester: Int) -> Unit
    ) {
        // 1. Inflate the custom XML layout file
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_course, null)
        val edtCourseName = dialogView.findViewById<TextInputEditText>(R.id.edtCourseName)
        val edtCourseYear = dialogView.findViewById<TextInputEditText>(R.id.edtCourseYear)
        val edtCourseSemester = dialogView.findViewById<TextInputEditText>(R.id.edtCourseSemester)

        // Calculate dynamic calendar defaults
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentSemester = if (calendar.get(Calendar.MONTH) < 6) 1 else 2

        // Mutable state trackers captured when options change
        var selectedYear = currentYear
        var selectedSemester = currentSemester

        // Set initial layout texts
        edtCourseYear.setText(selectedYear.toString())
        edtCourseSemester.setText(selectedSemester.toString())

        // 2. Setup Year selection modal sheet fallback hook
        val yearsArray = ((currentYear - 5)..(currentYear + 1)).map { it.toString() }.toTypedArray()
        edtCourseYear.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Select Year")
                .setItems(yearsArray) { _, which ->
                    selectedYear = yearsArray[which].toInt()
                    edtCourseYear.setText(selectedYear.toString())
                }
                .show()
        }

        // 3. Setup Semester selection modal sheet fallback hook
        val semestersArray = arrayOf("1", "2")
        edtCourseSemester.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Select Semester")
                .setItems(semestersArray) { _, which ->
                    selectedSemester = semestersArray[which].toInt()
                    edtCourseSemester.setText(selectedSemester.toString())
                }
                .show()
        }

        // 4. Force Checkmark enter key to hide soft input system tray keyboard panel layout instantly
        edtCourseName.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm =
                    v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else {
                false
            }
        }

        // 5. Construct and show the Material dialog
        AlertDialog.Builder(context)
            .setTitle("New Course")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val name = edtCourseName.text.toString().trim()
                if (name.isNotEmpty()) {
                    onCourseCreated(name, selectedYear, selectedSemester)
                } else {
                    Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .showWithSmartNfcReading() // Preserves your custom helper reading engine scope extension
    }

    fun showEditSessionDialog(
        context: Context,
        layoutInflater: LayoutInflater,
        fragmentManager: androidx.fragment.app.FragmentManager,
        session: Session,
        onSessionUpdated: (newName: String, newDateMillis: Long) -> Unit
    ) {
        // 1. Inflate the shared Material design input fields
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_session, null)

        // 2. Pre-populate and auto-select existing Session Name
        val edtName = dialogView.findViewById<EditText>(R.id.edtSessionName)
        edtName.setText(session.name)
        edtName.selectAll()

        // 3. Setup and pre-populate the Session Date field with existing timestamp
        val edtDate = dialogView.findViewById<TextInputEditText>(R.id.edtSessionDate)
        var selectedTimestamp = session.date
        val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())
        edtDate.setText(CourseUtilities.fromMillisToLocalDate(selectedTimestamp).format(dateFormat))

        // 4. Bind the identical MaterialDatePicker workflow
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
            datePicker.show(fragmentManager, "EDIT_DATE_PICKER")
        }

        // 5. Force keyboard "Checkmark" enter action to dismiss software tray properly
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

        // 6. Build and show the dialog utilizing your safe NFC reading suspension framework
        AlertDialog.Builder(context)
            .setTitle("Edit Session Details")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val nameText = edtName.text.toString().trim()
                if (nameText.isNotEmpty()) {
                    onSessionUpdated(nameText, selectedTimestamp)
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Name cannot be empty",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
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
            hint = "Student Name"
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
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
            .showWithSmartNfcReading()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val email = emailInput.text.toString().trim()

            if (name.isEmpty()) {
                nameInput.error = "Name is required"
            } else if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email)
                    .matches()
            ) {
                emailInput.error = "Enter a valid email"
            } else {
                onStudentSaved(name, email, dialog)
            }
        }
    }
}