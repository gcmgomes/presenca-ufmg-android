package com.example.presensor.controllers

import org.mockito.kotlin.mock

/**
 * A test-only implementation of ReaderInteractionProvider.
 * Captures calls to UI methods for verification in unit tests.
 */
class MockReaderInteractionProvider : ReaderInteractionProvider {
    
    var lastToastResId: Int? = null
    var lastToastIsShort: Boolean? = null
    
    var onPasswordEntered: ((String) -> Unit)? = null
    var onPasswordDismissed: (() -> Unit)? = null
    var lastPasswordReaderName: String? = null
    
    var onConfigSaved: ((String, String) -> Unit)? = null
    var lastEditReaderName: String? = null
    
    var onDestructiveConfirmed: (() -> Unit)? = null
    var lastDestructiveTitle: String? = null
    var lastDestructiveMessage: String? = null

    override fun showToast(msgResId: Int, isShort: Boolean) {
        lastToastResId = msgResId
        lastToastIsShort = isShort
    }

    override fun showPasswordPromptDialog(
        readerName: String,
        onPasswordEntered: (String) -> Unit,
        onDismissed: () -> Unit
    ) {
        lastPasswordReaderName = readerName
        this.onPasswordEntered = onPasswordEntered
        this.onPasswordDismissed = onDismissed
    }

    override fun showEditReaderDialog(
        readerName: String,
        onConfigSaved: (newName: String, newPass: String) -> Unit
    ) {
        lastEditReaderName = readerName
        this.onConfigSaved = onConfigSaved
    }

    override fun showDestructiveDeleteDialog(
        title: String,
        message: String,
        onConfirmed: () -> Unit
    ) {
        lastDestructiveTitle = title
        lastDestructiveMessage = message
        this.onDestructiveConfirmed = onConfirmed
    }
}
