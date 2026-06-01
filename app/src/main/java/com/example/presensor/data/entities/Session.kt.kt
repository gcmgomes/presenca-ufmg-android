package com.example.presensor.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [Index(value = ["courseId"])]
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    var name: String,
    var date: Long,
    var isLocked: Boolean = false
)

