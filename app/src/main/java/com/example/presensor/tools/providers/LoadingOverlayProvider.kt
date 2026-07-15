package com.example.presensor.tools.providers

import kotlinx.coroutines.Job

interface LoadingOverlayProvider {
    fun toggleLoadingOverlay(show: Boolean)
    fun setCurrentOverlayJob(job: Job?)
}
