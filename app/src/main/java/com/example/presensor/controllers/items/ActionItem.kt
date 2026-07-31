package com.example.presensor.controllers.items

/**
 * UI representation of an action item in the dashboard or course view.
 */
data class ActionItem(
    val text: String,
    val iconResId: Int,
    val onClick: () -> Unit
)
