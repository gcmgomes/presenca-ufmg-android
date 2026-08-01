package com.example.presensor.controllers.providers

import android.widget.LinearLayout
import com.example.presensor.MainActivityForTest
import com.example.presensor.controllers.BaseControllerTest
import com.example.presensor.data.entities.Course
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric

class AndroidDetailedCourseInteractionProviderTest : BaseControllerTest() {

    private lateinit var testActivity: MainActivityForTest
    private lateinit var provider: AndroidDetailedCourseInteractionProvider

    @Before
    override fun setup() {
        super.setup()
        testActivity = Robolectric.buildActivity(MainActivityForTest::class.java).create().get()
        provider = AndroidDetailedCourseInteractionProvider(testActivity)
    }

    @Test
    fun `openDetailedCourseView inflates view in container`() {
        val container = LinearLayout(testActivity).apply { id = com.example.presensor.R.id.layoutCourseStatisticsView }
        testActivity.setContentView(container)
        
        provider.openDetailedCourseView({}, {})
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        
        assert(container.childCount > 0)
    }

    @Test
    fun `updateDetailedCourseHeader populates stats card`() {
        val course = Course(name = "Test Course")
        val container = LinearLayout(testActivity).apply { id = com.example.presensor.R.id.layoutCourseStatisticsView }
        testActivity.setContentView(container)
        
        // Need to inflate stats view first so it has the expected views
        provider.openDetailedCourseView({}, {})
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        
        provider.updateDetailedCourseHeader(course, emptySet(), emptySet(), emptyList())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        
        // No crash and card population logic reached
    }
}
