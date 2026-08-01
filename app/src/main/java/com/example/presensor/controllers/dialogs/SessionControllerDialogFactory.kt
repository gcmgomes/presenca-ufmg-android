package com.example.presensor.controllers.dialogs

import android.text.InputType
import android.util.Log
import android.util.Patterns
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Session
import com.example.presensor.tools.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionControllerDialogFactory(
    private val activity: MainActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase,
    private val refreshUI: () -> Unit
) {

    fun showEditSessionDialog(
        session: Session,
        onSessionUpdated: (newName: String, newDateMillis: Long) -> Unit
    ): AlertDialog {
        return DialogFactory.showSessionEntryDialog(
            context = activity,
            fragmentManager = activity.supportFragmentManager,
            titleResId = R.string.title_edit_session,
            positiveButtonResId = R.string.action_save,
            initialName = session.name,
            initialDate = session.date,
            onConfirmed = onSessionUpdated
        )
    }

    fun showCreateSessionDialog(
        courseId: Long,
        addSession: (Long, String, Long) -> Unit
    ) {
        lifecycleOwner.lifecycleScope.launch {
            val count = db.getSessionsByCourse(courseId).size + 1
            withContext(Dispatchers.Main) {
                val sessionPlaceholder = activity.getString(R.string.session_text) + " $count"
                DialogFactory.showSessionEntryDialog(
                    context = activity,
                    fragmentManager = activity.supportFragmentManager,
                    titleResId = R.string.title_new_session,
                    positiveButtonResId = R.string.action_create,
                    initialName = sessionPlaceholder,
                    onConfirmed = { name, date ->
                        addSession(courseId, name, date)
                    }
                )
            }
        }
    }

    fun showDeleteSessionDialog(session: Session): AlertDialog {
        return DialogFactory.showDestructiveDeleteDialog(
            context = activity,
            title = activity.getString(R.string.dialog_delete_session_title),
            message = activity.getString(R.string.dialog_delete_session_message, session.name),
            onConfirmed = {
                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.deleteSession(session)
                    }
                    refreshUI()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_session_properties_modified),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    fun showMassDateChangeDialog(courseId: Long): AlertDialog {
        val context = activity
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_date_change_sessions, null)

        val edtThresholdDate = dialogView.findViewById<EditText>(R.id.edtThresholdDate)
        val edtNewStartDate = dialogView.findViewById<EditText>(R.id.edtNewStartDate)

        var thresholdTimestamp: Long? = null
        var newStartTimestamp: Long? = null

        val dateFormatter = TimeUtils.makeSessionTimeFormatter(activity)

        val attachDatePicker = { editText: EditText, onDateSelected: (Long) -> Unit ->
            editText.setOnClickListener {
                val builder = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
                builder.setTitleText(editText.hint)
                val picker = builder.build()
                picker.addOnPositiveButtonClickListener { selection ->
                    onDateSelected(selection)
                    editText.setText(
                        TimeUtils.fromMillisToLocalDate(selection).format(dateFormatter)
                    )
                }
                picker.show(activity.supportFragmentManager, "MASS_DATE_PICKER")
            }
        }

        attachDatePicker(edtThresholdDate) { thresholdTimestamp = it }
        attachDatePicker(edtNewStartDate) { newStartTimestamp = it }

        with(DialogFactory) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.menu_course_postpone))
                .setView(dialogView)
                .setPositiveButton(context.getString(R.string.action_save), null)
                .setNegativeButton(context.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val currentThreshold = thresholdTimestamp
                val currentNewStart = newStartTimestamp

                if (currentThreshold == null) {
                    edtThresholdDate.error = context.getString(R.string.error_empty_date)
                    return@setOnClickListener
                }
                if (currentNewStart == null) {
                    edtNewStartDate.error = context.getString(R.string.error_empty_date)
                    return@setOnClickListener
                }

                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val targetedSessions = db.getSessionsByCourse(courseId)
                            .filter { it.date >= currentThreshold }
                            .sortedBy { it.date }

                        if (targetedSessions.isNotEmpty()) {
                            val originalBaseDate = targetedSessions.first().date
                            val deltaOffset = currentNewStart - originalBaseDate

                            targetedSessions.forEach { session ->
                                val updatedSession = session.copy(
                                    date = session.date + deltaOffset
                                )
                                db.updateSession(updatedSession)
                            }
                        }
                    }

                    refreshUI()
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_sessions_updated_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
            }
            return dialog
        }
    }

    fun showManualRegistrationDialog(
        rfid: String,
        onStudentSaved: (name: String, email: String, dialog: AlertDialog) -> Unit
    ): AlertDialog {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val nameInput = EditText(activity).apply {
            hint = activity.getString(R.string.student_name)
            inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val emailInput = EditText(activity).apply {
            hint = activity.getString(R.string.student_email)
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        layout.addView(nameInput)
        layout.addView(emailInput)

        with(DialogFactory) {
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.title_manual_attendance)
                .setMessage(activity.getString(R.string.label_tag_id, rfid))
                .setView(layout)
                .setPositiveButton(R.string.action_save, null)
                .setNegativeButton(R.string.action_cancel, null)
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                val email = emailInput.text.toString().trim()

                if (name.isEmpty()) {
                    nameInput.error = activity.getString(R.string.error_empty_name)
                } else if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email)
                        .matches()
                ) {
                    emailInput.error = activity.getString(R.string.error_invalid_email)
                } else {
                    onStudentSaved(name, email, dialog)
                }
            }
            return dialog
        }
    }
}
