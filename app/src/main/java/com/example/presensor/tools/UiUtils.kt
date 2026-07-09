package com.example.presensor.tools

import android.content.res.TypedArray
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.presensor.R
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Course

object UiUtils {

    fun updateLockIconUI(isLocked: Boolean, lockIcon: ImageView) {
        if (isLocked) {
            lockIcon.setImageResource(R.drawable.status_lock)
            lockIcon.alpha = 1.0f
        } else {
            lockIcon.setImageResource(R.drawable.status_unlock)
            lockIcon.alpha = 0.5f
        }
    }

    fun updateEditIconUI(isLocked: Boolean, editIcon: ImageView) {
        if (isLocked) {
            editIcon.setImageResource(R.drawable.ic_edit)
            editIcon.alpha = 0.4f
        } else {
            editIcon.setImageResource(R.drawable.ic_edit)
            editIcon.alpha = 1.0f
        }
    }

    fun getColorForAccent(courseName: String, colorArray: TypedArray): Int {
        val colors = IntArray(colorArray.length())
        for (i in 0 until colorArray.length()) {
            colors[i] = colorArray.getColor(i, 0)
        }
        colorArray.recycle()
        return colors[Math.abs(courseName.hashCode()) % colors.size]
    }

    fun fillCourseDetailedCardStatistics(
        activity: AppCompatActivity,
        card: View,
        course: Course,
        sessionIds: Set<Long>,
        studentEmails: Set<String>,
        courseAttendances: List<AttendanceRecord>
    ) {
        card.findViewById<TextView>(R.id.txtDetailCourseName).text = course.name

        val semesterOrdinal = if (course.semester == 1) {
            activity.getString(R.string.semester_ordinal_1st)
        } else {
            activity.getString(R.string.semester_ordinal_2nd)
        }
        card.findViewById<TextView>(R.id.txtDetailCourseSemester).text =
            activity.getString(R.string.semester_display_format, course.year, semesterOrdinal)

        card.findViewById<View>(R.id.viewCourseDetailAccent)
            .setBackgroundColor(
                getColorForAccent(
                    course.name,
                    activity.resources.obtainTypedArray(R.array.chalk_colors_list)
                )
            )

        val studentCount = studentEmails.size
        val sessionCount = sessionIds.size

        val avgAttendance = if (studentCount > 0 && sessionCount > 0) {
            val totalPossible = studentCount * sessionCount
            val actualLogs =
                courseAttendances.map { it.sessionId to it.studentEmail }.distinct().size
            (actualLogs.toFloat() / totalPossible.toFloat() * 100).toInt()
        } else {
            0
        }

        card.findViewById<TextView>(R.id.txtStatStudentCount).text = studentCount.toString()
        card.findViewById<TextView>(R.id.txtStatSessionCount).text = sessionCount.toString()
        card.findViewById<TextView>(R.id.txtStatAvgAttendance).text = "$avgAttendance%"
    }
}
