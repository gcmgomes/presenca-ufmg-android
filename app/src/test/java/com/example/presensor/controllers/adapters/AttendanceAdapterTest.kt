package com.example.presensor.controllers.adapters

import androidx.recyclerview.widget.AsyncDifferConfig
import com.example.presensor.controllers.BaseControllerTest
import com.example.presensor.data.entities.AttendanceRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceAdapterTest : BaseControllerTest() {

    private lateinit var adapter: AttendanceAdapter

    @Before
    override fun setup() {
        super.setup()
        val config = AsyncDifferConfig.Builder(AttendanceAdapter.AttendanceDiffCallback())
            .setBackgroundThreadExecutor { it.run() }
            .setMainThreadExecutor { it.run() }
            .build()
        adapter = AttendanceAdapter(config)
    }

    @Test
    fun `submitList updates items correctly`() = runTest {
        val records = listOf(
            AttendanceRecord(1000L, "Student A", null, "a@test.com", "S1", 1L),
            AttendanceRecord(2000L, "Student B", null, "b@test.com", "S1", 1L)
        )

        adapter.submitList(records)
        
        // ListAdapter updates asynchronously
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()
        
        assertEquals(2, adapter.itemCount)
        assertEquals("Student A", adapter.currentList[0].studentName)
        assertEquals("Student B", adapter.currentList[1].studentName)
    }

    @Test
    fun `DiffUtil handles partial updates correctly`() = runTest {
        val initial = listOf(
            AttendanceRecord(1000L, "Student A", null, "a@test.com", "S1", 1L)
        )
        adapter.submitList(initial)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val updated = listOf(
            AttendanceRecord(1000L, "Student A", null, "a@test.com", "S1", 1L),
            AttendanceRecord(3000L, "Student C", null, "c@test.com", "S1", 1L)
        )
        adapter.submitList(updated)
        
        // ListAdapter diffing can be tricky in tests. 
        // We ensure all pending tasks on both background and main threads are processed.
        repeat(3) {
            advanceUntilIdle()
            ShadowLooper.idleMainLooper()
        }

        assertEquals(2, adapter.itemCount)
        assertEquals("Student C", adapter.currentList[1].studentName)
    }
}
