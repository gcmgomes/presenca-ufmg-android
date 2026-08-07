package com.example.presensor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecureStoreManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var secureStoreManager: SecureStoreManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Use regular SharedPreferences for tests to avoid Keystore issues in Robolectric
        prefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        secureStoreManager = SecureStoreManager(prefs)
    }

    @Test
    fun testDefaultConstructor_Fallback() {
        // This tests the branch where it falls back to regular prefs if EncryptedSharedPreferences fails
        // In Robolectric it will fail and fall back.
        val manager = SecureStoreManager(context)
        assertNotNull(manager)
        // Verify it works
        manager.deviceName = "FallbackTest"
        assertEquals("FallbackTest", manager.deviceName)
    }

    @Test
    fun testIsReaderEnabled_DefaultIsFalse() {
        assertFalse(secureStoreManager.isReaderEnabled)
    }

    @Test
    fun testIsReaderEnabled_Persistence() {
        secureStoreManager.isReaderEnabled = true
        assertTrue(secureStoreManager.isReaderEnabled)
        
        // Re-instantiate with same prefs to verify persistence
        val newManager = SecureStoreManager(prefs)
        assertTrue(newManager.isReaderEnabled)
    }

    @Test
    fun testDeviceName_DefaultValue() {
        assertEquals("Presensor_Reader", secureStoreManager.deviceName)
    }

    @Test
    fun testDeviceName_Persistence() {
        val newName = "Custom_Reader"
        secureStoreManager.deviceName = newName
        assertEquals(newName, secureStoreManager.deviceName)

        val newManager = SecureStoreManager(prefs)
        assertEquals(newName, newManager.deviceName)
    }

    @Test
    fun testSaveReaderCredentials_MultipleDevices() {
        secureStoreManager.saveReaderCredentials("Device1", "pass1")
        secureStoreManager.saveReaderCredentials("Device2", "pass2")

        assertTrue(secureStoreManager.hasPasswordFor("Device1"))
        assertTrue(secureStoreManager.hasPasswordFor("Device2"))
        assertFalse(secureStoreManager.hasPasswordFor("Device3"))
    }

    @Test
    fun testHasPasswordFor_BlankPassword() {
        secureStoreManager.saveReaderCredentials("Device1", "  ")
        assertFalse(secureStoreManager.hasPasswordFor("Device1"))
    }

    @Test
    fun testGetAuthPasswordFor() {
        val name = "Device1"
        val pass = "secret123"
        secureStoreManager.saveReaderCredentials(name, pass)
        
        assertEquals(pass, secureStoreManager.getAuthPasswordFor(name))
        assertNull(secureStoreManager.getAuthPasswordFor("NonExistent"))
    }

    @Test
    fun testGetAuthPasswordBytes() {
        val name = "ActiveDevice"
        val pass = "bytes_test"
        secureStoreManager.deviceName = name
        secureStoreManager.saveReaderCredentials(name, pass)

        val bytes = secureStoreManager.getAuthPasswordBytes()
        assertArrayEquals(pass.toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun testGetAuthPasswordBytes_NoMatch() {
        secureStoreManager.deviceName = "NoPassDevice"
        val bytes = secureStoreManager.getAuthPasswordBytes()
        assertEquals(0, bytes.size)
    }

    @Test
    fun testClearCredentialsFor() {
        val name = "ToClear"
        secureStoreManager.saveReaderCredentials(name, "pass")
        assertTrue(secureStoreManager.hasPasswordFor(name))

        val result = secureStoreManager.clearCredentialsFor(name)
        assertTrue(result)
        assertFalse(secureStoreManager.hasPasswordFor(name))
        assertNull(secureStoreManager.getAuthPasswordFor(name))

        // Clearing non-existent
        val resultNonExistent = secureStoreManager.clearCredentialsFor("Unknown")
        assertFalse(resultNonExistent)
    }

    @Test
    fun testJsonParsingErrorPaths() {
        // Injecting invalid JSON string directly into prefs
        prefs.edit().putString("pref_reader_credentials_map", "invalid_json").commit()

        // These should catch the exception and return false/null
        assertFalse(secureStoreManager.hasPasswordFor("Any"))
        assertNull(secureStoreManager.getAuthPasswordFor("Any"))
        assertFalse(secureStoreManager.clearCredentialsFor("Any"))
        
        // saveReaderCredentials should also handle it by catching the exception
        secureStoreManager.saveReaderCredentials("New", "Pass")
        // It shouldn't crash, and since it failed to parse the old one, it might have failed to save.
    }
}
