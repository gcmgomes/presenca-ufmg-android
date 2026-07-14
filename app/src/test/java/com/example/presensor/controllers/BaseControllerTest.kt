package com.example.presensor.controllers

import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.presensor.data.AppDatabase
import com.example.presensor.rules.MainDispatcherRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.example.presensor.controllers.dialogs.DialogFactory
import kotlinx.coroutines.asExecutor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
abstract class BaseControllerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    protected lateinit var db: AppDatabase
    protected lateinit var activity: AppCompatActivity

    @Before
    open fun setup() {
        DialogFactory.resetForTesting()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).setQueryExecutor(mainDispatcherRule.testDispatcher.asExecutor())
            .setTransactionExecutor(mainDispatcherRule.testDispatcher.asExecutor())
            .allowMainThreadQueries().build()
        
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
    }

    @After
    open fun tearDown() {
        db.close()
    }
}
