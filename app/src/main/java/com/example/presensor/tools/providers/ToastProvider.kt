package com.example.presensor.tools.providers

import android.content.Context
import android.widget.Toast

interface ToastProvider {
    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT)
}

class AndroidToastProvider(private val context: Context) : ToastProvider {
    override fun showToast(message: String, duration: Int) {
        Toast.makeText(context, message, duration).show()
    }
}
