package com.example.presensor.controllers

import android.content.Intent
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.MainActivity
import com.example.presensor.controllers.dialogs.DialogFactory
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
import kotlinx.coroutines.Job
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
    // Optimization: Reuse transport and factory instances
    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    private var driveService: Drive? = null
    private var sheetsService: Sheets? = null

    private var currentAccessToken: String? = null

    // Track the active coroutine job to allow user cancellation via Back button
    private var activeJob: Job? = null

    fun cancelActiveOperation() {
        activeJob?.cancel()
        activeJob = null
    }

    // Bundle all required OAuth permissions using standard modern Scope objects
    private val requestedScopes = listOf(
        Scope("https://www.googleapis.com/auth/drive.metadata.readonly"),
        Scope(DriveScopes.DRIVE_FILE),
        Scope(SheetsScopes.SPREADSHEETS)
    )

    fun getSheetsService(): Sheets? = sheetsService

    /**
     * Checks for permissions and prompts login via modern IntentSender resolutions if needed.
     */
    fun runWithCloudAuthentication(
        signInLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onAuthSuccess: () -> Unit
    ) {
        Log.d("CloudSync", "Initiating cloud authentication check...")
        val authorizationClient = Identity.getAuthorizationClient(activity)
        val authorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .build()

        authorizationClient.authorize(authorizationRequest)
            .addOnSuccessListener { result ->
                Log.d("CloudSync", "Authorization check success. Has resolution: ${result.hasResolution()}")
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

            Log.d("CloudSync", "Token successfully fetched: ${result.accessToken?.take(10)}...")
            initializeCloudServices(result.accessToken)
            onAuthSuccess()

        } catch (e: com.google.android.gms.common.api.ApiException) {
            // Log the explicit Play Services error code (e.g., 10, 16, etc.)
            Log.e("CloudSync", "Google Play Services Authorization failed! Status Code: ${e.statusCode}", e)
            Toast.makeText(activity, "Auth Failed Code: ${e.statusCode}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("CloudSync", "Google Identity authorization token processing failure", e)
            Toast.makeText(activity, activity.getString(R.string.toast_cloud_auth_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeCloudServices(accessToken: String?) {
        if (accessToken == null) return
        
        // Skip re-initialization if the token hasn't changed
        if (accessToken == currentAccessToken && driveService != null && sheetsService != null) {
            return
        }
        
        currentAccessToken = accessToken

        val requestInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
            // Optimization: Set reasonable timeouts for mobile networks
            request.connectTimeout = 10000 // 10s
            request.readTimeout = 10000    // 10s
        }

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

        activeJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            (activity as? MainActivity)?.currentOverlayJob = coroutineContext[Job]
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
        Log.d("CloudSync", "Fetching backups from Drive...")
        activeJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            (activity as? MainActivity)?.currentOverlayJob = coroutineContext[Job]
            try {
                val service = driveService ?: return@launch
                val prefix = activity.getString(R.string.dialog_cloud_backup_prefix)
                val result: FileList = service.files().list()
                    .setQ("name contains '$prefix' and mimeType = 'text/csv' and trashed = false")
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()

                Log.d("CloudSync", "Backups fetched: ${result.files?.size ?: 0} items found.")
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
        activeJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            (activity as? MainActivity)?.currentOverlayJob = coroutineContext[Job]
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
        Log.d("CloudSync", "Fetching spreadsheets from Drive...")
        activeJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            (activity as? MainActivity)?.currentOverlayJob = coroutineContext[Job]
            try {
                val service = driveService ?: return@launch
                val result: FileList = service.files().list()
                    .setQ("mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false")
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()

                Log.d("CloudSync", "Spreadsheets fetched: ${result.files?.size ?: 0} items found.")
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
        activeJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            (activity as? MainActivity)?.currentOverlayJob = coroutineContext[Job]
            try {
                val service = sheetsService ?: return@launch

                // OPTIMIZATION: Only ask for sheets.properties.title
                val spreadsheet = service.spreadsheets().get(spreadsheetId)
                    .setFields("sheets.properties.title")
                    .execute()

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

    /**
     * A unified helper to display a searchable dialog for any collection of Google Drive file structures.
     */
    fun <T> showCloudFileDialog(
        title: String,
        subtitle: String,
        driveItems: List<T>,
        getName: (T) -> String,
        onItemSelected: (T) -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_cloud_import, null)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.dialogSubtitle)
        val listView = dialogView.findViewById<ListView>(R.id.backupListView)
        val searchView =
            dialogView.findViewById<androidx.appcompat.widget.SearchView>(R.id.dialogSearchView)

        txtSubtitle.text = subtitle

        // Create a robust string map so we can retrieve the full generic object when filtered
        val itemMap = driveItems.associateBy { getName(it) }
        val itemNames = driveItems.map { getName(it) }

        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, itemNames)
        listView.adapter = adapter

        searchView.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter.filter(newText)
                return true
            }
        })

        with(DialogFactory) {
            val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(dialogView)
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()

            listView.setOnItemClickListener { _, _, position, _ ->
                val selectedName = adapter.getItem(position) ?: return@setOnItemClickListener
                val selectedItem = itemMap[selectedName] ?: return@setOnItemClickListener
                dialog.dismiss()
                onItemSelected(selectedItem)
            }
        }
    }
}