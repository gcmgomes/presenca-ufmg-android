package com.example.presensor.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Calendar


@Entity(tableName = "Course")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val year: Int = Calendar.getInstance().get(Calendar.YEAR),
    val semester: Int = if (Calendar.getInstance().get(Calendar.MONTH) < 6) 1 else 2,
    val startTime: Long? = null, // Minutes from midnight
    val endTime: Long? = null    // Minutes from midnight
)
