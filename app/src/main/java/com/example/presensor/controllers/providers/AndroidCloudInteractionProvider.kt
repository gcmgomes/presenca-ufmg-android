package com.example.presensor.controllers.providers

import android.app.Activity.RESULT_OK
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory.showWithSmartNfcReading
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.SheetsScopes

class AndroidCloudInteractionProvider(
    activity: MainActivity
) : BaseAndroidInteractionProvider(activity), CloudInteractionProvider {

    private var onCloudAuthSuccessCallback: ((String) -> Unit)? = null
    
    private val cloudSignInLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.activityResultRegistry.register("cloud_sign_in", activity, ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                try {
                    val authorizationClient = Identity.getAuthorizationClient(activity)
                    val authResult = authorizationClient.getAuthorizationResultFromIntent(result.data)
                    authResult.accessToken?.let { token ->
                        onCloudAuthSuccessCallback?.invoke(token)
                    }
                } catch (e: Exception) {
                    showToast(R.string.toast_cloud_auth_failed)
                    Log.e("CloudAuth", "Authorization result processing failed", e)
                }
            } else {
                toggleLoading(false)
            }
        }

    override fun runWithCloudAuthentication(onAuthSuccess: (String) -> Unit) {
        this.onCloudAuthSuccessCallback = onAuthSuccess
        activity.runOnUiThread {
            val authorizationClient = Identity.getAuthorizationClient(activity)
            val requestedScopes = listOf(
                Scope("https://www.googleapis.com/auth/drive.metadata.readonly"),
                Scope(DriveScopes.DRIVE_FILE),
                Scope(SheetsScopes.SPREADSHEETS)
            )
            val authorizationRequest = AuthorizationRequest.builder()
                .setRequestedScopes(requestedScopes)
                .build()

            authorizationClient.authorize(authorizationRequest)
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        val pendingIntent = result.pendingIntent!!
                        cloudSignInLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
                    } else {
                        result.accessToken?.let { token ->
                            onAuthSuccess(token)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    showToast(R.string.toast_cloud_auth_failed)
                }
        }
    }

    override fun <T> showCloudFileDialog(
        title: String,
        subtitle: String,
        driveItems: List<T>,
        getName: (T) -> String,
        onItemSelected: (T) -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_cloud_import, null)
            val txtSubtitle = dialogView.findViewById<TextView>(R.id.dialogSubtitle)
            val listV = dialogView.findViewById<ListView>(R.id.backupListView)
            val searchView =
                dialogView.findViewById<androidx.appcompat.widget.SearchView>(R.id.dialogSearchView)

            txtSubtitle.text = subtitle
            val itemMap = driveItems.associateBy { getName(it) }
            val itemNames = driveItems.map { getName(it) }
            val adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_list_item_1,
                itemNames
            )
            listV.adapter = adapter

            searchView.setOnQueryTextListener(object :
                androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    adapter.filter.filter(newText)
                    return true
                }
            })

            val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(dialogView)
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .showWithSmartNfcReading()

            listV.setOnItemClickListener { _, _, position, _ ->
                val selectedName = adapter.getItem(position) ?: return@setOnItemClickListener
                val selectedItem = itemMap[selectedName] ?: return@setOnItemClickListener
                dialog.dismiss()
                onItemSelected(selectedItem)
            }
        }
    }
}
