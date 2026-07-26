package com.example.presensor.controllers

import android.view.View
import com.example.presensor.MainActivity
import com.example.presensor.R
import com.example.presensor.communication.ReaderEvent
import com.example.presensor.communication.ReaderOrchestrator
import com.example.presensor.data.SecureStoreManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDiscoveryControllerTest : BaseControllerTest() {

    private lateinit var controller: ReaderDiscoveryController
    private val mockMainActivity: MainActivity = mock()
    private val mockSecureStore: SecureStoreManager = mock()
    private val mockOrchestrator: ReaderOrchestrator = mock()
    private val mockInteractionProvider = MockReaderInteractionProvider()
    
    private val eventFlow = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 1)
    private val isReaderEnabledFlow = MutableStateFlow(false)

    @Before
    override fun setup() {
        super.setup()
        
        whenever(mockMainActivity.readerOrchestrator).thenReturn(mockOrchestrator)
        whenever(mockOrchestrator.eventFlow).thenReturn(eventFlow)
        whenever(mockOrchestrator.isReaderEnabled).thenReturn(isReaderEnabledFlow)
        
        controller = ReaderDiscoveryController(
            activity = mockMainActivity,
            secureStoreManager = mockSecureStore,
            interactionProvider = mockInteractionProvider,
            scope = TestScope(mainDispatcherRule.testDispatcher)
        )
    }

    @Test
    fun `handleReaderSelection no password triggers prompt`() = runTest {
        val readerName = "TestReader"
        val address = "00:11:22:33:44:55"
        whenever(mockSecureStore.getAuthPasswordFor(readerName)).thenReturn(null)
        
        controller.handleReaderSelection(readerName, address)
        
        assert(mockInteractionProvider.lastPasswordReaderName == readerName)
    }

    @Test
    fun `handleReaderSelection with password initiates connection`() = runTest {
        val readerName = "TestReader"
        val address = "00:11:22:33:44:55"
        val password = "pass"
        whenever(mockSecureStore.getAuthPasswordFor(readerName)).thenReturn(password)
        
        controller.handleReaderSelection(readerName, address)
        
        verify(mockOrchestrator).startConnecting(eq(readerName), eq(password), eq(address), eq(true))
        assert(mockInteractionProvider.lastToastResId == R.string.status_connecting)
    }

    @Test
    fun `teardownDiscovery fullDisconnect calls orchestrator disconnect`() {
        controller.teardownDiscovery(fullDisconnect = true)
        verify(mockOrchestrator).disconnect()
    }
}
