package com.example.presensor.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.annotation.RequiresPermission

class BlePairingReceiver(private val plaintextPassword: String) : BroadcastReceiver() {

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (BluetoothDevice.ACTION_PAIRING_REQUEST == action) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)

            // Check if the pairing request requires a PIN code input
            if (pairingVariant == BluetoothDevice.PAIRING_VARIANT_PIN ||
                pairingVariant == 2
            ) { // 2 corresponds to PAIRING_VARIANT_PASSKEY in hidden APIs

                // Convert the user's password to the 6-digit hardware equivalent
                val hardwarePin = SecurityBuffer.generateBlePin(plaintextPassword)

                try {
                    // Automatically pass the PIN to the BLE bonding subsystem
                    device?.setPin(hardwarePin.toByteArray(Charsets.UTF_8))

                    // Abort the broadcast to prevent the native system popup from showing up
                    abortBroadcast()
                    Log.d(
                        "PresensorSecure",
                        "Automated pairing PIN injected successfully: $hardwarePin"
                    )
                } catch (e: Exception) {
                    Log.e("PresensorSecure", "Failed to auto-inject pairing PIN", e)
                }
            }
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY // Catch it before the OS dialog displays
        }
        context.registerReceiver(this, filter)
    }

    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
        } catch (e: Exception) {
        }
    }
}