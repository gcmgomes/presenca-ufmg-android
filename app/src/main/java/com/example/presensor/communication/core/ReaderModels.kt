package com.example.presensor.communication.core

/**
 * Low-level transport connection states.
 */
enum class TransportConnectionState {
    DISCONNECTED,
    CONNECTING,
    READY // Ready means hardware link is up and ready for protocol handshake
}

/**
 * Logical communication channels mapping to physical characteristics or endpoints.
 */
enum class TransportChannel {
    AUTH,
    DATA,
    MODE,
    CONFIG,
    TIME,
    ACK,
    INVENTORY,
    STATUS
}

/**
 * High-level reader operation modes.
 */
enum class AppMode {
    IDLE,
    ACTIVE,
    MANAGEMENT
}

/**
 * Domain events emitted by the Protocol layer after parsing raw data.
 */
sealed class ProtocolEvent {
    data class RfidSwipe(val tagId: String, val timestamp: Long) : ProtocolEvent()
    data class InventoryItem(val tagId: String, val timestamp: Long) : ProtocolEvent()
    data class Metrics(val timestamp: Long, val batteryLevel: Int) : ProtocolEvent()
    object SyncDone : ProtocolEvent()
    object DeletionSuccess : ProtocolEvent()
    object DeletionError : ProtocolEvent()
    object AuthSuccess : ProtocolEvent()
    object AuthFailed : ProtocolEvent()
    data class AckRequired(val tagId: String, val timestamp: String) : ProtocolEvent()
}

/**
 * Represents a discovered BLE reader device.
 */
data class ReaderDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val batteryLevel: Int? = null,
    val deviceEpoch: Long? = null,
    val lastSeen: Long = System.currentTimeMillis()
)
