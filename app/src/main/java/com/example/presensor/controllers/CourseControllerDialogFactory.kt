package com.example.presensor.controllers

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.CourseUtilities
import com.example.presensor.DialogFactory
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.adapters.ImportPreviewAdapter
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

class CourseControllerDialogFactory(
    private val activity: MainActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase,
    private val getSelectedCourse: () -> Course?,
    private val refreshCourseUI: () -> Unit,
    private val addSession: (Long, String, Long) -> Unit
) {
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

    fun showCreateSessionDialog() {
        val course = getSelectedCourse() ?: return
        lifecycleOwner.lifecycleScope.launch {
            val count = db.getSessionsByCourse(course.id).size + 1
            withContext(Dispatchers.Main) {
                val sessionPlaceholder = activity.getString(R.string.session_text) + " $count"
                DialogFactory.showCreateSessionDialog(
                    context = activity,
                    layoutInflater = layoutInflater,
                    fragmentManager = activity.supportFragmentManager,
                    defaultSessionName = sessionPlaceholder,
                    onSessionCreated = { sessionName, dateMillis ->
                        addSession(
                            course.id, sessionName, CourseUtilities.fromMillisToLocalDate(dateMillis)
                                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        )
                    }
                )
            }
        }
    }

    fun showMassDateChangeDialog() {
        val course = getSelectedCourse() ?: return
        val context = activity
        val dialogView = layoutInflater.inflate(R.layout.dialog_date_change_sessions, null)

        val edtThresholdDate = dialogView.findViewById<EditText>(R.id.edtThresholdDate)
        val edtNewStartDate = dialogView.findViewById<EditText>(R.id.edtNewStartDate)

        var thresholdTimestamp: Long? = null
        var newStartTimestamp: Long? = null

        val dateFormatter = CourseUtilities.makeSessionTimeFormatter(activity)

        val attachDatePicker = { editText: EditText, onDateSelected: (Long) -> Unit ->
            editText.setOnClickListener {
                val builder = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
                builder.setTitleText(editText.hint)
                val picker = builder.build()
                picker.addOnPositiveButtonClickListener { selection ->
                    onDateSelected(selection)
                    editText.setText(
                        CourseUtilities.fromMillisToLocalDate(selection).format(dateFormatter)
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
                        val targetedSessions = db.getSessionsByCourse(course.id)
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

                    refreshCourseUI()
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_sessions_updated_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                }
            }
        }
    }

    fun showDeleteSessionDialog(session: Session) {
        DialogFactory.showDestructiveDeleteDialog(
            context = activity,
            title = activity.getString(R.string.dialog_delete_session_title),
            message = activity.getString(R.string.dialog_delete_session_message, session.name),
            onConfirmed = {
                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.deleteSession(session)
                    }
                    refreshCourseUI()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_session_properties_modified),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    fun showImportPreview(sessions: List<Session>) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = layoutInflater.inflate(R.layout.layout_import_session_preview, null)
        bottomSheet.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvImportPreview)
        val txtImportCount = view.findViewById<TextView>(R.id.txtImportCount)
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmImport)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = ImportPreviewAdapter(sessions)

        txtImportCount.text = activity.getString(R.string.dialog_import_sessions_hint, sessions.size)
        btnConfirm.text = activity.getString(R.string.dialog_import_sessions_button_text)

        btnConfirm.setOnClickListener {
            activity.lifecycleScope.launch {
                db.insertSessions(sessions)
                bottomSheet.dismiss()
                refreshCourseUI()

                val toastMsg = activity.getString(R.string.toast_imported_sessions, sessions.size)
                Toast.makeText(activity, toastMsg, Toast.LENGTH_SHORT).show()
            }
        }
        bottomSheet.show()
    }

    fun showCreateCourseDialog(onCourseCreated: () -> Unit) {
        val context = activity
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_add_course, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtCourseName)
        val edtYear = dialogView.findViewById<EditText>(R.id.edtCourseYear)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        edtYear.setText(currentYear.toString())

        with(DialogFactory) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.title_new_course))
                .setView(dialogView)
                .setPositiveButton(context.getString(R.string.action_create), null)
                .setNegativeButton(context.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val courseName = edtName.text.toString().trim()
                val yearRaw = edtYear.text.toString().trim()
                val selectedSemester = spinnerSemester.selectedItem.toString().toIntOrNull() ?: 1

                if (courseName.isEmpty()) {
                    edtName.error = context.getString(R.string.error_empty_name)
                    return@setOnClickListener
                }

                val parsedYear = yearRaw.toIntOrNull()
                if (parsedYear == null || yearRaw.isEmpty()) {
                    edtYear.error = context.getString(R.string.label_year)
                    return@setOnClickListener
                }

                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val newCourse = Course(
                            name = courseName,
                            year = parsedYear,
                            semester = selectedSemester
                        )
                        db.insertCourse(newCourse)
                    }
                    onCourseCreated()
                    dialog.dismiss()
                }
            }
        }
    }

    fun showEditCourseDialog(course: Course, onUpdateSelectedCourse: (Course) -> Unit, onCourseEdited: () -> Unit) {
        val context = activity
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_add_course, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtCourseName)
        val edtYear = dialogView.findViewById<EditText>(R.id.edtCourseYear)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

        edtName.setText(course.name)
        edtYear.setText(course.year.toString())
        val semesterIndex = if (course.semester == 2) 1 else 0
        spinnerSemester.setSelection(semesterIndex)

        with(DialogFactory) {
            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.title_edit_course))
                .setView(dialogView)
                .setPositiveButton(context.getString(R.string.action_save), null)
                .setNegativeButton(context.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updatedName = edtName.text.toString().trim()
                val yearRaw = edtYear.text.toString().trim()
                val selectedSemester = spinnerSemester.selectedItem.toString().toIntOrNull() ?: 1

                if (updatedName.isEmpty()) {
                    edtName.error = context.getString(R.string.error_empty_name)
                    return@setOnClickListener
                }

                val updatedYear = yearRaw.toIntOrNull()
                if (updatedYear == null || yearRaw.isEmpty()) {
                    edtYear.error = context.getString(R.string.label_year)
                    return@setOnClickListener
                }

                lifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val updatedCourse = course.copy(
                            name = updatedName,
                            year = updatedYear,
                            semester = selectedSemester
                        )
                        db.updateCourse(updatedCourse)
                        onUpdateSelectedCourse(updatedCourse)
                    }

                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_course_updated),
                        Toast.LENGTH_SHORT
                    ).show()

                    onCourseEdited()
                    dialog.dismiss()
                }
            }
        }
    }
}
