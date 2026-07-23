package com.example.presensor.communication.core

import android.bluetooth.le.ScanResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the contract for low-level hardware communication.
 */
interface ReaderTransport {
    // Connection Lifecycle
    fun connect(address: String)
    fun disconnect()
    
    // Discovery
    fun startScan(isBroad: Boolean)
    fun stopScan()
    
    // Communication
    fun write(payload: ByteArray, channel: TransportChannel)
    
    // Hardware Commands
    fun requestRssi()
    fun requestMtu(mtu: Int)
    
    // Reactive States
    val connectionState: StateFlow<TransportConnectionState>
    val isScanning: StateFlow<Boolean>
    val incomingData: SharedFlow<Pair<ByteArray, TransportChannel>>
    val discoveredDevices: SharedFlow<ScanResult>
    val lastRssi: StateFlow<Int?>
    val connectedAddress: StateFlow<String?>
}
