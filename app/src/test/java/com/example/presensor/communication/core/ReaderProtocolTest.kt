package com.example.presensor.communication.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderProtocolTest {
    private lateinit var protocol: ReaderProtocol

    @Before
    fun setup() {
        protocol = ReaderProtocol()
    }

    // --- Command Formatting Tests ---

    @Test
    fun `formatAuthCommand should return password bytes`() {
        val pass = "secret123"
        val expected = pass.toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, protocol.formatAuthCommand(pass))
    }

    @Test
    fun `formatAppModeCommand should return correct strings`() {
        assertEquals("IDLE", String(protocol.formatAppModeCommand(AppMode.IDLE)))
        assertEquals("ACTIVE", String(protocol.formatAppModeCommand(AppMode.ACTIVE)))
        assertEquals("MANAGEMENT", String(protocol.formatAppModeCommand(AppMode.MANAGEMENT)))
    }

    @Test
    fun `formatTimeSyncCommand should return epoch string`() {
        val epoch = 1625097600L
        assertEquals("1625097600", String(protocol.formatTimeSyncCommand(epoch)))
    }

    @Test
    fun `formatInventoryListCommand should return LIST`() {
        assertEquals("LIST", String(protocol.formatInventoryListCommand()))
    }

    @Test
    fun `formatInventoryDeleteCommand should remove colons and return DEL csv`() {
        val tagId = "AA:BB:CC:DD"
        val timestamp = 12345L
        assertEquals(
            "DEL,AABBCCDD,12345",
            String(protocol.formatInventoryDeleteCommand(tagId, timestamp))
        )
    }

    @Test
    fun `formatSyncCommand should return SYNC`() {
        assertEquals("SYNC", String(protocol.formatSyncCommand()))
    }

    @Test
    fun `formatAckCommand should return csv bytes`() {
        assertEquals("TAG1,123", String(protocol.formatAckCommand("TAG1", "123")))
    }

    @Test
    fun `formatConfigUpdateCommand should return tab separated bytes`() {
        assertEquals(
            "NewName\tNewPass",
            String(protocol.formatConfigUpdateCommand("NewName", "NewPass"))
        )
    }

    // --- Data Parsing Tests ---

    @Test
    fun `processData should emit AuthSuccess on SUCCESS payload`() = runBlocking {
        val job = launch {
            protocol.processData("SUCCESS".toByteArray(), TransportChannel.AUTH)
        }

        val event = withTimeout(1000) { protocol.domainEvents.first() }
        assertTrue(event is ProtocolEvent.AuthSuccess)
        assertTrue(protocol.isAuthenticated.value)
        job.join()
    }

    @Test
    fun `processData should emit AuthFailed on FAIL payload`() = runBlocking {
        val job = launch {
            protocol.processData("FAIL".toByteArray(), TransportChannel.AUTH)
        }

        val event = withTimeout(1000) { protocol.domainEvents.first() }
        assertTrue(event is ProtocolEvent.AuthFailed)
        assertFalse(protocol.isAuthenticated.value)
        job.join()
    }

    @Test
    fun `processData should emit RfidSwipe and AckRequired on DATA channel`() = runBlocking {
        val payload = "AA:BB:CC:DD,1624612345".toByteArray()
        val events = mutableListOf<ProtocolEvent>()

        val collectJob = launch {
            protocol.domainEvents.take(2).toList(events)
        }

        kotlinx.coroutines.delay(50)
        protocol.processData(payload, TransportChannel.DATA)

        withTimeout(1000) { collectJob.join() }

        assertEquals(2, events.size)
        assertTrue(events[0] is ProtocolEvent.RfidSwipe)
        assertTrue(events[1] is ProtocolEvent.AckRequired)

        val swipe = events[0] as ProtocolEvent.RfidSwipe
        assertEquals("AA:BB:CC:DD", swipe.tagId)
        assertEquals(1624612345L, swipe.timestamp)
    }

    @Test
    fun `processData should emit SyncDone on DONE payload`() = runBlocking {
        val job = launch {
            protocol.processData("DONE".toByteArray(), TransportChannel.DATA)
        }
        val event = withTimeout(1000) { protocol.domainEvents.first() }
        assertTrue(event is ProtocolEvent.SyncDone)
        job.join()
    }

    @Test
    fun `processData should ignore INFO payload in INVENTORY channel (legacy)`() = runBlocking {
        val payload = "INFO,1624612345,85".toByteArray()
        val events = mutableListOf<ProtocolEvent>()
        val job = launch {
            protocol.domainEvents.toList(events)
        }

        protocol.processData(payload, TransportChannel.INVENTORY)
        kotlinx.coroutines.delay(100)

        // Metrics event should NOT be emitted from INVENTORY channel anymore
        assertTrue(events.none { it is ProtocolEvent.Metrics })
        job.cancel()
    }

    @Test
    fun `processData should emit DeletionSuccess on DEL_OK`() = runBlocking {
        val job = launch {
            protocol.processData("DEL_OK".toByteArray(), TransportChannel.INVENTORY)
        }
        val event = withTimeout(1000) { protocol.domainEvents.first() }
        assertTrue(event is ProtocolEvent.DeletionSuccess)
        job.join()
    }

    @Test
    fun `processData should emit Metrics on STATUS channel`() = runBlocking {
        val payload = "1624612345,90".toByteArray()
        val job = launch {
            protocol.processData(payload, TransportChannel.STATUS)
        }
        val event = withTimeout(1000) { protocol.domainEvents.first() }
        assertTrue(event is ProtocolEvent.Metrics)
        val metrics = event as ProtocolEvent.Metrics
        assertEquals(1624612345L, metrics.timestamp)
        assertEquals(90, metrics.batteryLevel)
        job.join()
    }

    @Test
    fun `processData should emit InventoryItem on INVENTORY channel`() = runBlocking {
        val payload = "TAG_ID,1624612345".toByteArray()
        val job = launch {
            protocol.processData(payload, TransportChannel.INVENTORY)
        }
        val event = withTimeout(1000) { protocol.domainEvents.first() }
        assertTrue(event is ProtocolEvent.InventoryItem)
        val item = event as ProtocolEvent.InventoryItem
        assertEquals("TAG_ID", item.tagId)
        assertEquals(1624612345L, item.timestamp)
        job.join()
    }

    @Test
    fun `processData should handle malformed payload without crashing`() = runBlocking {
        // Just verify no exception is thrown
        protocol.processData("MALFORMED".toByteArray(), TransportChannel.DATA)
        protocol.processData("PART1,NOT_A_NUMBER".toByteArray(), TransportChannel.DATA)
        protocol.processData("INFO,NOT_LONG,NOT_INT".toByteArray(), TransportChannel.INVENTORY)
    }
}
