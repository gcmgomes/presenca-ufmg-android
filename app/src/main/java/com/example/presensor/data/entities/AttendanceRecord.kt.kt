package com.example.presensor.data.entities

data class AttendanceRecord(
    val timestamp: Long,
    val studentName: String,
    val studentRfid: String?,
    val studentEmail: String,
    val sessionName: String,
    val sessionId: Long
)
