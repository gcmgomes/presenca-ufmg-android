package com.example.presensor.data

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.presensor.CourseUtilities
import com.example.presensor.data.entities.Course
import com.example.presensor.data.entities.Session
import com.example.presensor.data.entities.Student
import com.example.presensor.data.entities.Attendance
import com.example.presensor.data.CourseCache
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.collections.plus

@Database(
    entities = [Course::class, Session::class, Student::class, Attendance::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PresensorDao

    private val courseCache = CourseCache()

    fun getCourseCache(): CourseCache {
        return courseCache
    }

    fun insertSession(scope: LifecycleCoroutineScope, courseId: Long, sessionName: String, date: Long) {
        scope.launch {
            val newSession = dao().insertSession(
                Session(
                    courseId = courseId,
                    name = sessionName,
                    date = date
                )
            )


            getCourseCache().sessionIds += newSession
            getCourseCache().allSessions
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val dbCallback = object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Performance optimization: Keep Session & Attendance indexes in RAM
                        db.execSQL("PRAGMA cache_size = 4000;")
                        db.execSQL("PRAGMA foreign_keys = ON;")
                        db.execSQL("PRAGMA optimize;")
                    }
                }

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "presensor-db"
                )
                    .addCallback(dbCallback)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}