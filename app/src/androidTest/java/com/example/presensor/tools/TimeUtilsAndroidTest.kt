package com.example.presensor.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeUtilsAndroidTest {

    @Test
    fun makeSessionTimeFormatter_ReturnsValidFormatter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val formatter = TimeUtils.makeSessionTimeFormatter(context)
        assertNotNull(formatter)
        
        // Try to format something to ensure it works
        val date = java.time.LocalDate.of(2023, 12, 25)
        val formatted = formatter.format(date)
        assertNotNull(formatted)
    }
}
