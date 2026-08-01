package com.example.presensor

import android.os.Bundle
import androidx.activity.OnBackPressedCallback

class MainActivityForTest : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun initializeDependenciesAndControllers() {
        // Skip heavy init
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        
        // Mock minimal required lateinits if they are accessed during lifecycle
        currentBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        }
    }
}
