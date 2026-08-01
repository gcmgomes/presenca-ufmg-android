package com.example.presensor.controllers.providers

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.controllers.adapters.BacklogAdapter
import com.example.presensor.controllers.adapters.DeviceListAdapter
import com.example.presensor.controllers.adapters.ImportBacklogAdapter
import com.example.presensor.controllers.dialogs.DialogFactory
import com.example.presensor.controllers.dialogs.DialogFactory.showWithSmartNfcReading
import com.example.presensor.controllers.items.BacklogItem
import com.example.presensor.controllers.items.DeviceItem
import com.example.presensor.data.SecureStoreManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class AndroidReaderInteractionProvider(
    activity: MainActivity,
    private val secureStoreManager: SecureStoreManager
) : BaseAndroidInteractionProvider(activity), ReaderInteractionProvider {

    private var backlogAdapter: ImportBacklogAdapter? = null
    private var backlogCountText: TextView? = null

    private var onDisconnectRequested: (() -> Unit)? = null
    private var onConnectRequested: (() -> Unit)? = null

    override fun showPasswordPromptDialog(
        readerName: String,
        onPasswordEntered: (String) -> Unit,
        onDismissed: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_reader_password, null)
            val inputField = dialogView.findViewById<TextInputEditText>(R.id.editReaderPassword)

            val dialog = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_assign_tag, readerName))
                .setView(dialogView)
                .setPositiveButton("Connect", null)
                .setNegativeButton(R.string.action_cancel, null)
                .setOnDismissListener { onDismissed() }
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typedPassword = inputField.text.toString().trim()
                if (typedPassword.isNotEmpty()) {
                    onPasswordEntered(typedPassword)
                    dialog.dismiss()
                } else {
                    inputField.error = "Password cannot be blank"
                }
            }
        }
    }

    override fun showEditReaderDialog(
        readerName: String,
        onConfigSaved: (newName: String, newPass: String) -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_edit_reader, null)
            val inputName = dialogView.findViewById<TextInputEditText>(R.id.editReaderName)
            val inputOldPass = dialogView.findViewById<TextInputEditText>(R.id.editOldPassword)
            val inputNewPass = dialogView.findViewById<TextInputEditText>(R.id.editNewPassword)
            val inputConfirmPass =
                dialogView.findViewById<TextInputEditText>(R.id.editConfirmNewPassword)
            inputName.setText(readerName)

            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.action_edit)
                .setView(dialogView)
                .setPositiveButton(R.string.action_save, null)
                .setNegativeButton(R.string.action_cancel, null)
                .showWithSmartNfcReading()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = inputName.text.toString().trim()
                val oldPass = inputOldPass.text.toString()
                val newPass = inputNewPass.text.toString()
                val confirmPass = inputConfirmPass.text.toString()
                val storedPass = secureStoreManager.getAuthPasswordFor(readerName) ?: ""

                when {
                    newName.isEmpty() -> inputName.error =
                        activity.getString(R.string.error_empty_name)

                    oldPass != storedPass -> inputOldPass.error =
                        activity.getString(R.string.error_incorrect_old_password)

                    newPass.isEmpty() -> inputNewPass.error =
                        activity.getString(R.string.error_empty_password)

                    newPass != confirmPass -> inputConfirmPass.error =
                        activity.getString(R.string.error_passwords_mismatch)

                    else -> {
                        onConfigSaved(newName, newPass)
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    override fun showDestructiveDeleteDialog(
        title: String,
        message: String,
        onConfirmed: () -> Unit
    ) {
        activity.runOnUiThread {
            activeAlertDialog =
                DialogFactory.showDestructiveDeleteDialog(activity, title, message, onConfirmed)
        }
    }

    override fun showBacklogImportPreview(
        onConfirm: (List<BacklogItem>) -> Unit,
        onDismiss: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialogView =
                LayoutInflater.from(activity).inflate(R.layout.dialog_list_preview, null)
            val rvPreview = dialogView.findViewById<RecyclerView>(R.id.rvPreviewList)
            val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmAction)
            backlogCountText = dialogView.findViewById<TextView>(R.id.txtPreviewHint)
            val txtTitle = dialogView.findViewById<TextView>(R.id.txtPreviewTitle)
            val progressBar = dialogView.findViewById<ProgressBar>(R.id.pbPreviewLoading)

            txtTitle.text = activity.getString(R.string.dialog_import_backlog_title)
            btnConfirm.text = activity.getString(R.string.dialog_import_backlog_button_text)
            progressBar.visibility = View.VISIBLE

            backlogAdapter = ImportBacklogAdapter()
            rvPreview.layoutManager = LinearLayoutManager(activity)
            rvPreview.adapter = backlogAdapter

            val dialog = BottomSheetDialog(activity)
            activeBottomSheet = dialog
            dialog.setContentView(dialogView)

            dialog.setOnDismissListener {
                activeBottomSheet = null
                backlogAdapter = null
                backlogCountText = null
                onDismiss()
            }

            btnConfirm.setOnClickListener {
                onConfirm(backlogAdapter?.getSelectedItems() ?: emptyList())
                dialog.dismiss()
            }

            with(DialogFactory) {
                dialog.showWithSmartNfcReading()
            }
        }
    }

    override fun addBacklogItem(item: BacklogItem) {
        activity.runOnUiThread {
            backlogAdapter?.addItem(item)
        }
    }

    override fun removeBacklogItem(item: BacklogItem) {
        activity.runOnUiThread {
            backlogAdapter?.removeItem(item)
        }
    }

    override fun updateBacklogCount(count: Int) {
        activity.runOnUiThread {
            backlogCountText?.text = activity.getString(R.string.dialog_import_backlog_hint, count)
        }
    }

    override fun toggleBacklogImportLoading(show: Boolean) {
        activity.runOnUiThread {
            val dialog = activeBottomSheet ?: return@runOnUiThread
            val progressBar = dialog.findViewById<ProgressBar>(R.id.pbPreviewLoading)
            val btnConfirm = dialog.findViewById<MaterialButton>(R.id.btnConfirmAction)

            progressBar?.visibility = if (show) View.VISIBLE else View.GONE
            btnConfirm?.isEnabled = !show
        }
    }

    override fun getBacklogItemCount(): Int = backlogAdapter?.itemCount ?: 0

    override fun setupReaderDiscoveryUI(
        onReaderEnabledChanged: (Boolean) -> Unit,
        onRefreshRequested: () -> Unit
    ) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutReaderManagementView) ?: return@runOnUiThread
            val switchUseReader =
                rootView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchUseReader)
            val recyclerView = rootView.findViewById<RecyclerView>(R.id.readerRecyclerView)
            val listRefresh =
                rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshReader)

            switchUseReader?.setOnCheckedChangeListener { _, isChecked ->
                onReaderEnabledChanged(isChecked)
            }

            listRefresh?.setOnRefreshListener {
                onRefreshRequested()
            }

            recyclerView?.layoutManager = LinearLayoutManager(activity)
        }
    }

    override fun updateDeviceList(
        connected: List<DeviceItem>,
        known: List<DeviceItem>,
        unknown: List<DeviceItem>,
        onDeviceSelected: (String, String) -> Unit,
        onDeviceLongClicked: (String, String) -> Unit
    ) {
        activity.runOnUiThread {
            val recyclerView =
                activity.findViewById<RecyclerView>(R.id.readerRecyclerView) ?: return@runOnUiThread
            val adapter = if (recyclerView.adapter !is DeviceListAdapter) {
                val newAdapter = DeviceListAdapter(onDeviceSelected, onDeviceLongClicked)
                recyclerView.adapter = newAdapter
                newAdapter
            } else {
                val existingAdapter = recyclerView.adapter as DeviceListAdapter
                existingAdapter.updateCallbacks(onDeviceSelected, onDeviceLongClicked)
                existingAdapter
            }
            adapter.submitList(connected, known, unknown)
        }
    }

    override fun setReaderEnabledState(enabled: Boolean) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutReaderManagementView) ?: return@runOnUiThread
            val switchUseReader =
                rootView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchUseReader)
            val listRefresh =
                rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshReader)

            switchUseReader?.isChecked = enabled
            listRefresh?.isEnabled = enabled
        }
    }

    override fun setDiscoveryRefreshing(isRefreshing: Boolean) {
        activity.runOnUiThread {
            activity.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshReader)?.isRefreshing =
                isRefreshing
        }
    }

    override fun openDeviceManager(name: String, address: String) {
        activity.runOnUiThread {
            secureStoreManager.deviceName = name
            activity.openDeviceManager(address)
        }
    }

    override fun setupReaderManagementUI(
        onEditDeviceRequested: () -> Unit,
        onSyncTimeRequested: () -> Unit,
        onForgetDeviceRequested: () -> Unit,
        onRefreshRequested: () -> Unit,
        onDisconnectRequested: () -> Unit,
        onConnectRequested: () -> Unit,
        onBacklogItemLongClicked: (BacklogItem) -> Unit
    ) {
        this.onDisconnectRequested = onDisconnectRequested
        this.onConnectRequested = onConnectRequested

        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutDeviceManagerView) ?: return@runOnUiThread

            rootView.findViewById<View>(R.id.btnEditDevice)
                ?.setOnClickListener { onEditDeviceRequested() }
            rootView.findViewById<View>(R.id.btnSyncTime)
                ?.setOnClickListener { onSyncTimeRequested() }
            rootView.findViewById<View>(R.id.btnForget)
                ?.setOnClickListener { onForgetDeviceRequested() }

            val swipeRefresh = rootView as? SwipeRefreshLayout
                ?: rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshDeviceManager)
            swipeRefresh?.setOnRefreshListener { onRefreshRequested() }

            val rvBacklog = rootView.findViewById<RecyclerView>(R.id.rvDeviceBacklog)
            rvBacklog?.layoutManager = LinearLayoutManager(activity)
            if (rvBacklog?.adapter !is BacklogAdapter) {
                rvBacklog?.adapter = BacklogAdapter(onBacklogItemLongClicked)
            }
        }
    }

    override fun updateReaderManagementHeader(
        deviceName: String,
        deviceMac: String,
        batteryLevel: String?,
        deviceTime: String?,
        backlogCount: String
    ) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutDeviceManagerView) ?: return@runOnUiThread
            rootView.findViewById<TextView>(R.id.txtDeviceName)?.text = deviceName
            rootView.findViewById<TextView>(R.id.txtDeviceMac)?.text = deviceMac
            rootView.findViewById<TextView>(R.id.txtStatFilesCount)?.text = backlogCount

            if (batteryLevel != null) {
                rootView.findViewById<TextView>(R.id.txtStatBattery)?.text = batteryLevel
            }
            if (deviceTime != null) {
                rootView.findViewById<TextView>(R.id.txtStatDeviceTime)?.text = deviceTime
            }
        }
    }

    override fun updateReaderManagementBacklog(items: List<BacklogItem>) {
        activity.runOnUiThread {
            val rv = activity.findViewById<RecyclerView>(R.id.rvDeviceBacklog)
            (rv?.adapter as? BacklogAdapter)?.submitList(items)
        }
    }

    override fun updateReaderManagementStatus(isReady: Boolean, isConnecting: Boolean) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutDeviceManagerView) ?: return@runOnUiThread
            val viewAccent = rootView.findViewById<View>(R.id.viewDeviceDetailAccent)

            val accentColor = when {
                isReady -> activity.getColor(R.color.chalk_green)
                isConnecting -> activity.getColor(R.color.chalk_orange)
                else -> android.graphics.Color.TRANSPARENT
            }
            viewAccent?.setBackgroundColor(accentColor)

            val btnDisconnect =
                rootView.findViewById<LinearLayout>(R.id.btnDisconnect) ?: return@runOnUiThread
            val imgDisconnect = btnDisconnect.getChildAt(0) as? ImageView
            val txtDisconnect = btnDisconnect.getChildAt(1) as? TextView

            if (isReady || isConnecting) {
                txtDisconnect?.text = activity.getString(R.string.action_disconnect)
                imgDisconnect?.setImageResource(R.drawable.ic_reader_disconnected)
            } else {
                txtDisconnect?.text = activity.getString(R.string.action_connect)
                imgDisconnect?.setImageResource(R.drawable.ic_reader_connected)
            }

            btnDisconnect.setOnClickListener {
                if (isReady || isConnecting) {
                    onDisconnectRequested?.invoke()
                } else {
                    onConnectRequested?.invoke()
                }
            }
        }
    }

    override fun setManagementRefreshing(isRefreshing: Boolean) {
        activity.runOnUiThread {
            val rootView =
                activity.findViewById<View>(R.id.layoutDeviceManagerView) ?: return@runOnUiThread
            val swipeRefresh = rootView as? SwipeRefreshLayout
                ?: rootView.findViewById<SwipeRefreshLayout>(R.id.swipeRefreshDeviceManager)
            swipeRefresh?.isRefreshing = isRefreshing
        }
    }
}
