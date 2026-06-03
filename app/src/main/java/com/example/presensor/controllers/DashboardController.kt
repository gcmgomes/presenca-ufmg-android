package com.example.presensor.controllers

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.example.presensor.R
import com.example.presensor.MainUiBinder
import com.example.presensor.CourseUtilities
import com.example.presensor.DialogFactory
import com.example.presensor.MainActivity.AppState
import com.example.presensor.adapters.ImportStudentAdapter
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Student
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class DashboardController(
    private val activity: AppCompatActivity,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val onCourseSelected: (Course) -> Unit,
    private val onCourseLongClicked: (Course) -> Unit,
    private val onCourseCreateRequested: (() -> Unit) -> Unit,
    private val onCourseEditRequested: (Course) -> Unit // Add this new callback token
) {
    // Safely look up views directly from the Activity context to prevent null reference crashes
    private val container: LinearLayout = activity.findViewById(R.id.currentCoursesContainer)
    private val txtCurrentTerm: TextView = activity.findViewById(R.id.txtCurrentTerm)
    private val searchView: SearchView = activity.findViewById(R.id.courseSearchView)
    private val layoutInflater: LayoutInflater = LayoutInflater.from(activity)

    init {
        setupSearchView()
    }

    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                Log.d("", "Aha!")
                refreshDashboard(newText ?: "")
                return true
            }
        })
    }

    private fun showStudentImportPreview(students: List<Student>) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = layoutInflater.inflate(R.layout.layout_import_student_preview, null)
        bottomSheet.setContentView(view)

        val recyclerView = view.findViewById<RecyclerView>(R.id.rvImportStudentPreview)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirmStudentImport)

        // Maps to: "Found %1$d students. Please verify their information." / "Foram encontrados %1$d alunos..."
        view.findViewById<TextView>(R.id.txtImportStudentCount).text =
            activity.getString(R.string.dialog_import_students_hint, students.size)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = ImportStudentAdapter(students)

        btnConfirm.setOnClickListener {
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.dao().insertStudents(students) }
                bottomSheet.dismiss()

                // Maps to: "%1$d aulas importadas" or customizable toast parameters safely
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_imported_sessions, students.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        bottomSheet.show()
    }

    private fun parseStudentCsv(uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val students = CourseUtilities.parseStudentsFromCsv(activity.contentResolver, uri)
                withContext(Dispatchers.Main) {
                    if (students.isNotEmpty()) {
                        showStudentImportPreview(students)
                    } else {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.msg_no_students_found),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    fun setupQuickActionsAccordion() {
        val includedDashboard = activity.findViewById<ViewGroup>(R.id.layoutDashboardView) ?: return
        val expandableLayout =
            includedDashboard.findViewById<LinearLayout>(R.id.layoutActionsContent)
        val arrowIcon = includedDashboard.findViewById<ImageView>(R.id.imgExpandArrow)
        val headerClickArea =
            includedDashboard.findViewById<RelativeLayout>(R.id.layoutDashboardActionsHeader)

        headerClickArea.setOnClickListener {
            val animationDuration = 300L
            val transition = TransitionSet().apply {
                addTransition(ChangeBounds())
                addTransition(Fade())
                ordering = TransitionSet.ORDERING_TOGETHER
                duration = animationDuration
                interpolator = FastOutSlowInInterpolator()
            }

            TransitionManager.beginDelayedTransition(includedDashboard, transition)

            if (expandableLayout.isGone) {
                expandableLayout.visibility = View.VISIBLE
                arrowIcon.animate().rotation(180f).setDuration(animationDuration).start()
            } else {
                expandableLayout.visibility = View.GONE
                arrowIcon.animate().rotation(0f).setDuration(animationDuration).start()
            }
        }
    }

    private val importStudentLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri -> parseStudentCsv(uri) }
            }
        }


    private fun triggerStudentImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/comma-separated-values"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/csv", "text/comma-separated-values", "text/plain")
            )
        }
        importStudentLauncher.launch(intent)
    }

    fun setupOnClickListeners() {

        activity.findViewById<Button>(R.id.btnImportStudents)
            .setOnClickListener { triggerStudentImportPicker() }
        activity.findViewById<FloatingActionButton>(R.id.btnCreateCourse)
            .setOnClickListener {
                onCourseCreateRequested {
                    refreshDashboard()
                }
            }
    }

    fun refreshDashboard(filter: String = "") {
        val calendar = Calendar.getInstance()
        val curYear = calendar.get(Calendar.YEAR)
        val curSemester = if (calendar.get(Calendar.MONTH) < 6) 1 else 2

        // Maps cleanly using layout format ordinal definitions
        val semesterString = if (curSemester == 1) {
            activity.getString(R.string.semester_ordinal_1st)
        } else {
            activity.getString(R.string.semester_ordinal_2nd)
        }
        txtCurrentTerm.text =
            activity.getString(R.string.semester_display_format, curYear, semesterString)

        scope.launch {
            var allCourses = db.dao().getAllCourses()
            if (filter.isNotEmpty()) {
                allCourses = allCourses.filter { it.name.contains(filter, ignoreCase = true) }
            }
            container.removeAllViews()

            val thisSemester =
                allCourses.filter { it.year == curYear && it.semester == curSemester }
            val nextSemester =
                allCourses.filter { (it.year == curYear && it.semester > curSemester) || (it.year > curYear) }
            val previousSemesters =
                allCourses.filter { (it.year == curYear && it.semester < curSemester) || (it.year < curYear) }

            addCoursesToSection(
                activity.getString(R.string.current_semester_head_text),
                thisSemester
            )
            addCoursesToSection(
                activity.getString(R.string.upcoming_semester_head_text),
                nextSemester
            )

            if (previousSemesters.isNotEmpty()) {
                MainUiBinder.addSectionHeader(
                    container,
                    activity.getString(R.string.previous_semester_head_text)
                )
                var lastYear = -1
                previousSemesters.sortedWith(compareByDescending<Course> { it.year }.thenByDescending { it.semester })
                    .forEach { course ->
                        if (course.year != lastYear) {
                            MainUiBinder.addYearDivider(container, course.year.toString())
                        }
                        lastYear = course.year
                        addCourseCardToContainer(course)
                    }
            }
        }
    }

    private fun addCoursesToSection(title: String, courses: List<Course>) {
        if (courses.isNotEmpty()) {
            MainUiBinder.addSectionHeader(container, title)
            courses.forEach { addCourseCardToContainer(it) }
        }
    }

    private fun addCourseCardToContainer(course: Course) {
        val cardView = layoutInflater.inflate(R.layout.item_course_card, container, false)
        cardView.findViewById<View>(R.id.viewCourseAccent)
            .setBackgroundColor(getColorForAccent(course.name))
        cardView.findViewById<TextView>(R.id.txtCourseName).text = course.name

        val semesterString = if (course.semester == 1) {
            activity.getString(R.string.semester_ordinal_1st)
        } else {
            activity.getString(R.string.semester_ordinal_2nd)
        }
        cardView.findViewById<TextView>(R.id.txtCourseDetails).text =
            activity.getString(R.string.semester_display_format, course.year, semesterString)

        cardView.setOnClickListener { onCourseSelected(course) }
        cardView.setOnLongClickListener {
            onCourseLongClicked(course)
            true
        }

        // Inside your Course Adapter's ViewHolder binding block:
        val imgEditCourse = cardView.findViewById<ImageView>(R.id.imgEditCourseDashboard)

        imgEditCourse.setOnClickListener {
            // Trigger the custom long click / edit callback passing the selected course
            onCourseEditRequested(course)
        }
        container.addView(cardView)
    }

    private fun getColorForAccent(courseName: String): Int {
        val typedArray = activity.resources.obtainTypedArray(R.array.chalk_colors_list)
        val colors = IntArray(typedArray.length())
        for (i in 0 until typedArray.length()) {
            colors[i] = typedArray.getColor(i, 0)
        }
        typedArray.recycle()
        return colors[Math.abs(courseName.hashCode()) % colors.size]
    }
}