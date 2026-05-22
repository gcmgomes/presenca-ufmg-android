package com.example.presensor.data.entities

import androidx.room.Entity

@Entity(primaryKeys = ["email"]) // Email is a better primary key if RFID is initially missing
data class Student(
    val email: String,
    val name: String,
    val rfid: String? = null // Nullable until bound
)
