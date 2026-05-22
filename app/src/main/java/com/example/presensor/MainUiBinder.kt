package com.example.presensor

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

object MainUiBinder {

    fun addSectionHeader(container: LinearLayout, title: String) {
        val context = container.context
        val header = TextView(context).apply {
            text = title.uppercase()
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
            setPadding(10, 40, 10, 10)
        }
        container.addView(header)
    }

    fun addYearDivider(container: LinearLayout, year: String) {
        val context = container.context
        val dividerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val line = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 2, 1f)
            setBackgroundColor(Color.LTGRAY)
        }
        val yearLabel = TextView(context).apply {
            text = "  $year  "
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        dividerLayout.addView(line)
        dividerLayout.addView(yearLabel)

        val rightLine = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 2, 1f)
            setBackgroundColor(Color.LTGRAY)
        }
        dividerLayout.addView(rightLine)
        container.addView(dividerLayout)
    }
}