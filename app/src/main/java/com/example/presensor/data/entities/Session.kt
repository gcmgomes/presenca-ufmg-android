package com.example.presensor.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [Index(value = ["courseId"])],
    foreignKeys = [
        ForeignKey(
            entity = Course::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    var name: String,
    var date: Long,
    var isLocked: Boolean = false,
    var startTime: Long? = null, // Minutes from midnight
    var endTime: Long? = null    // Minutes from midnight
)

