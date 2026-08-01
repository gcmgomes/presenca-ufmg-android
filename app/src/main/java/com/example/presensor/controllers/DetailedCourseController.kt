package com.example.presensor.controllers

import com.example.presensor.controllers.providers.DetailedCourseInteractionProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.AttendanceRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailedCourseController(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val courseController: CourseController,
    private val interactionProvider: DetailedCourseInteractionProvider,
    private val getColorFromAttr: (Int) -> Int,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var isViewActive = false
    private var activeStudents: List<Student> = emptyList()
    private var allSessions: List<Session> = emptyList()
    private var allAttendance: List<AttendanceRecord> = emptyList()

    /**
     * Initializes the statistics view and triggers initial data load.
     */
    fun openDetailedCourseView() {
        val course = courseController.getSelectedCourse()
            ?: throw IllegalStateException("No course selected")

        isViewActive = true
        interactionProvider.openDetailedCourseView(
            onEditCourseRequested = {
                courseController.showEditCourseDialog(course) {
                    fetchDataAndRefresh()
                }
            },
            onSearchQueryChanged = { query ->
                refreshDetailedCourseUI(query)
            }
        )

        fetchDataAndRefresh()
    }

    /**
     * Performs a full data re-fetch from the database and updates the UI.
     */
    fun fetchDataAndRefresh(filter: String = "") {
        val course = courseController.getSelectedCourse() ?: return
        scope.launch(ioDispatcher) {
            val sessions = db.getSessionsByCourse(course.id)
            val attendances = db.getAllAttendanceForCourse(course.id)
            val students = db.getAllStudents()

            val activeEmails = attendances.map { it.studentEmail }.toSet()
            activeStudents = students.filter { it.email in activeEmails }
            allSessions = sessions
            allAttendance = attendances

            withContext(mainDispatcher) {
                if (!isViewActive) return@withContext
                
                interactionProvider.updateStudentStatsList(
                    activeStudents,
                    allSessions,
                    allAttendance,
                    getColorFromAttr = getColorFromAttr
                )

                refreshDetailedCourseUI(filter)
            }
        }
    }

    /**
     * Filters the student roster list view dynamically as the instructor types.
     * Uses currently loaded local data.
     */
    fun refreshDetailedCourseUI(filter: String = "") {
        if (!isViewActive) return
        val course = courseController.getSelectedCourse() ?: return

        interactionProvider.updateDetailedCourseHeader(
            course,
            allSessions.map { it.id }.toSet(),
            activeStudents.map { it.email }.toSet(),
            allAttendance
        )

        val filteredStudents = if (filter.isBlank()) {
            activeStudents
        } else {
            activeStudents.filter { it.name.contains(filter, ignoreCase = true) }
        }

        interactionProvider.updateStudentStatsList(
            filteredStudents,
            allSessions,
            allAttendance,
            getColorFromAttr = getColorFromAttr
        )
    }

    /**
     * Releases view references safely when navigating away to prevent memory leaks.
     */
    fun clear() {
        isViewActive = false
    }
}
