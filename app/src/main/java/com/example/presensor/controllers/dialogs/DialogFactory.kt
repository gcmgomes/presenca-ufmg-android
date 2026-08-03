package com.example.presensor.controllers.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.FragmentManager
import com.example.presensor.R
import com.example.presensor.controllers.TagController
import com.example.presensor.tools.TimeUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object DialogFactory {

    private var isDialogOpen = false

    var tagController: TagController? = null



    /**
     * Public getter to let MainActivity or other controllers check if a
     * DialogFactory dialog is currently active before handling an NFC tag swipe.
     */
    fun isAnyDialogOpen(): Boolean = isDialogOpen

    fun resetForTesting() {
        isDialogOpen = false
    }

    fun setDialogOpenForTesting(open: Boolean) {
        isDialogOpen = open
    }



    // Private helper extension that manages the state tracker centrally
    fun AlertDialog.Builder.showWithSmartNfcReading(resumeReader: Boolean = false, onDismiss: (() -> Unit)? = null): AlertDialog {
        val dialog = this.create()
        dialog.setOnShowListener {
            isDialogOpen = true
            tagController?.pauseNfcScanning()
        }
        dialog.setOnDismissListener {
            isDialogOpen = false
            tagController?.resumeNfcScanning()
            if (resumeReader) {
                tagController?.resumeReader()
            }
            onDismiss?.invoke()
        }
        dialog.show()
        return dialog
    }

    fun BottomSheetDialog.showWithSmartNfcReading(resumeReader: Boolean = false, onDismiss: (() -> Unit)? = null): BottomSheetDialog {
        this.setOnShowListener {
            isDialogOpen = true
            tagController?.pauseNfcScanning()
        }
        this.setOnDismissListener {
            isDialogOpen = false
            tagController?.resumeNfcScanning()
            if (resumeReader) {
                tagController?.resumeReader()
            }
            onDismiss?.invoke()
        }
        this.show()
        return this
    }

    fun showDestructiveDeleteDialog(
        context: Context,
        title: String,
        message: String,
        onConfirmed: () -> Unit
    ): AlertDialog {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 0)
        }
        val input = EditText(context).apply {
            hint = context.getString(R.string.hint_delete_confirm)
            setSingleLine(true)
        }
        container.addView(input)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(container)
            .setPositiveButton(R.string.delete_action_text, null)
            .setNegativeButton(R.string.action_cancel, null)
            .showWithSmartNfcReading()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (input.text.toString().trim() == "DELETE") {
                onConfirmed()
                dialog.dismiss()
            } else {
                input.error = context.getString(R.string.error_delete_confirmation_mismatch)
            }
        }
        return dialog
    }

    fun showMappingDialog(
        context: Context,
        fields: List<String>,
        columns: List<String>,
        sampleRow: List<String>? = null,
        onDismissed: (() -> Unit)? = null,
        onConfirmed: (Map<String, String>) -> Unit
    ): AlertDialog {
        val layoutInflater = LayoutInflater.from(context)
        val view = layoutInflater.inflate(R.layout.dialog_import_mapping, null)
        val container = view.findViewById<LinearLayout>(R.id.mappingFieldsContainer)
        val previewContainer = view.findViewById<LinearLayout>(R.id.layoutPreviewContainer)
        
        // Populate Preview Section
        columns.forEachIndexed { index, header ->
            val previewItem = layoutInflater.inflate(R.layout.item_mapping_preview_column, previewContainer, false)
            previewItem.findViewById<TextView>(R.id.txtHeader).text = header
            previewItem.findViewById<TextView>(R.id.txtValue).text = sampleRow?.getOrNull(index) ?: context.getString(R.string.label_preview_empty_value)
            previewContainer.addView(previewItem)
        }

        val autoCompleteViews = mutableMapOf<String, AutoCompleteTextView>()
        
        fields.forEach { field ->
            val row = layoutInflater.inflate(R.layout.item_mapping_row, container, false) as TextInputLayout
            
            // Map internal field keys to localized display names
            val displayHint = when(field) {
                "name" -> context.getString(R.string.dialog_mapping_field_name)
                "email" -> context.getString(R.string.dialog_mapping_field_email)
                "date" -> context.getString(R.string.dialog_mapping_field_date)
                "start_time" -> context.getString(R.string.label_start_time)
                "end_time" -> context.getString(R.string.label_end_time)
                else -> field.replaceFirstChar { it.uppercase() }
            }
            row.hint = displayHint
            
            val autoComplete = row.findViewById<AutoCompleteTextView>(R.id.autoCompleteColumn)
            
            val noneLabel = context.getString(R.string.label_mapping_none)
            val selectableColumns = listOf(noneLabel) + columns
            val adapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, selectableColumns)
            autoComplete.setAdapter(adapter)
            
            // Try to auto-select if field name matches a column name
            val matchIdx = columns.indexOfFirst { it.contains(field, ignoreCase = true) || it.contains(displayHint, ignoreCase = true) }
            if (matchIdx != -1) {
                autoComplete.setText(columns[matchIdx], false)
            } else {
                autoComplete.setText(noneLabel, false)
            }
            
            autoCompleteViews[field] = autoComplete
            container.addView(row)
        }

        var confirmed = false
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.dialog_mapping_title))
            .setView(view)
            .setCancelable(true)
            .showWithSmartNfcReading()
            
        dialog.setOnDismissListener {
            isDialogOpen = false
            tagController?.resumeNfcScanning()
            if (!confirmed) {
                onDismissed?.invoke()
            }
        }
            
        view.findViewById<Button>(R.id.btnConfirmMapping).setOnClickListener {
            confirmed = true
            val resultMapping = autoCompleteViews.mapValues { it.value.text.toString() }
            onConfirmed(resultMapping)
            dialog.dismiss()
        }
        return dialog
    }

    fun showSessionEntryDialog(
        context: Context,
        fragmentManager: FragmentManager,
        titleResId: Int,
        positiveButtonResId: Int,
        initialName: String = "",
        initialDate: Long = System.currentTimeMillis(),
        initialStartTime: Long? = null,
        initialEndTime: Long? = null,
        onConfirmed: (String, Long, Long?, Long?) -> Unit
    ): AlertDialog {
        val layoutInflater = LayoutInflater.from(context)
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_session, null)
        val edtName = dialogView.findViewById<EditText>(R.id.edtSessionName)
        val edtDate = dialogView.findViewById<TextInputEditText>(R.id.edtSessionDate)
        val edtStartTime = dialogView.findViewById<TextInputEditText>(R.id.edtSessionStartTime)
        val edtEndTime = dialogView.findViewById<TextInputEditText>(R.id.edtSessionEndTime)

        edtName.setText(initialName)
        if (initialName.isNotEmpty()) edtName.selectAll()

        var selectedTimestamp = initialDate
        val pattern = context.getString(R.string.date_picker_display_format)
        val dateFormat = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        edtDate.setText(TimeUtils.fromMillisToLocalDate(selectedTimestamp).format(dateFormat))

        edtDate.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(context.getString(R.string.select_session_date))
                .setSelection(selectedTimestamp)
                .build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                selectedTimestamp = selection
                val localDate = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
                edtDate.setText(localDate.format(dateFormat))
            }
            datePicker.show(fragmentManager, "SESSION_DATE_PICKER")
        }

        var selectedStartTime = initialStartTime
        var selectedEndTime = initialEndTime

        val setupTimePicker = { view: TextInputEditText, initial: Long?, onSelected: (Long) -> Unit ->
            view.setText(TimeUtils.formatMinutesToTime(initial))
            view.setOnClickListener {
                val hour = (initial ?: (9 * 60)) / 60
                val minute = (initial ?: (9 * 60)) % 60
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                    .setHour(hour.toInt())
                    .setMinute(minute.toInt())
                    .setTitleText(view.hint)
                    .build()

                picker.addOnPositiveButtonClickListener {
                    val minutes = (picker.hour * 60 + picker.minute).toLong()
                    view.setText(TimeUtils.formatMinutesToTime(minutes))
                    onSelected(minutes)
                }
                picker.show(fragmentManager, "SESSION_TIME_PICKER")
            }
        }

        setupTimePicker(edtStartTime, selectedStartTime) { selectedStartTime = it }
        setupTimePicker(edtEndTime, selectedEndTime) { selectedEndTime = it }

        edtName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(edtName.windowToken, 0)
                edtName.clearFocus()
                true
            } else {
                false
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleResId)
            .setView(dialogView)
            .setPositiveButton(positiveButtonResId, null)
            .setNegativeButton(R.string.action_cancel, null)
            .showWithSmartNfcReading()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nameText = edtName.text.toString().trim()
            if (nameText.isNotEmpty()) {
                // Normalize to start of day in local timezone to maintain consistency
                val normalizedDate = TimeUtils.fromMillisToLocalDate(selectedTimestamp)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onConfirmed(nameText, normalizedDate, selectedStartTime, selectedEndTime)
                dialog.dismiss()
            } else {
                Toast.makeText(context, context.getString(R.string.error_empty_name), Toast.LENGTH_SHORT).show()
            }
        }
        return dialog
    }
}
