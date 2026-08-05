package com.example.presensor.controllers.adapters

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.MainActivityForTest
import com.example.presensor.R
import com.example.presensor.data.entities.AttendanceRecord
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Session
import com.example.presensor.controllers.items.ActionItem
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.items.DeviceItem
import com.google.api.services.drive.model.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecyclerViewAdaptersTest {

    private lateinit var activity: MainActivityForTest
    private lateinit var rv: RecyclerView

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(MainActivityForTest::class.java).setup().get()
        rv = RecyclerView(activity)
        rv.layoutManager = LinearLayoutManager(activity)
        rv.layout(0, 0, 1080, 1920)
    }

    @Test
    fun `AttendanceAdapter binding and diff`() {
        val records = listOf(AttendanceRecord(1L, "Alice", "R1", "a@a.com", "S1", 1000L))
        val adapter = AttendanceAdapter()
        rv.adapter = adapter
        adapter.submitList(records)
        ShadowLooper.idleMainLooper()
        
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        
        val callback = AttendanceAdapter.AttendanceDiffCallback()
        assertTrue(callback.areItemsTheSame(records[0], records[0]))
        assertTrue(callback.areContentsTheSame(records[0], records[0]))
    }

    @Test
    fun `CloudFileAdapter binding`() {
        val files = listOf(File().setName("File1").setId("id1"))
        val onSelected: (File) -> Unit = mock()
        val adapter = CloudFileAdapter(files, { it.name }, onSelected)
        rv.adapter = adapter
        
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        
        holder.itemView.performClick()
        verify(onSelected).invoke(any())
    }

    @Test
    fun `ActionsPageAdapter binding`() {
        val actions = listOf(ActionItem("Action", 0) {})
        val adapter = ActionsPageAdapter(actions, listOf("Title"), 1, R.layout.item_dashboard_actions_page, listOf(R.id.btnRow1))
        rv.adapter = adapter
        
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
    }

    @Test
    fun `StudentSearchAdapter binding and diff`() {
        val students = listOf(Student("a@a.com", "Alice"))
        val onSelected: (Student) -> Unit = mock()
        val adapter = StudentSearchAdapter(onSelected)
        rv.adapter = adapter
        adapter.submitList(students)
        ShadowLooper.idleMainLooper()
        
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        holder.itemView.performClick()
        verify(onSelected).invoke(any())
        
        // Find the private diff callback via reflection to hit lines
        val callback = adapter.javaClass.getDeclaredClasses().find { it.simpleName == "StudentDiffCallback" }
            ?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance() as androidx.recyclerview.widget.DiffUtil.ItemCallback<Student>
        assertTrue(callback.areItemsTheSame(students[0], students[0]))
    }

    @Test
    fun `ImportPreviewAdapter binding and diff`() {
        val sessions = listOf(Session(10L, 1L, "S1", 0L))
        val adapter = ImportPreviewAdapter()
        rv.adapter = adapter
        adapter.submitList(sessions)
        ShadowLooper.idleMainLooper()
        
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        
        holder.itemView.performClick()
        assertFalse(adapter.getSelectedItems().contains(sessions[0]))

        val callback = adapter.javaClass.getDeclaredClasses().find { it.simpleName == "SessionDiffCallback" }
            ?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance() as androidx.recyclerview.widget.DiffUtil.ItemCallback<Session>
        assertTrue(callback.areItemsTheSame(sessions[0], sessions[0]))
    }

    @Test
    fun `StudentStatsAdapter binding`() {
        val students = listOf(Student("a@a.com", "Alice"))
        val sessions = listOf(Session(10L, 1L, "S1", 0L))
        val attendance = listOf(AttendanceRecord(1L, "Alice", null, "a@a.com", "S1", 10L))
        
        val adapter = StudentStatsAdapter(
            students, sessions, attendance, setOf(10L),
            getColorFromAttr = { 0 },
            makeSessionTimeFormatter = { mock() },
            fromMillisToLocalDate = { LocalDate.now() }
        )
        rv.adapter = adapter
        
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        
        adapter.updateData(emptyList())
        ShadowLooper.idleMainLooper()
        assertEquals(0, adapter.itemCount)
    }

    @Test
    fun `BacklogAdapter binding`() {
        val item = BacklogItem("RFID", Student("a@a.com", "Alice"), 1000L)
        val onLongClick: (BacklogItem) -> Unit = mock()
        val adapter = BacklogAdapter(onLongClick)
        rv.adapter = adapter
        adapter.submitList(listOf(item))
        ShadowLooper.idleMainLooper()
        
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        
        holder.itemView.performLongClick()
        verify(onLongClick).invoke(any())
        
        // cover diff callback
        adapter.submitList(emptyList())
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `ImportBacklogAdapter binding`() {
        val item = BacklogItem("RFID", Student("a@a.com", "Alice"), 1000L)
        val adapter = ImportBacklogAdapter()
        rv.adapter = adapter
        adapter.addItem(item)
        ShadowLooper.idleMainLooper()
        
        rv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        rv.layout(0, 0, 1000, 1000)
        
        val holder = rv.findViewHolderForAdapterPosition(0) as ImportBacklogAdapter.ViewHolder
        assertTrue(adapter.getSelectedItems().contains(item))
        
        holder.itemView.performClick()
        assertFalse(adapter.getSelectedItems().contains(item))
        
        adapter.removeItem(item)
        ShadowLooper.idleMainLooper()
        assertEquals(0, adapter.itemCount)
    }

    @Test
    fun `ImportStudentAdapter binding and diff`() {
        val students = listOf(Student("a@a.com", "Alice"))
        val adapter = ImportStudentAdapter()
        rv.adapter = adapter
        adapter.submitList(students)
        ShadowLooper.idleMainLooper()
        
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        
        assertTrue(adapter.getSelectedItems().contains(students[0]))
        holder.itemView.performClick()
        assertFalse(adapter.getSelectedItems().contains(students[0]))

        val callback = adapter.javaClass.getDeclaredClasses().find { it.simpleName == "StudentDiffCallback" }
            ?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance() as androidx.recyclerview.widget.DiffUtil.ItemCallback<Student>
        assertTrue(callback.areItemsTheSame(students[0], students[0]))
    }

    @Test
    fun `DeviceListAdapter binding and sections`() {
        val d1 = DeviceItem("C", "A1", -50, 100, 0L, isConnected = true, isConnecting = false, isNearby = true)
        val d2 = DeviceItem("K", "A2", -70, 80, 0L, isConnected = false, isConnecting = false, isNearby = true)
        val d3 = DeviceItem("U", "A3", -90, 0, 0L, isConnected = false, isConnecting = true, isNearby = true)
        
        val onSelected: (String, String) -> Unit = mock()
        val onLongClick: (String, String) -> Unit = mock()
        val adapter = DeviceListAdapter(onSelected, onLongClick)
        
        adapter.submitList(connected = listOf(d1), known = listOf(d2), unknown = listOf(d3))
        ShadowLooper.idleMainLooper()
        
        // 6 items: 3 headers + 3 devices
        assertEquals(6, adapter.itemCount)
        
        // test variety of bindings
        for (i in 0 until 6) {
            val holder = adapter.createViewHolder(rv, adapter.getItemViewType(i))
            adapter.bindViewHolder(holder, i)
        }
        
        // update callbacks coverage
        adapter.updateCallbacks(mock(), mock())
        
        // coverage for RSSI logic (dBm text)
        val d4 = DeviceItem("Offline", "A4", null, null, null, isConnected = false, isConnecting = false, isNearby = false)
        adapter.submitList(connected = emptyList(), known = emptyList(), unknown = listOf(d4))
        ShadowLooper.idleMainLooper()
        val offlineHolder = adapter.createViewHolder(rv, adapter.getItemViewType(1))
        adapter.bindViewHolder(offlineHolder, 1)
    }
}
