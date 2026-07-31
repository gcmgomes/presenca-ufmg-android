package com.example.presensor.controllers.items

/**
 * UI representation of a discovered reader device.
 */
data class DeviceItem(
    val name: String,
    val address: String,
    val rssi: Int?,
    val batteryLevel: Int? = null,
    val deviceEpoch: Long? = null,
    val isConnected: Boolean,
    val isConnecting: Boolean,
    val isNearby: Boolean = true,
    val lastSeen: Long = 0
)
