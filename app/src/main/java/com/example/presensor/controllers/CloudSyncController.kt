package com.example.presensor.controllers

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections

class CloudSyncController(
    private val activity: AppCompatActivity,
    private val lifecycleOwner: LifecycleOwner,
    private val db: AppDatabase
) {
    private var driveService: Drive? = null
    private var credential: GoogleAccountCredential? = null

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
//        .requestScopes(Scope(DriveScopes.DRIVE_FILE))
        .requestScopes(Scope("https://www.googleapis.com/auth/drive.metadata.readonly"))
        .requestEmail()
        .build()

    private val googleSignInClient = GoogleSignIn.getClient(activity, gso)

    // Add this property declaration near your driveService field
    private var sheetsService: com.google.api.services.sheets.v4.Sheets? = null

// Inside initializeCloudServices, add this builder line right below driveService assignment:


    fun runWithCloudAuthentication(
        signInLauncher: ActivityResultLauncher<Intent>,
        onAuthSuccess: () -> Unit
    ) {
        val lastAccount = GoogleSignIn.getLastSignedInAccount(activity)
        if (lastAccount != null && GoogleSignIn.hasPermissions(
                lastAccount,
                Scope(DriveScopes.DRIVE_FILE)
            )
        ) {
            initializeCloudServices(lastAccount)
            onAuthSuccess()
        } else {
            signInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    fun handleSignInResult(data: Intent?, onAuthSuccess: () -> Unit) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(Exception::class.java)
            if (account != null) {
                initializeCloudServices(account)

                // Warm up the token in the background right now to avoid the first-click race condition
                lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // Forcing the credential to fetch the token from Play Services *before* running our API action
                        credential?.token

                        withContext(Dispatchers.Main) {
                            onAuthSuccess()
                        }
                    } catch (e: Exception) {
                        Log.e("CloudSync", "Failed to pre-fetch OAuth token", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.toast_cloud_auth_pre_fetch_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } else {
                throw IllegalStateException("Google account returned null")
            }
        } catch (e: Exception) {
            Log.e("CloudSync", "Google accounts integration auth failure", e)
            Toast.makeText(
                activity,
                activity.getString(R.string.toast_cloud_auth_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun initializeCloudServices(account: GoogleSignInAccount) {
        credential = GoogleAccountCredential.usingOAuth2(
            activity,
            Collections.singletonList("https://www.googleapis.com/auth/drive.metadata.readonly")
        ).apply {
            selectedAccount = account.account
        }

        val transport = NetHttpTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        driveService = Drive.Builder(transport, jsonFactory, credential)
            .setApplicationName("Presensor")
            .build()
        sheetsService =
            com.google.api.services.sheets.v4.Sheets.Builder(transport, jsonFactory, credential)
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
                    .setCorpora("user") // Explicitly read across everything shared with or owned by this user
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

    /**
     * Downloads cell matrix rows from a given sheet tab and saves them to the DB.
     * Assumes column structure: Column A = Name, Column B = Email (Adjust to match your structure)
     */
    fun importStudentsFromSheet(
        spreadsheetId: String,
        tabTitle: String,
        onComplete: (Int) -> Unit
    ) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val service = sheetsService ?: return@launch
                // Query columns A to C to pull structural content data arrays
                val range = "'$tabTitle'!A1:C500"
                val response = service.spreadsheets().values().get(spreadsheetId, range).execute()
                val rows = response.getValues() ?: emptyList()

                if (rows.isEmpty()) {
                    withContext(Dispatchers.Main) { onComplete(0) }
                    return@launch
                }

                var importedCount = 0

                // Loop data rows safely (Skipping index 0 if your spreadsheet has headers)
                for (i in 1 until rows.size) {
                    val row = rows[i]
                    if (row.size >= 2) {
                        val studentName = row[0].toString().trim()
                        val studentEmail = row[1].toString().trim()

                        if (studentName.isNotEmpty() && studentEmail.isNotEmpty()) {
                            // TODO: Map to your custom DB entity insertions here!
                            // e.g., db.studentDao().insert(Student(name = studentName, email = studentEmail))
                            importedCount++
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    onComplete(importedCount)
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to read rows within selected sheet layout bounds", e)
                withContext(Dispatchers.Main) { onComplete(-1) }
            }
        }
    }
}