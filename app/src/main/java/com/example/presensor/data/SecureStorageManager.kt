package com.yourpackage.presensor.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import androidx.core.content.edit
import org.json.JSONObject
import android.util.Log

class SecureStorageManager(context: Context) {

    companion object {
        private const val PREF_FILE_NAME = "presensor_secure_prefs"
        private const val KEY_CURRENT_DEVICE_NAME = "pref_ble_device_name"
        private const val KEY_READER_MAP_JSON = "pref_reader_credentials_map"

        private const val DEFAULT_DEVICE_NAME = "Presensor_Reader"
    }

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val sharedPreferences = EncryptedSharedPreferences.create(
        PREF_FILE_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Keeps track of the currently selected active target device name.
     */
    var deviceName: String
        get() = sharedPreferences.getString(KEY_CURRENT_DEVICE_NAME, DEFAULT_DEVICE_NAME)
            ?: DEFAULT_DEVICE_NAME
        set(value) {
            sharedPreferences.edit { putString(KEY_CURRENT_DEVICE_NAME, value) }
        }

    /**
     * Saves or updates a password combination for a specific device name.
     */
    fun saveReaderCredentials(name: String, password: String) {
        try {
            val currentMapJson = sharedPreferences.getString(KEY_READER_MAP_JSON, "{}") ?: "{}"
            val jsonObject = JSONObject(currentMapJson)

            // Insert or overwrite the entry
            jsonObject.put(name, password)

            sharedPreferences.edit {
                putString(KEY_READER_MAP_JSON, jsonObject.toString())
            }
        } catch (e: Exception) {
            Log.e("SecureStorageManager", "Failed to save credentials for $name", e)
        }
    }

    /**
     * Checks if a specific device already has a stored password.
     */
    fun hasPasswordFor(name: String): Boolean {
        val currentMapJson = sharedPreferences.getString(KEY_READER_MAP_JSON, "{}") ?: "{}"
        return try {
            val jsonObject = JSONObject(currentMapJson)
            jsonObject.has(name) && jsonObject.getString(name).isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Retrieves the password bytes for the current active device name.
     * Returns an empty byte array if no password match is found.
     */
    fun getAuthPasswordBytes(): ByteArray {
        val activeName = deviceName
        val currentMapJson = sharedPreferences.getString(KEY_READER_MAP_JSON, "{}") ?: "{}"

        return try {
            val jsonObject = JSONObject(currentMapJson)
            if (jsonObject.has(activeName)) {
                jsonObject.getString(activeName).toByteArray(Charsets.UTF_8)
            } else {
                ByteArray(0) // No password stored yet
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /**
     * Erases the stored password credentials for a specific reader name.
     * Returns true if a key was successfully removed, false otherwise.
     */
    fun clearCredentialsFor(readerName: String): Boolean {
        return try {
            val currentMapJson = sharedPreferences.getString(KEY_READER_MAP_JSON, "{}") ?: "{}"
            val jsonObject = JSONObject(currentMapJson)

            if (jsonObject.has(readerName)) {
                jsonObject.remove(readerName) // Drops the key-value pair completely

                sharedPreferences.edit {
                    putString(KEY_READER_MAP_JSON, jsonObject.toString())
                }
                Log.d("SecureStorageManager", "Successfully erased credentials for: $readerName")
                true
            } else {
                Log.d("SecureStorageManager", "No credentials found to erase for: $readerName")
                false
            }
        } catch (e: Exception) {
            Log.e("SecureStorageManager", "Failed to clear credentials for $readerName", e)
            false
        }
    }
}