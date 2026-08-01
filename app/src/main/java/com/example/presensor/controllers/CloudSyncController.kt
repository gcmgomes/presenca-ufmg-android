package com.example.presensor.controllers

import android.util.Log
import com.example.presensor.R
import com.example.presensor.data.AppDatabase
import com.example.presensor.controllers.providers.CloudInteractionProvider
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.FileList
import com.google.api.services.sheets.v4.Sheets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CloudSyncController(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val interactionProvider: CloudInteractionProvider,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    private var driveService: Drive? = null
    private var sheetsService: Sheets? = null
    private var currentAccessToken: String? = null

    private var activeJob: Job? = null

    fun cancelActiveOperation() {
        activeJob?.cancel()
        activeJob = null
    }

    fun getSheetsService(): Sheets? = sheetsService

    /**
     * Orchestrates authentication and executes the provided action upon success.
     */
    fun runWithCloudAuthentication(onAuthSuccess: () -> Unit) {
        interactionProvider.runWithCloudAuthentication { token ->
            initializeCloudServices(token)
            onAuthSuccess()
        }
    }

    private fun initializeCloudServices(accessToken: String?) {
        if (accessToken == null) return
        if (accessToken == currentAccessToken && driveService != null && sheetsService != null) return
        
        currentAccessToken = accessToken
        val requestInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
            request.connectTimeout = 10000
            request.readTimeout = 10000
        }

        driveService = Drive.Builder(transport, jsonFactory, requestInitializer)
            .setApplicationName("Presensor")
            .build()

        sheetsService = Sheets.Builder(transport, jsonFactory, requestInitializer)
            .setApplicationName("Presensor")
            .build()
    }

    fun uploadBackupToDrive(customSuffix: String) {
        val service = driveService
        if (service == null) {
            interactionProvider.showToast(R.string.toast_cloud_service_not_initialized)
            return
        }

        interactionProvider.toggleLoading(true)

        activeJob = scope.launch(ioDispatcher) {
            interactionProvider.setLoadingJob(coroutineContext[Job])
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
                    name = "${interactionProvider.getString(R.string.dialog_cloud_backup_prefix)}${cleanSuffix}.csv"
                }

                service.files().create(fileMetadata, mediaContent).setFields("id").execute()

                withContext(mainDispatcher) {
                    interactionProvider.toggleLoading(false)
                    interactionProvider.showToast(interactionProvider.getString(R.string.toast_cloud_upload_success), isShort = false)
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Google Drive file delivery failure", e)
                withContext(mainDispatcher) {
                    interactionProvider.toggleLoading(false)
                    interactionProvider.showToast(R.string.toast_cloud_upload_failed)
                }
            }
        }
    }

    fun fetchAvailableBackups(onResult: (List<com.google.api.services.drive.model.File>) -> Unit) {
        activeJob = scope.launch(ioDispatcher) {
            interactionProvider.setLoadingJob(coroutineContext[Job])
            try {
                val service = driveService ?: return@launch
                val prefix = interactionProvider.getString(R.string.dialog_cloud_backup_prefix)
                val result: FileList = service.files().list()
                    .setQ("name contains '$prefix' and mimeType = 'text/csv' and trashed = false")
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()

                withContext(mainDispatcher) {
                    onResult(result.files ?: emptyList())
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to query backups from Drive", e)
                withContext(mainDispatcher) {
                    onResult(emptyList())
                }
            }
        }
    }

    fun downloadAndRestoreBackup(fileId: String, onComplete: (Boolean) -> Unit) {
        val service = driveService ?: return
        interactionProvider.toggleLoading(true)

        activeJob = scope.launch(ioDispatcher) {
            interactionProvider.setLoadingJob(coroutineContext[Job])
            try {
                val outputStream = ByteArrayOutputStream()
                service.files().get(fileId).executeMediaAndDownloadTo(outputStream)

                val inputStream = ByteArrayInputStream(outputStream.toByteArray())
                val success = db.importFullDatabaseDump(inputStream)

                withContext(mainDispatcher) {
                    interactionProvider.toggleLoading(false)
                    if (success) {
                        interactionProvider.showToast(interactionProvider.getString(R.string.toast_cloud_restore_success), isShort = false)
                    } else {
                        interactionProvider.showToast(R.string.toast_cloud_restore_failed_parse)
                    }
                    onComplete(success)
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to download backup file", e)
                withContext(mainDispatcher) {
                    interactionProvider.toggleLoading(false)
                    interactionProvider.showToast(R.string.toast_cloud_download_failed)
                    onComplete(false)
                }
            }
        }
    }

    fun fetchAvailableSpreadsheets(onResult: (List<com.google.api.services.drive.model.File>) -> Unit) {
        activeJob = scope.launch(ioDispatcher) {
            interactionProvider.setLoadingJob(coroutineContext[Job])
            try {
                val service = driveService ?: return@launch
                val result: FileList = service.files().list()
                    .setQ("mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false")
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()

                withContext(mainDispatcher) {
                    onResult(result.files ?: emptyList())
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to query spreadsheets from Drive", e)
                withContext(mainDispatcher) { onResult(emptyList()) }
            }
        }
    }

    fun fetchSpreadsheetTabs(spreadsheetId: String, onResult: (List<String>) -> Unit) {
        activeJob = scope.launch(ioDispatcher) {
            interactionProvider.setLoadingJob(coroutineContext[Job])
            try {
                val service = sheetsService ?: return@launch
                val spreadsheet = service.spreadsheets().get(spreadsheetId)
                    .setFields("sheets.properties.title")
                    .execute()

                val sheetNames = spreadsheet.sheets?.map { it.properties.title } ?: emptyList()

                withContext(mainDispatcher) {
                    onResult(sheetNames)
                }
            } catch (e: Exception) {
                Log.e("CloudSync", "Failed to retrieve sheet tabs metadata", e)
                withContext(mainDispatcher) { onResult(emptyList()) }
            }
        }
    }

    fun <T> showCloudFileDialog(
        title: String,
        subtitle: String,
        driveItems: List<T>,
        getName: (T) -> String,
        onItemSelected: (T) -> Unit
    ) {
        interactionProvider.showCloudFileDialog(title, subtitle, driveItems, getName, onItemSelected)
    }
}
