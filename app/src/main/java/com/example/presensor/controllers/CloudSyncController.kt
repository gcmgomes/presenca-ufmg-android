package com.example.presensor.controllers

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.FileList
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CloudSyncController(
    private val activity: AppCompatActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase
) {
    private var driveService: Drive? = null
    private var sheetsService: Sheets? = null

    // Cache the active access token for token pre-fetching or validation validations if needed
    private var currentAccessToken: String? = null

    // Bundle all required OAuth permissions using standard modern Scope objects
    private val requestedScopes = listOf(
        Scope("https://www.googleapis.com/auth/drive.metadata.readonly"),
        Scope(DriveScopes.DRIVE_FILE),
        Scope(SheetsScopes.SPREADSHEETS_READONLY)
    )

    fun getSheetsService(): Sheets? = sheetsService

    /**
     * Checks for permissions and prompts login via modern IntentSender resolutions if needed.
     */
    fun runWithCloudAuthentication(
        signInLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onAuthSuccess: () -> Unit
    ) {
        val authorizationClient = Identity.getAuthorizationClient(activity)
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .build()

        authorizationClient.authorize(authorizationRequest)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    // Authorization needed: trigger modern UI flow resolution account picker
                    val pendingIntent = result.pendingIntent!!
                    signInLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                } else {
                    // Already authorized: tokens are immediately available in the result
                    initializeCloudServices(result.accessToken)
                    onAuthSuccess()
                }
            }
            .addOnFailureListener { e ->
                Log.e("CloudSync", "Authorization request validation failed", e)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_cloud_auth_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    /**
     * Handles the callback when returning from the Identity account picker window.
     */
    fun handleSignInResult(data: Intent?, onAuthSuccess: () -> Unit) {
        try {
            val authorizationClient = Identity.getAuthorizationClient(activity)
            val result = authorizationClient.getAuthorizationResultFromIntent(data)

            initializeCloudServices(result.accessToken)
            onAuthSuccess()

        } catch (e: Exception) {
            Log.e("CloudSync", "Google Identity authorization token processing failure", e)
            Toast.makeText(
                activity,
                activity.getString(R.string.toast_cloud_auth_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun initializeCloudServices(accessToken: String?) {
        if (accessToken == null) return
        currentAccessToken = accessToken

        // Injects the OAuth2 bearer access token seamlessly into HTTP headers
        val requestInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
        }

        val transport = NetHttpTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        driveService = Drive.Builder(transport, jsonFactory, requestInitializer)
            .setApplicationName("Presensor")
            .build()

        sheetsService = Sheets.Builder(transport, jsonFactory, requestInitializer)
            .setApplicationName("Presensor")
            .build()
    }

    fun uploadBackupToDrive(customSuffix: String, onLoadingToggle: (Boolean) -> Unit) {
        val service = driveService
        if (service == null) {
            Toast.makeText(
                activity,
                activity.getString(R.string.toast_cloud_service_not_initialized),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        onLoadingToggle(true)

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val outputStream = ByteArrayOutputStream()
                val dumpSuccess = db.performFullDatabaseDump(outputStream)
                if (!dumpSuccess) throw IllegalStateException("Database serialization sequence failed")

                val csvBytes = outputStream.toByteArray()
                val mediaContent = InputStreamContent("text/csv", ByteArrayInputStream(csvBytes))

                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    val cleanSuffix = customSuffix.trim().ifEmpty {
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                    }
                    name =
                        "${activity.getString(R.string.dialog_cloud_backup_prefix)}${cleanSuffix}.csv"
                }

                service.files().create(fileMetadata, mediaContent).setFields("id").execute()

                withContext(Dispatchers.Main) {
                    onLoadingToggle(false)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_cloud_upload_success),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Google Drive file delivery failure", e)
                withContext(Dispatchers.Main) {
                    onLoadingToggle(false)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_cloud_upload_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun fetchAvailableBackups(onResult: (List<com.google.api.services.drive.model.File>) -> Unit) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val service = driveService ?: return@launch
                val prefix = activity.getString(R.string.dialog_cloud_backup_prefix)
                val result: FileList = service.files().list()
                    .setQ("name contains '$prefix' and mimeType = 'text/csv' and trashed = false")
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()

                withContext(Dispatchers.Main) {
                    onResult(result.files ?: emptyList())
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to query backups from Drive", e)
                withContext(Dispatchers.Main) {
                    onResult(emptyList())
                }
            }
        }
    }

    fun downloadAndRestoreBackup(
        fileId: String,
        onLoadingToggle: (Boolean) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val service = driveService ?: return@launch
            onLoadingToggle(true)

            withContext(Dispatchers.IO) {
                try {
                    val outputStream = ByteArrayOutputStream()
                    service.files().get(fileId).executeMediaAndDownloadTo(outputStream)

                    val inputStream = ByteArrayInputStream(outputStream.toByteArray())
                    val success = db.importFullDatabaseDump(inputStream)

                    withContext(Dispatchers.Main) {
                        onLoadingToggle(false)
                        if (success) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.toast_cloud_restore_success),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.toast_cloud_restore_failed_parse),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        onComplete(success)
                    }
                } catch (e: Exception) {
                    Log.e("CloudSync", "Failed to download backup file", e)
                    withContext(Dispatchers.Main) {
                        onLoadingToggle(false)
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.toast_cloud_download_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                        onComplete(false)
                    }
                }
            }
        }
    }

    /**
     * Queries Google Drive for Spreadsheets matching the standard Google Sheets MimeType.
     */
    fun fetchAvailableSpreadsheets(onResult: (List<com.google.api.services.drive.model.File>) -> Unit) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val service = driveService ?: return@launch
                val result: FileList = service.files().list()
                    .setQ("mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false")
                    .setSpaces("drive")
                    .setCorpora("user")
                    .setFields("files(id, name)")
                    .execute()

                withContext(Dispatchers.Main) {
                    onResult(result.files ?: emptyList())
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to query spreadsheets from Drive", e)
                withContext(Dispatchers.Main) { onResult(emptyList()) }
            }
        }
    }

    /**
     * Fetches individual worksheet tab titles inside a target spreadsheet ID.
     */
    fun fetchSpreadsheetTabs(spreadsheetId: String, onResult: (List<String>) -> Unit) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val service = sheetsService ?: return@launch
                val spreadsheet = service.spreadsheets().get(spreadsheetId).execute()
                val sheetNames = spreadsheet.sheets?.map { it.properties.title } ?: emptyList()

                withContext(Dispatchers.Main) {
                    onResult(sheetNames)
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to retrieve sheet tabs metadata", e)
                withContext(Dispatchers.Main) { onResult(emptyList()) }
            }
        }
    }
}