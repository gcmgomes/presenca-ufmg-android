package com.example.presensor.controllers

/**
 * Interface for abstracting UI interactions like Toasts and Dialogs.
 * This allows controllers to remain headless and testable.
 */
interface ReaderInteractionProvider {
    
    /**
     * Displays a short or long duration toast message.
     */
    fun showToast(msgResId: Int, isShort: Boolean = true)

    /**
     * Prompts the user for a device password.
     */
    fun showPasswordPromptDialog(
        readerName: String,
        onPasswordEntered: (String) -> Unit,
        onDismissed: () -> Unit
    )

    /**
     * Displays the reader configuration edit dialog.
     */
    fun showEditReaderDialog(
        readerName: String,
        onConfigSaved: (newName: String, newPass: String) -> Unit
    )

    /**
     * Displays a confirmation dialog for destructive actions (e.g., deleting a tag).
     */
    fun showDestructiveDeleteDialog(
        title: String,
        message: String,
        onConfirmed: () -> Unit
    )
}
