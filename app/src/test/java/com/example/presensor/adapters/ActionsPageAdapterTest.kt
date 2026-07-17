package com.example.presensor.adapters

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.example.presensor.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.google.android.material.button.MaterialButton

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ActionsPageAdapterTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_Presensor)
    }

    @Test
    fun getItemCount_returnsPageTitleSize() {
        val titles = listOf("P1", "P2")
        val adapter = ActionsPageAdapter(emptyList(), titles, 2, R.layout.item_course_utils_page, listOf(R.id.btnRow1, R.id.btnRow2))
        assertEquals(2, adapter.itemCount)
    }

    @Test
    fun onBindViewHolder_bindsTitleAndActions() {
        val onClick1: () -> Unit = mock()
        val actions = listOf(
            ActionItem("Action 1", android.R.drawable.ic_menu_add, onClick1)
        )
        val titles = listOf("Management")
        val adapter = ActionsPageAdapter(
            actions, 
            titles, 
            2, 
            R.layout.item_course_utils_page, 
            listOf(R.id.btnRow1, R.id.btnRow2)
        )
        
        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)

        adapter.onBindViewHolder(viewHolder, 0)

        assertEquals("Management", viewHolder.txtPageHeader.text.toString())
        
        val btn1 = viewHolder.itemView.findViewById<MaterialButton>(R.id.btnRow1)
        val btn2 = viewHolder.itemView.findViewById<MaterialButton>(R.id.btnRow2)

        assertEquals("Action 1", btn1.text.toString())
        assertEquals(View.VISIBLE, btn1.visibility)
        assertEquals(View.GONE, btn2.visibility)

        btn1.performClick()
        verify(onClick1).invoke()
    }

    @Test
    fun onBindViewHolder_nullTitle_bindsEmptyString() {
        val adapter = ActionsPageAdapter(
            emptyList(),
            listOf("P1"),
            2,
            R.layout.item_course_utils_page,
            listOf(R.id.btnRow1, R.id.btnRow2)
        )
        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)

        adapter.onBindViewHolder(viewHolder, 5) 
        assertEquals("", viewHolder.txtPageHeader.text.toString())
    }

    @Test
    fun bindRow_invalidIndex_returnsEarly() {
        val titles = listOf("P1")
        val adapter = ActionsPageAdapter(
            emptyList(),
            titles,
            2,
            R.layout.item_course_utils_page,
            listOf(R.id.btnRow1)
        )
        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)
        
        viewHolder.bindRow(99, ActionItem("X", 0, {}))
    }

    @Test
    fun onBindViewHolder_multiPage_partitionsActionsCorrectly() {
        val actions = listOf(
            ActionItem("A1", 0, {}),
            ActionItem("A2", 0, {}),
            ActionItem("A3", 0, {})
        )
        val titles = listOf("P1", "P2")
        val adapter = ActionsPageAdapter(
            actions,
            titles,
            2,
            R.layout.item_course_utils_page,
            listOf(R.id.btnRow1, R.id.btnRow2)
        )

        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)

        adapter.onBindViewHolder(viewHolder, 0)
        val btnP1_1 = viewHolder.itemView.findViewById<MaterialButton>(R.id.btnRow1)
        val btnP1_2 = viewHolder.itemView.findViewById<MaterialButton>(R.id.btnRow2)
        assertEquals("A1", btnP1_1.text.toString())
        assertEquals("A2", btnP1_2.text.toString())

        adapter.onBindViewHolder(viewHolder, 1)
        val btnP2_1 = viewHolder.itemView.findViewById<MaterialButton>(R.id.btnRow1)
        val btnP2_2 = viewHolder.itemView.findViewById<MaterialButton>(R.id.btnRow2)
        assertEquals("A3", btnP2_1.text.toString())
        assertEquals(View.GONE, btnP2_2.visibility)
    }
}
