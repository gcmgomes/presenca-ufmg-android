package com.example.presensor.communication.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Handles device-specific communication logic: command formatting and payload parsing.
 */
class ReaderProtocol {

    companion object {
        private const val TAG = "ReaderProtocol"
    }

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    private val _domainEvents = MutableSharedFlow<ProtocolEvent>(replay = 0)
    val domainEvents: SharedFlow<ProtocolEvent> = _domainEvents

    private var lastProcessedTag: String? = null
    private var lastProcessedTimestamp: String? = null

    var authPassword: String? = null

    /**
     * Processes raw data received from the transport layer.
     */
    suspend fun processData(data: ByteArray, channel: TransportChannel) {
        val payload = String(data, Charsets.UTF_8).replace("\u0000", "").trim()
        if (payload.isEmpty()) return

        when (channel) {
            TransportChannel.AUTH -> handleAuthPayload(payload)
            TransportChannel.DATA -> handleRfidPayload(payload, isInventory = false)
            TransportChannel.INVENTORY -> handleInventoryPayload(payload)
            TransportChannel.STATUS -> handleStatusPayload(payload)
            else -> {
                Log.w(TAG, "[Protocol] Unhandled channel $channel for payload '$payload'")
            }
        }
    }

    fun resetAuth() {
        Log.d(TAG, "[Protocol] Explicit Auth Reset requested.")
        _isAuthenticated.value = false
    }

    private suspend fun handleAuthPayload(payload: String) {
        if (payload == "SUCCESS") {
            Log.i(TAG, "[Auth] Handshake SUCCESS confirmed by hardware.")
            _isAuthenticated.value = true
            _domainEvents.emit(ProtocolEvent.AuthSuccess)
        } else if (payload == "FAIL") {
            Log.e(TAG, "[Auth] Handshake FAILED: Invalid credentials.")
            _isAuthenticated.value = false
            _domainEvents.emit(ProtocolEvent.AuthFailed)
        } else {
            Log.w(TAG, "[Auth] Unexpected auth payload: '$payload'")
        }
    }

    private suspend fun handleRfidPayload(payload: String, isInventory: Boolean) {
        if (payload == "DONE") {
            _domainEvents.emit(ProtocolEvent.SyncDone)
            return
        }

        val parts = payload.split(",")
        if (parts.size == 2) {
            val tagId = parts[0].trim()
            val timestampStr = parts[1].trim()
            try {
                val epochSec = timestampStr.toLong()

                if (!isInventory) {
                    if (tagId == lastProcessedTag && timestampStr == lastProcessedTimestamp) {
                        _domainEvents.emit(ProtocolEvent.AckRequired(tagId, timestampStr))
                        return
                    }
                    lastProcessedTag = tagId
                    lastProcessedTimestamp = timestampStr
                    _domainEvents.emit(ProtocolEvent.RfidSwipe(tagId, epochSec))
                    _domainEvents.emit(ProtocolEvent.AckRequired(tagId, timestampStr))
                } else {
                    _domainEvents.emit(ProtocolEvent.InventoryItem(tagId, epochSec))
                }
            } catch (e: NumberFormatException) {
                Log.e(
                    TAG,
                    "[Protocol Error] Failed to parse timestamp: '$timestampStr' in payload '$payload'"
                )
            }
        } else {
            Log.w(TAG, "[Protocol Warning] Malformed RFID payload (expected 2 parts): '$payload'")
        }
    }

    private suspend fun handleInventoryPayload(payload: String) {
        when {
            payload == "DEL_OK" -> _domainEvents.emit(ProtocolEvent.DeletionSuccess)
            payload == "DEL_ERR" -> _domainEvents.emit(ProtocolEvent.DeletionError)
            payload.startsWith("INFO,") -> handleMetricsPayload(payload)
            else -> handleRfidPayload(payload, isInventory = true)
        }
    }

    private suspend fun handleStatusPayload(payload: String) {
        val parts = payload.split(",")
        if (parts.size == 2) {
            try {
                val epoch = parts[0].trim().toLong()
                val battery = parts[1].trim().toInt()
                _domainEvents.emit(ProtocolEvent.Metrics(epoch, battery))
            } catch (e: Exception) {
                Log.e(TAG, "[Protocol Error] Failed to parse status payload: '$payload'", e)
            }
        } else {
            Log.w(TAG, "[Protocol Warning] Malformed status payload (expected 2 parts): '$payload'")
        }
    }

    private suspend fun handleMetricsPayload(payload: String) {
        val parts = payload.split(",")
        if (parts.size == 3) {
            try {
                val epoch = parts[1].trim().toLong()
                val battery = parts[2].trim().toInt()
                _domainEvents.emit(ProtocolEvent.Metrics(epoch, battery))
            } catch (e: Exception) {
                Log.e(TAG, "[Protocol Error] Failed to parse metrics payload: '$payload'", e)
            }
        } else {
            Log.w(
                TAG,
                "[Protocol Warning] Malformed metrics payload (expected 3 parts): '$payload'"
            )
        }
    }

    /**
     * Formats commands into raw byte arrays for the transport layer.
     */
    fun formatAuthCommand(password: String): ByteArray {
        return password.toByteArray(Charsets.UTF_8)
    }

    fun formatAppModeCommand(mode: AppMode): ByteArray {
        return when (mode) {
            AppMode.IDLE -> "IDLE".toByteArray()
            AppMode.ACTIVE -> "ACTIVE".toByteArray()
            AppMode.MANAGEMENT -> "MANAGEMENT".toByteArray()
        }
    }

    fun formatTimeSyncCommand(epochSeconds: Long): ByteArray {
        return epochSeconds.toString().toByteArray()
    }

    fun formatInventoryGetCommand(): ByteArray {
        return "GET".toByteArray()
    }

    fun formatInventoryDeleteCommand(tagId: String, timestamp: Long): ByteArray {
        val cleanTagId = tagId.replace(":", "")
        return "DEL,$cleanTagId,$timestamp".toByteArray()
    }

    fun formatSyncCommand(): ByteArray {
        return "SYNC".toByteArray()
    }

    fun formatStatusGetCommand(): ByteArray {
        return "GET".toByteArray()
    }

    fun formatAckCommand(tagId: String, timestamp: String): ByteArray {
        return "$tagId,$timestamp".toByteArray()
    }

    fun formatConfigUpdateCommand(newName: String, newPassword: String): ByteArray {
        val payload = "$newName\t$newPassword"
        return payload.toByteArray(Charsets.UTF_8)
    }
}
