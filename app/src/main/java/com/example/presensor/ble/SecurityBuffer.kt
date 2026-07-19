package com.example.presensor.ble

import java.security.MessageDigest

object SecurityBuffer {
    fun generateBlePin(password: String): String {
        if (password.isEmpty()) return "123456" // Guard check matching ESP32 default

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))

        // Combine the first 4 bytes into an Int (matching ESP32 bit-shifting bit-for-bit)
        val numericHash = ((hashBytes[0].toInt() and 0xFF) shl 24) or
                ((hashBytes[1].toInt() and 0xFF) shl 16) or
                ((hashBytes[2].toInt() and 0xFF) shl 8) or
                (hashBytes[3].toInt() and 0xFF)

        // Mask to an unsigned value to handle Java's signed Ints before performing the modulo
        val positiveHash = numericHash.toLong() and 0xFFFFFFFFL
        val sixDigitPin = positiveHash % 1000000

        // Formats as 6 digits with leading zeros (e.g., 4521 -> "004521")
        return String.format("%06d", sixDigitPin)
    }
}