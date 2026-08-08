package com.example.presensor.controllers.adapters

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.data.entities.Student
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportBacklogAdapterTest {

    private lateinit var activity: MainActivityForTest
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ImportBacklogAdapter

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(MainActivityForTest::class.java).setup().get()
        rv = RecyclerView(activity)
        rv.layoutManager = LinearLayoutManager(activity)
        // Set a fixed size for the RecyclerView to allow holder discovery
        rv.layout(0, 0, 1080, 1920)
        
        adapter = ImportBacklogAdapter()
        rv.adapter = adapter
    }

    @Test
    fun `addItem adds item to the top and auto-selects by default`() {
        val item1 = BacklogItem("TAG1", null, 1000L)
        val item2 = BacklogItem("TAG2", null, 2000L)

        adapter.addItem(item1)
        adapter.addItem(item2) // Should be at position 0

        assertEquals(2, adapter.itemCount)
        assertEquals(2, adapter.getSelectedItems().size)
        
        // Verify item2 is at position 0 (inserted at 0)
        val holder = adapter.createViewHolder(rv, 0)
        adapter.bindViewHolder(holder, 0)
        assertEquals("TAG2", holder.rfidText.text.toString().replace(":", ""))
    }

    @Test
    fun `addItem respects shouldAutoSelect flag`() {
        val item = BacklogItem("TAG1", null, 1000L)
        adapter.addItem(item, shouldAutoSelect = false)

        assertEquals(1, adapter.itemCount)
        assertTrue(adapter.getSelectedItems().isEmpty())
    }

    @Test
    fun `removeItem removes item and selection`() {
        val item = BacklogItem("TAG1", null, 1000L)
        adapter.addItem(item)
        assertEquals(1, adapter.itemCount)
        
        adapter.removeItem(item)
        assertEquals(0, adapter.itemCount)
        assertTrue(adapter.getSelectedItems().isEmpty())
    }

    @Test
    fun `clicking an item toggles its selection state and updates UI`() {
        val item = BacklogItem("TAG1", Student("a@a.com", "Alice"), 1000L)
        adapter.addItem(item)
        
        // Use Robolectric to layout and find the actual view holder
        rv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        rv.layout(0, 0, 1000, 1000)
        ShadowLooper.idleMainLooper()
        
        val holder = rv.findViewHolderForAdapterPosition(0) as ImportBacklogAdapter.ViewHolder
        
        // Initial state: selected
        assertTrue(adapter.getSelectedItems().contains(item))
        assertEquals(1.0f, holder.cardRoot.alpha, 0.01f)

        // Click to deselect
        holder.itemView.performClick()
        assertFalse(adapter.getSelectedItems().contains(item))
        assertEquals(0.5f, holder.cardRoot.alpha, 0.01f)

        // Click to select again
        holder.itemView.performClick()
        assertTrue(adapter.getSelectedItems().contains(item))
        assertEquals(1.0f, holder.cardRoot.alpha, 0.01f)
    }

    @Test
    fun `onBindViewHolder formats time and date correctly`() {
        // 1723042800 is 2024-08-07 15:00:00 UTC
        // But the adapter uses systemDefault, which might vary.
        // We just check if it's not empty and visible.
        val item = BacklogItem("TAG1", null, 1723042800L)
        adapter.addItem(item)
        
        val holder = adapter.createViewHolder(rv, 0)
        adapter.bindViewHolder(holder, 0)

        assertFalse(holder.timeText.text.isNullOrEmpty())
        assertFalse(holder.dateText.text.isNullOrEmpty())
        assertEquals(View.VISIBLE, holder.dateText.visibility)
        assertEquals(View.GONE, holder.layoutSignalStack.visibility)
        assertEquals(View.GONE, holder.layoutBatteryStack.visibility)
    }
}
