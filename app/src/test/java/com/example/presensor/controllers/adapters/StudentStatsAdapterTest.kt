package com.example.presensor.controllers.adapters

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import android.widget.TextView
import com.example.presensor.R
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class StudentStatsAdapterTest {

    private lateinit var context: Context
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_Presensor)
    }

    @Test
    fun getItemCount_returnsStudentCount() {
        val adapter = createAdapter(students = listOf(Student(email = "e1", name = "N1")))
        assertEquals(1, adapter.itemCount)
    }

    @Test
    fun onBindViewHolder_bindsBasicInfoAndPercentage() {
        val student = Student(email = "test@test.com", name = "Alice")
        val session1 = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        val session2 = Session(id = 2, courseId = 1, name = "S2", date = 2000L)
        val attendance = listOf(
            AttendanceRecord(
                timestamp = 1000L,
                studentName = "Alice",
                studentEmail = "test@test.com",
                studentRfid = null,
                sessionName = "S1",
                sessionId = 1
            )
        )
        
        val adapter = createAdapter(
            students = listOf(student),
            sessions = listOf(session1, session2),
            attendance = attendance,
            sessionIds = setOf(1L, 2L)
        )

        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(viewHolder, 0)

        assertEquals("Alice", viewHolder.name.text.toString())
        assertEquals("test@test.com", viewHolder.email.text.toString())
        assertEquals("50%", viewHolder.percentage.text.toString())
    }

    @Test
    fun itemClick_togglesExpansion() {
        val student = Student(email = "test@test.com", name = "Alice")
        val adapter = createAdapter(students = listOf(student))
        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(viewHolder, 0)

        assertEquals(View.GONE, viewHolder.container.visibility)

        viewHolder.root.performClick()
        assertEquals(View.VISIBLE, viewHolder.container.visibility)
        
        viewHolder.root.performClick()
        assertEquals(View.GONE, viewHolder.container.visibility)
    }

    @Test
    fun updateData_refreshesCount() {
        val adapter = createAdapter(students = emptyList())
        assertEquals(0, adapter.itemCount)

        adapter.updateData(listOf(Student(email = "e1", name = "N1")))
        assertEquals(1, adapter.itemCount)
    }

    @Test
    fun onBindViewHolder_emptySessions_showsPlaceholder() {
        val student = Student(email = "test@test.com", name = "Alice")
        val adapter = createAdapter(
            students = listOf(student),
            sessions = emptyList(),
            sessionIds = emptySet()
        )

        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(viewHolder, 0)

        assertEquals("123%", viewHolder.percentage.text.toString())
    }

    @Test
    fun populateExpandedSessions_inflatesAndColorsCorrectly() {
        val student = Student(email = "test@test.com", name = "Alice")
        val session1 = Session(id = 1, courseId = 1, name = "S1", date = 1000L)
        val session2 = Session(id = 2, courseId = 1, name = "S2", date = 2000L)
        val attendance = listOf(
            AttendanceRecord(
                timestamp = 1000L,
                studentName = "Alice",
                studentEmail = "test@test.com",
                studentRfid = null,
                sessionName = "S1",
                sessionId = 1
            )
        )
        
        val adapter = createAdapter(
            students = listOf(student),
            sessions = listOf(session1, session2),
            attendance = attendance,
            sessionIds = setOf(1L, 2L),
            colorCallback = { attr ->
                if (attr == R.attr.studentAttendedClassColor) 0xFF00FF00.toInt()
                else 0xFFFF0000.toInt()
            }
        )

        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(viewHolder, 0)

        viewHolder.root.performClick()
        
        assertEquals(2, viewHolder.container.childCount)
        
        val row1 = viewHolder.container.getChildAt(0)
        val row2 = viewHolder.container.getChildAt(1)
        
        val name1 = row1.findViewById<TextView>(R.id.txtMiniSessionName)
        val name2 = row2.findViewById<TextView>(R.id.txtMiniSessionName)
        
        assertEquals("S1", name1.text.toString())
        assertEquals("S2", name2.text.toString())
        
        assertEquals(0xFF00FF00.toInt(), name1.currentTextColor)
        assertEquals(0xFFFF0000.toInt(), name2.currentTextColor)
    }

    @Test
    fun onBindViewHolder_persistsExpansionStateAfterUpdate() {
        val student = Student(email = "test@test.com", name = "Alice")
        val adapter = createAdapter(students = listOf(student))
        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)
        
        adapter.onBindViewHolder(viewHolder, 0)
        viewHolder.root.performClick()
        assertEquals(View.VISIBLE, viewHolder.container.visibility)
        
        adapter.updateData(listOf(student))
        adapter.onBindViewHolder(viewHolder, 0)
        
        assertEquals(View.VISIBLE, viewHolder.container.visibility)
    }

    private fun createAdapter(
        students: List<Student> = emptyList(),
        sessions: List<Session> = emptyList(),
        attendance: List<AttendanceRecord> = emptyList(),
        sessionIds: Set<Long> = emptySet(),
        colorCallback: (Int) -> Int = { 0 }
    ): StudentStatsAdapter {
        return StudentStatsAdapter(
            activeStudents = students,
            allSessions = sessions,
            allAttendance = attendance,
            sessionIds = sessionIds,
            getColorFromAttr = colorCallback,
            makeSessionTimeFormatter = { dateFormatter },
            fromMillisToLocalDate = { LocalDate.of(2024, 1, 1) }
        )
    }
}
