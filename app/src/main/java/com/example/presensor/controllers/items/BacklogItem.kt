package com.example.presensor.controllers.items

import com.example.presensor.data.entities.Student

/**
 * UI representation of a reader backlog item.
 * Used in both Device Management and Selective Sync views.
 */
data class BacklogItem(
    val tagId: String,
    val student: Student?,
    val timestamp: Long
)
