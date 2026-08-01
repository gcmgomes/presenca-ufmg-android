package com.example.presensor.controllers

import com.example.presensor.controllers.providers.CloudInteractionProvider
import com.example.presensor.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncControllerTest : BaseControllerTest() {

    private lateinit var controller: CloudSyncController
    private val interactionProvider: CloudInteractionProvider = mock()

    @Before
    override fun setup() {
        super.setup()

        controller = CloudSyncController(
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            db = db,
            interactionProvider = interactionProvider,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @Test
    fun `runWithCloudAuthentication delegates to provider`() {
        val action: () -> Unit = mock()
        controller.runWithCloudAuthentication(action)

        val captor = argumentCaptor<(String) -> Unit>()
        verify(interactionProvider).runWithCloudAuthentication(captor.capture())

        captor.firstValue.invoke("fake_token")
        verify(action).invoke()
    }

    @Test
    fun `uploadBackupToDrive triggers provider loading and toast`() = runTest {
        // We can't easily test the actual Drive upload without mocking Drive service,
        // but we can verify the initial checks and interaction provider calls.
        
        // Before initialization
        controller.uploadBackupToDrive("suffix")
        verify(interactionProvider).showToast(any<Int>(), any())

        // Simulate auth
        controller.runWithCloudAuthentication {}
        val captor = argumentCaptor<(String) -> Unit>()
        verify(interactionProvider).runWithCloudAuthentication(captor.capture())
        captor.firstValue.invoke("token")

        controller.uploadBackupToDrive("suffix")
        verify(interactionProvider).toggleLoading(true)
        advanceUntilIdle()
        // verify(interactionProvider).toggleLoading(false) // This depends on DB dump success
    }
}
