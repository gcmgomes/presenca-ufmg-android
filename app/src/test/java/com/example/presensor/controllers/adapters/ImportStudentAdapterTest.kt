package com.example.presensor.controllers.adapters

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.example.presensor.R
import com.example.presensor.data.entities.Student
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ImportStudentAdapterTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_Presensor)
    }

    @Test
    fun getItemCount_returnsCorrectSize() {
        val students = listOf(
            Student(email = "s1@test.com", name = "Student 1"),
            Student(email = "s2@test.com", name = "Student 2")
        )
        val adapter = ImportStudentAdapter()
        adapter.submitList(students)
        ShadowLooper.idleMainLooper()
        assertEquals(2, adapter.itemCount)
    }

    @Test
    fun onBindViewHolder_bindsDataCorrectly() {
        val students = listOf(
            Student(email = "test@example.com", name = "Test Student")
        )
        val adapter = ImportStudentAdapter()
        adapter.submitList(students)
        ShadowLooper.idleMainLooper()
        
        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)

        adapter.onBindViewHolder(viewHolder, 0)

        assertEquals("Test Student", viewHolder.nameText.text.toString())
        assertEquals("test@example.com", viewHolder.subText.text.toString())
    }

    @Test
    fun onBindViewHolder_existingStudent_unselectedByDefault() {
        val students = listOf(
            Student(email = "existing@test.com", name = "Existing")
        )
        val adapter = ImportStudentAdapter()
        adapter.setExistingEmails(setOf("existing@test.com"))
        adapter.submitList(students)
        ShadowLooper.idleMainLooper()

        assertEquals(0, adapter.getSelectedItems().size)
    }

    @Test
    fun onBindViewHolder_newStudent_selectedByDefault() {
        val students = listOf(
            Student(email = "new@test.com", name = "New")
        )
        val adapter = ImportStudentAdapter()
        adapter.submitList(students)
        ShadowLooper.idleMainLooper()

        assertEquals(1, adapter.getSelectedItems().size)
    }

    @Test
    fun onBindViewHolder_existingStudent_usesOrangeAccent() {
        val students = listOf(
            Student(email = "existing@test.com", name = "Existing")
        )
        val adapter = ImportStudentAdapter()
        adapter.setExistingEmails(setOf("existing@test.com"))
        adapter.submitList(students)
        ShadowLooper.idleMainLooper()

        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(viewHolder, 0)

        // chalk_orange is #FFCC80
        val expectedColor = context.getColor(R.color.chalk_orange)
        val actualColor = (viewHolder.selectionAccent.background as android.graphics.drawable.ColorDrawable).color
        assertEquals(expectedColor, actualColor)
        // Alpha should be 0.5f because it's unselected by default
        assertEquals(0.5f, viewHolder.selectionAccent.alpha, 0.01f)
    }
}
