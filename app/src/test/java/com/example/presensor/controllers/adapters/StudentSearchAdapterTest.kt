package com.example.presensor.controllers.adapters

import com.example.presensor.controllers.BaseControllerTest
import com.example.presensor.data.entities.Student
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
class StudentSearchAdapterTest : BaseControllerTest() {

    private lateinit var adapter: StudentSearchAdapter
    private val onStudentSelected: (Student) -> Unit = mock()

    @Before
    override fun setup() {
        super.setup()
        adapter = StudentSearchAdapter(onStudentSelected)
    }

    @Test
    fun `submitList updates items and handles click`() = runTest {
        val student = Student(email = "test@example.com", name = "Test Student")
        adapter.submitList(listOf(student))
        ShadowLooper.idleMainLooper()

        assertEquals(1, adapter.itemCount)
        
        // Use a real ViewGroup for inflation
        val parent = android.widget.FrameLayout(activity)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        holder.itemView.performClick()
        verify(onStudentSelected).invoke(student)
    }
}
