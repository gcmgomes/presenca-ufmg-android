package com.example.presensor.controllers

import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.SecureStoreManager
import com.example.presensor.controllers.items.BacklogItem
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
    private val mockSecureStore: SecureStoreManager = mock()
    private val mockOrchestrator: ReaderOrchestrator = mock()
    private val mockInteractionProvider = MockReaderInteractionProvider()

    @Before
    override fun setup() {
        super.setup()

        whenever(mockOrchestrator.isAuthenticated).thenReturn(MutableStateFlow(false))
        whenever(mockOrchestrator.connectionState).thenReturn(MutableStateFlow(ReaderOrchestrator.ConnectionState.DISCONNECTED))
        whenever(mockOrchestrator.metricsFlow).thenReturn(MutableSharedFlow())
        whenever(mockOrchestrator.inventoryFlow).thenReturn(MutableSharedFlow())
        whenever(mockOrchestrator.discoveredDevices).thenReturn(MutableStateFlow(emptyList()))

        controller = ReaderManagementController(
            db = db,
            secureStoreManager = mockSecureStore,
            interactionProvider = mockInteractionProvider,
            orchestrator = mockOrchestrator,
            scope = TestScope(mainDispatcherRule.testDispatcher)
        )
    }

    @Test
    fun `handleBacklogItemLongClick triggers destructive dialog`() {
        val item = BacklogItem("TAG123", null, 1000L)
        controller.handleBacklogItemLongClick(item)
        assert(mockInteractionProvider.lastDestructiveTitle != null)
    }

    @Test
    fun `teardownView cancels jobs`() {
        controller.teardownView()
        verify(mockOrchestrator).setAppMode(any(), any())
    }
}
