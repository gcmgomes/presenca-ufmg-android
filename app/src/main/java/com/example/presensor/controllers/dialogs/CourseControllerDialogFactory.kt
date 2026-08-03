package com.example.presensor.controllers.dialogs

import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.tools.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class CourseControllerDialogFactory(
    private val activity: MainActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase
) {
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

    private fun setupTimePicker(
        editText: EditText,
        initialMinutes: Long?,
        onTimeSelected: (Long) -> Unit
    ) {
        editText.setText(TimeUtils.formatMinutesToTime(initialMinutes))
        editText.setOnClickListener {
            val hour = (initialMinutes ?: (9 * 60)) / 60
            val minute = (initialMinutes ?: (9 * 60)) % 60
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .setHour(hour.toInt())
                .setMinute(minute.toInt())
                .setTitleText(editText.hint)
                .build()

            picker.addOnPositiveButtonClickListener {
                val selectedMinutes = (picker.hour * 60 + picker.minute).toLong()
                editText.setText(TimeUtils.formatMinutesToTime(selectedMinutes))
                onTimeSelected(selectedMinutes)
            }
            picker.show(activity.supportFragmentManager, "COURSE_TIME_PICKER")
        }
    }

    fun showCreateCourseDialog(onCourseCreated: () -> Unit): AlertDialog {
        val context = activity
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_add_course, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtCourseName)
        val edtYear = dialogView.findViewById<EditText>(R.id.edtCourseYear)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)
        val calendar = Calendar.getInstance()
        val curSemester = if (calendar.get(Calendar.MONTH) < 6) 0 else 1
        spinnerSemester.setSelection(curSemester)
        val edtStartTime = dialogView.findViewById<EditText>(R.id.edtCourseStartTime)
        val edtEndTime = dialogView.findViewById<EditText>(R.id.edtCourseEndTime)

        var startTime: Long? = null
        var endTime: Long? = null

        setupTimePicker(edtStartTime, null) { startTime = it }
        setupTimePicker(edtEndTime, null) { endTime = it }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        edtYear.setText(currentYear.toString())

        return with(DialogFactory) {
            val dialog = MaterialAlertDialogBuilder(context)
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
                            semester = selectedSemester,
                            startTime = startTime,
                            endTime = endTime
                        )
                        db.insertCourse(newCourse)
                    }
                    onCourseCreated()
                    dialog.dismiss()
                }
            }
            dialog
        }
    }

    fun showEditCourseDialog(
        course: Course,
        onUpdateSelectedCourse: (Course) -> Unit,
        onCourseEdited: () -> Unit
    ): AlertDialog {
        val context = activity
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_add_course, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtCourseName)
        val edtYear = dialogView.findViewById<EditText>(R.id.edtCourseYear)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)
        val edtStartTime = dialogView.findViewById<EditText>(R.id.edtCourseStartTime)
        val edtEndTime = dialogView.findViewById<EditText>(R.id.edtCourseEndTime)

        edtName.setText(course.name)
        edtYear.setText(course.year.toString())
        val semesterIndex = if (course.semester == 2) 1 else 0
        spinnerSemester.setSelection(semesterIndex)

        var startTime: Long? = course.startTime
        var endTime: Long? = course.endTime

        setupTimePicker(edtStartTime, startTime) { startTime = it }
        setupTimePicker(edtEndTime, endTime) { endTime = it }

        return with(DialogFactory) {
            val dialog = MaterialAlertDialogBuilder(context)
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
                            semester = selectedSemester,
                            startTime = startTime,
                            endTime = endTime
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
            dialog
        }
    }
}
