package com.example.presensor.controllers.providers

import com.example.presensor.MainActivity
import com.example.presensor.controllers.dialogs.SessionControllerDialogFactory
import com.example.presensor.controllers.dialogs.TagControllerDialogFactory
import com.example.presensor.controllers.dialogs.CourseControllerDialogFactory
import com.example.presensor.data.SecureStoreManager
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class AndroidInteractionProviderTest {

    private val activity: MainActivity = mock()
    private val secureStoreManager: SecureStoreManager = mock()
    private val tagDialogFactory: TagControllerDialogFactory = mock()
    private val sessionDialogFactory: SessionControllerDialogFactory = mock()
    private val courseDialogFactory: CourseControllerDialogFactory = mock()

    private lateinit var provider: AndroidInteractionProvider

    @Before
    fun setup() {
        provider = AndroidInteractionProvider(
            activity,
            secureStoreManager,
            tagDialogFactory,
            sessionDialogFactory,
            courseDialogFactory
        )
    }

    @Test
    fun `toggleLoading delegates to activity`() {
        doAnswer {
            (it.arguments[0] as Runnable).run()
            null
        }.whenever(activity).runOnUiThread(any())

        provider.toggleLoading(true)
        verify(activity).toggleLoadingOverlay(true)
    }

    @Test
    fun `showDeleteSessionDialog delegates to factory`() {
        doAnswer {
            (it.arguments[0] as Runnable).run()
            null
        }.whenever(activity).runOnUiThread(any())

        val mockSession = mock<com.example.presensor.data.entities.Session>()
        provider.showDeleteSessionDialog(mockSession)
        verify(sessionDialogFactory).showDeleteSessionDialog(mockSession)
    }
}
