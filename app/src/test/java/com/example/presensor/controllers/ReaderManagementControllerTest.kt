package com.example.presensor.controllers

import com.example.presensor.MainActivity
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.SecureStoreManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderManagementControllerTest : BaseControllerTest() {

    private lateinit var controller: ReaderManagementController
    private val mockMainActivity: MainActivity = mock()
    private val mockSecureStore: SecureStoreManager = mock()
    private val mockOrchestrator: ReaderOrchestrator = mock()
    private val mockInteractionProvider = MockReaderInteractionProvider()

    @Before
    override fun setup() {
        super.setup()
        
        whenever(mockMainActivity.readerOrchestrator).thenReturn(mockOrchestrator)
        whenever(mockOrchestrator.isAuthenticated).thenReturn(MutableStateFlow(false))
        whenever(mockOrchestrator.connectionState).thenReturn(MutableStateFlow(ReaderOrchestrator.ConnectionState.DISCONNECTED))
        whenever(mockOrchestrator.metricsFlow).thenReturn(MutableSharedFlow())
        whenever(mockOrchestrator.inventoryFlow).thenReturn(MutableSharedFlow())

        controller = ReaderManagementController(
            activity = mockMainActivity,
            db = db,
            secureStoreManager = mockSecureStore,
            interactionProvider = mockInteractionProvider,
            scope = TestScope(mainDispatcherRule.testDispatcher)
        )
    }

    @Test
    fun `handleBacklogItemLongClick triggers destructive dialog`() {
        val item = ReaderManagementController.BacklogItem("TAG123", "Student", 1000L)
        controller.handleBacklogItemLongClick(item)
        assert(mockInteractionProvider.lastDestructiveTitle != null)
    }

    @Test
    fun `teardownView cancels jobs`() {
        controller.teardownView()
        verify(mockOrchestrator).setAppMode(any(), any())
    }
}
