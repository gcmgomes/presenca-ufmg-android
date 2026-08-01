package com.example.presensor.controllers.dialogs

import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
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

    fun showCreateCourseDialog(onCourseCreated: () -> Unit): AlertDialog {
        val context = activity
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_add_course, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtCourseName)
        val edtYear = dialogView.findViewById<EditText>(R.id.edtCourseYear)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        edtYear.setText(currentYear.toString())

        return with(DialogFactory) {
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
            dialog
        }
    }

    fun showEditCourseDialog(course: Course, onUpdateSelectedCourse: (Course) -> Unit, onCourseEdited: () -> Unit): AlertDialog {
        val context = activity
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_add_course, null)

        val edtName = dialogView.findViewById<EditText>(R.id.edtCourseName)
        val edtYear = dialogView.findViewById<EditText>(R.id.edtCourseYear)
        val spinnerSemester = dialogView.findViewById<Spinner>(R.id.spinnerSemester)

        edtName.setText(course.name)
        edtYear.setText(course.year.toString())
        val semesterIndex = if (course.semester == 2) 1 else 0
        spinnerSemester.setSelection(semesterIndex)

        return with(DialogFactory) {
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
            dialog
        }
    }
}
