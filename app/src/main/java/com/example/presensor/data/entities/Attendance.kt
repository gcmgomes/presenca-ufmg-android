package com.example.presensor.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [Index(value = ["sessionId"]), Index(value = ["rfid"])],
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Attendance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rfid: String?,
    val studentEmail: String,
    val sessionId: Long,
    val timestamp: Long
)
